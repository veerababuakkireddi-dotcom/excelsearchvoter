package com.sri2461.votersearch

import android.content.Intent
import android.graphics.Typeface
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
    private lateinit var results: LinearLayout
    private lateinit var progress: ProgressBar

    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val runSearch = Runnable { search() }
    private var wardNumbers: List<Int> = emptyList()
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Keep content clear of the status bar, navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(8) + bars.bottom)
            insets
        }

        root.addView(TextView(this).apply {
            text = "Voter Search"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        })

        input = EditText(this).apply {
            hint = "Name (English / Telugu), voter ID, house no."
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    search(); true
                } else false
            }
            doAfterTextChanged {
                handler.removeCallbacks(runSearch)
                handler.postDelayed(runSearch, 300)
            }
        }
        root.addView(input)

        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wardSpinner = Spinner(this)
        filterRow.addView(wardSpinner, LinearLayout.LayoutParams(0, -2, 1f))
        filterRow.addView(Button(this).apply {
            text = "Search"
            setOnClickListener { search() }
        })
        root.addView(filterRow)

        status = TextView(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        root.addView(status)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress)

        val scroll = ScrollView(this)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(Button(this).apply {
            text = "Duplicate names table"
            setOnClickListener { startActivity(Intent(this@MainActivity, DuplicatesActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Import / replace list (Excel or CSV)"
            setOnClickListener {
                picker.launch(
                    arrayOf(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/csv",
                        "text/comma-separated-values",
                        "application/vnd.ms-excel",
                        "application/octet-stream"
                    )
                )
            }
        })
        setContentView(root)
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
        wardSpinner.onItemSelectedListener = null
        wardSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("All wards") + wardNumbers.map { "Ward $it" }
        )
        wardSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = search()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        search()
    }

    // ---------------------------------------------------------------- searching

    private fun selectedWard(): Int? {
        val pos = wardSpinner.selectedItemPosition
        return if (pos <= 0) null else wardNumbers.getOrNull(pos - 1)
    }

    private fun search() {
        handler.removeCallbacks(runSearch)
        results.removeAllViews()
        if (total == 0) return
        val query = input.text.toString().trim()
        val ward = selectedWard()
        if (query.isEmpty() && ward == null) {
            status.text = "$total voters saved on this phone. Type a name, voter ID, house number or serial."
            return
        }
        val result = db.search(query, ward, MAX_RESULTS)
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
        box.addView(line(v.name.ifBlank { v.nameEn }, 18f, bold = true))
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

        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(box)
        wrapper.addView(View(this).apply { setBackgroundColor(0xFFDDDDDD.toInt()) }, LinearLayout.LayoutParams(-1, 1))
        return wrapper
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
