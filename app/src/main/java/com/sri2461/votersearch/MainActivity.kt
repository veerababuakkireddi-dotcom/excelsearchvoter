package com.sri2461.votersearch

import android.content.Intent
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var db: VoterDb
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var wardSpinner: Spinner
    private lateinit var areaSpinner: Spinner
    private lateinit var genderSpinner: Spinner
    private lateinit var ageSpinner: Spinner
    private lateinit var results: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var alphabetBar: LinearLayout
    private var alphabetLetter: String? = null

    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val runSearch = Runnable { search() }
    private var wardNumbers: List<Int> = emptyList()
    private var areaNames: List<String> = emptyList()
    private var genderNames: List<String> = emptyList()
    private var total = 0

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = VoterDb(this)
        makeUi()
        removeOldOcrFiles()
        if (db.count() == 0) loadBundledList() else onListLoaded()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 247, 252))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(dp(14) + bars.left, dp(8) + bars.top, dp(14) + bars.right, dp(8) + bars.bottom)
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = rounded(0xFF174A8B.toInt(), 18)
        }
        header.addView(TextView(this).apply {
            text = "Voter Directory"
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "Search voters quickly by name, ID or house number"
            textSize = 11f
            setTextColor(0xFFE1ECFA.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })

        input = EditText(this).apply {
            hint = "Search in English / Telugu, voter ID, house no."
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(Color.WHITE, 14, 0xFFDCE4EF.toInt())
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
            }
            doAfterTextChanged {
                handler.removeCallbacks(runSearch)
                handler.postDelayed(runSearch, 300)
            }
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchRow.addView(input, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        searchRow.addView(actionButton("Search", 0xFF1769AA.toInt()) { search() },
            LinearLayout.LayoutParams(dp(82), dp(44)))
        root.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wardSpinner = Spinner(this).apply { background = rounded(Color.WHITE, 12, 0xFFDCE4EF.toInt()) }
        areaSpinner = Spinner(this).apply { background = rounded(Color.WHITE, 12, 0xFFDCE4EF.toInt()) }
        filterRow.addView(wardSpinner, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
        filterRow.addView(areaSpinner, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(6) })
        root.addView(filterRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        val extraFilterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        genderSpinner = Spinner(this).apply { background = rounded(Color.WHITE, 12, 0xFFDCE4EF.toInt()) }
        ageSpinner = Spinner(this).apply { background = rounded(Color.WHITE, 12, 0xFFDCE4EF.toInt()) }
        extraFilterRow.addView(genderSpinner, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
        extraFilterRow.addView(ageSpinner, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(6) })
        root.addView(extraFilterRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })

        root.addView(TextView(this).apply {
            text = "QUICK JUMP · ENGLISH NAME"
            textSize = 10f
            letterSpacing = 0.06f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF64748B.toInt())
            setPadding(dp(2), 0, 0, dp(3))
        })
        val alphabetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        alphabetBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        alphabetScroll.addView(alphabetBar)
        buildAlphabet()
        root.addView(alphabetScroll, LinearLayout.LayoutParams(-1, dp(35)).apply { bottomMargin = dp(3) })

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF475569.toInt())
            setPadding(dp(3), dp(4), dp(3), dp(7))
        }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(8)) }
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottomActions.addView(actionButton("Duplicates", 0xFF6D4BC3.toInt()) {
            startActivity(Intent(this@MainActivity, DuplicatesActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        bottomActions.addView(actionButton("Import Excel / CSV", 0xFF16836A.toInt()) {
            picker.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/csv", "text/comma-separated-values", "application/vnd.ms-excel", "application/octet-stream"
            ))
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        root.addView(bottomActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        setContentView(root)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun actionButton(label: String, color: Int, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(color, 12)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { action() }
    }

    private fun buildAlphabet() {
        alphabetBar.removeAllViews()
        val letters = listOf("All") + ('A'..'Z').map { it.toString() }
        letters.forEach { letter ->
            val selected = if (letter == "All") alphabetLetter == null else alphabetLetter == letter
            val chip = TextView(this).apply {
                text = letter
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (selected) Color.WHITE else 0xFF31516F.toInt())
                background = rounded(if (selected) 0xFF1769AA.toInt() else Color.WHITE, 9,
                    if (selected) null else 0xFFE0E7EF.toInt())
                setPadding(dp(9), dp(5), dp(9), dp(5))
                setOnClickListener {
                    alphabetLetter = if (letter == "All") null else letter
                    buildAlphabet()
                    search()
                }
            }
            alphabetBar.addView(chip, LinearLayout.LayoutParams(-2, dp(29)).apply { rightMargin = dp(4) })
        }
    }

    private fun removeOldOcrFiles() {
        worker.execute {
            File(filesDir, "cards").deleteRecursively()
            File(filesDir, "tesseract").deleteRecursively()
            File(filesDir, "voter_source.pdf").delete()
        }
    }

    // ---------------------------------------------------------------- loading

    private fun loadBundledList() {
        busy("Loading voter list…")
        worker.execute {
            try {
                val bytes = assets.open("voters.csv").use { it.readBytes() }
                db.replaceAll(rowsToVoters(parseCsv(String(bytes, Charsets.UTF_8))))
                runOnUiThread { onListLoaded() }
            } catch (e: Exception) {
                fail("Could not load the built-in voter list: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Replace voter list?")
            .setMessage("The selected file will replace the voter list currently saved in this app.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Import") { _, _ -> importFile(uri) }
            .show()
    }

    private fun importFile(uri: Uri) {
        busy("Importing…")
        worker.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to open the selected file")
                val isXlsx = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                val rows = if (isXlsx) XlsxReader.read(bytes)
                else parseCsv(String(bytes, Charsets.UTF_8))
                val voters = rowsToVoters(rows)
                check(voters.isNotEmpty()) { "No voter rows were found in the file" }
                db.replaceAll(voters)
                runOnUiThread {
                    onListLoaded()
                    Toast.makeText(this, "Imported ${voters.size} voters", Toast.LENGTH_LONG).show()
                }
            } catch (e: OutOfMemoryError) {
                fail("The file is too large for this phone's memory.")
            } catch (e: Exception) {
                fail("Import failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun busy(message: String) {
        progress.visibility = View.VISIBLE
        status.text = message
    }

    private fun fail(message: String) {
        runOnUiThread {
            progress.visibility = View.GONE
            status.text = message
        }
    }

    private fun onListLoaded() {
        progress.visibility = View.GONE
        total = db.count()
        wardNumbers = db.wards()
        areaNames = db.areas()
        genderNames = db.genders()
        wardSpinner.onItemSelectedListener = null
        wardSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("All wards") + wardNumbers.map { "Ward $it" }
        )
        areaSpinner.onItemSelectedListener = null
        areaSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("All areas") + areaNames.map { areaLabel(it) }
        )
        genderSpinner.onItemSelectedListener = null
        genderSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("All genders") + genderNames.map { genderLabel(it) }
        )
        ageSpinner.onItemSelectedListener = null
        ageSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("All ages", "18–25", "26–40", "41–60", "60+")
        )
        val filterListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = search()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        wardSpinner.onItemSelectedListener = filterListener
        areaSpinner.onItemSelectedListener = filterListener
        genderSpinner.onItemSelectedListener = filterListener
        ageSpinner.onItemSelectedListener = filterListener
        search()
    }

    // ---------------------------------------------------------------- searching

    private fun selectedWard(): Int? {
        val pos = wardSpinner.selectedItemPosition
        return if (pos <= 0) null else wardNumbers.getOrNull(pos - 1)
    }

    private fun selectedArea(): String? {
        val pos = areaSpinner.selectedItemPosition
        return if (pos <= 0) null else areaNames.getOrNull(pos - 1)
    }

    private fun selectedGender(): String? {
        val pos = genderSpinner.selectedItemPosition
        return if (pos <= 0) null else genderNames.getOrNull(pos - 1)
    }

    private fun selectedAgeGroup(): String? {
        val pos = ageSpinner.selectedItemPosition
        return if (pos <= 0) null else listOf("18–25", "26–40", "41–60", "60+").getOrNull(pos - 1)
    }

    private fun genderLabel(value: String): String = when (value.trim().lowercase()) {
        "m", "male" -> "Male"
        "f", "female" -> "Female"
        else -> value
    }

    private fun areaLabel(value: String): String = when (value.trim()) {
        "గోపాలపట్నం" -> "Gopalapatnam"
        "పెద్దిపాలెం" -> "Peddipalem"
        else -> value
    }

    private fun search() {
        handler.removeCallbacks(runSearch)
        results.removeAllViews()
        if (total == 0) return
        val query = input.text.toString().trim()
        val ward = selectedWard()
        val area = selectedArea()
        val gender = selectedGender()
        val ageGroup = selectedAgeGroup()
        if (query.isEmpty() && ward == null && area == null && gender == null && ageGroup == null && alphabetLetter == null) {
            status.text = "$total voters saved on this phone. Search by name, voter ID, house number or choose A–Z."
            return
        }
        val result = db.search(query, ward, MAX_RESULTS, alphabetLetter, area, gender, ageGroup)
        status.text = if (result.total > result.rows.size) {
            "${result.total} matches — showing the first ${result.rows.size}. Type more to narrow down."
        } else {
            "${result.total} matching voters."
        }
        result.rows.forEach { results.addView(card(it)) }
    }

    private fun line(text: String, size: Float = 14f, color: Int = 0xFF222222.toInt(), bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(v: Voter): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        box.setPadding(dp(12), dp(12), dp(12), dp(12))
        box.background = rounded(Color.WHITE, 14, 0xFFE5EAF1.toInt())
        box.addView(line(v.name.ifBlank { v.nameEn }, 18f, 0xFF183B63.toInt(), bold = true))
        if (v.name.isNotBlank() && v.nameEn.isNotBlank()) {
            box.addView(line(v.nameEn, 15f, 0xFF555555.toInt()))
        }
        if (v.relName.isNotBlank()) {
            val label = if (v.relation.isNotBlank()) v.relation else "Husband/Father"
            val en = if (v.relNameEn.isNotBlank()) " (${v.relNameEn})" else ""
            box.addView(line("$label: ${v.relName}$en"))
        }
        val details = listOfNotNull(
            v.age.takeIf { it.isNotBlank() }?.let { "Age $it" },
            v.gender.takeIf { it.isNotBlank() },
            v.house.takeIf { it.isNotBlank() }?.let { "House $it" }
        )
        if (details.isNotEmpty()) box.addView(line(details.joinToString(" • ")))
        box.addView(line("Voter ID: ${v.epic.ifBlank { "not available" }}", 15f, bold = true).apply {
            setTextIsSelectable(true)
        })
        box.addView(line("Ward ${v.ward} • S.No ${v.sno} • ${v.area}", 13f, 0xFF777777.toInt()))

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(runSearch)
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val MAX_RESULTS = 200
    }
}
