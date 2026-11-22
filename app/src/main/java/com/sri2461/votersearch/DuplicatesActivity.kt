package com.sri2461.votersearch

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import java.util.concurrent.Executors

/** Interactive duplicate-voter review table with global search, collapsible column filters and sorting. */
class DuplicatesActivity : AppCompatActivity() {

    private class Col(
        val title: String,
        val widthDp: Int,
        val numeric: Boolean,
        val value: (DupRow) -> String
    )

    private val cols = listOf(
        Col("Group", 64, true) { it.group.toString() },
        Col("Count", 64, true) { it.count.toString() },
        Col("Name (English)", 170, false) { it.voter.nameEn },
        Col("Father/Husband Name", 190, false) { it.voter.relNameEn.ifBlank { it.voter.relName } },
        Col("Name (Telugu)", 170, false) { it.voter.name },
        Col("Ward", 60, true) { it.voter.ward.toString() },
        Col("S.No", 70, true) { it.voter.sno.toString() },
        Col("Age", 60, true) { it.voter.age },
        Col("Gender", 80, false) { it.voter.gender },
        Col("Relation", 90, false) { it.voter.relation },
        Col("House", 90, false) { it.voter.house },
        Col("Voter ID", 120, false) { it.voter.epic },
        Col("Area", 110, false) { it.voter.area }
    )

    private var all: List<DupRow> = emptyList()
    private val filters = Array(cols.size) { "" }
    private val filterBoxes = ArrayList<EditText>()
    private val headerViews = ArrayList<TextView>()
    private var sortCol = 0
    private var ascending = true
    private var globalQuery = ""

    private lateinit var status: TextView
    private lateinit var rowsBox: LinearLayout
    private lateinit var globalSearch: EditText
    private lateinit var filterPanel: LinearLayout
    private lateinit var filterToggle: Button
    private lateinit var resultSummary: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { render() }
    private val worker = Executors.newSingleThreadExecutor()
    private var filtersExpanded = false

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(bg: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(12).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun styleButton(button: Button, primary: Boolean = false) {
        button.isAllCaps = false
        button.textSize = 13f
        button.setTextColor(if (primary) Color.WHITE else Color.rgb(35, 67, 105))
        button.background = rounded(if (primary) Color.rgb(35, 91, 150) else Color.rgb(235, 243, 251), if (primary) Color.TRANSPARENT else Color.rgb(215, 228, 241))
        button.setPadding(dp(12), dp(8), dp(12), dp(8))
        button.minHeight = dp(40)
    }

    private fun makeLabel(textValue: String, size: Float = 12f, color: Int = Color.rgb(91, 105, 122)): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        status.text = "Loading possible duplicate records…"
        worker.execute {
            try {
                val found = findDuplicates(VoterDb(this).all())
                runOnUiThread { all = found; render() }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Could not load duplicates: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 248, 252))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(dp(12) + bars.left, dp(10) + bars.top, dp(12) + bars.right, dp(8) + bars.bottom)
            insets
        }

        val titleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, Color.rgb(230, 235, 242))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val titleLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleLine.addView(TextView(this).apply {
            text = "Duplicate voter review"
            textSize = 20f
            setTextColor(Color.rgb(28, 45, 67))
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        filterToggle = Button(this).apply {
            text = "☷ Filters"
            styleButton(this)
            setOnClickListener { toggleFilters() }
        }
        titleLine.addView(filterToggle)
        titleCard.addView(titleLine)
        titleCard.addView(makeLabel("Review matching names and verify voter details before treating them as duplicates.").apply {
            setPadding(0, dp(4), 0, dp(10))
        })

        globalSearch = EditText(this).apply {
            hint = "Search name, relative, voter ID, house, ward…"
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(248, 250, 253), Color.rgb(220, 228, 238))
            doAfterTextChanged {
                globalQuery = it?.toString()?.trim().orEmpty()
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, 180)
            }
        }
        titleCard.addView(globalSearch, LinearLayout.LayoutParams(-1, dp(46)))

        val summaryLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        resultSummary = TextView(this).apply {
            text = "Checking records…"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(35, 91, 150))
        }
        summaryLine.addView(resultSummary, LinearLayout.LayoutParams(0, -2, 1f))
        val clearButton = Button(this).apply {
            text = "Clear all"
            styleButton(this)
            setOnClickListener {
                globalSearch.setText("")
                filterBoxes.forEach { it.setText("") }
            }
        }
        summaryLine.addView(clearButton)
        titleCard.addView(summaryLine)
        root.addView(titleCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        status = makeLabel("Finding duplicate names…", 12f).apply {
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }
        root.addView(status)

        val tableWidth = cols.sumOf { dp(it.widthDp) }
        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, Color.rgb(225, 231, 239))
            clipToOutline = true
        }

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(31, 65, 101)) }
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(242, 246, 251)) }
        cols.forEachIndexed { i, c ->
            val header = TextView(this).apply {
                text = c.title
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(7), dp(11), dp(7), dp(11))
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    if (sortCol == i) ascending = !ascending else { sortCol = i; ascending = true }
                    render()
                }
            }
            headerViews.add(header)
            headerRow.addView(header, LinearLayout.LayoutParams(dp(c.widthDp), -2))

            val box = EditText(this).apply {
                hint = "Filter"
                textSize = 12f
                setSingleLine(true)
                setPadding(dp(6), dp(5), dp(6), dp(5))
                background = rounded(Color.WHITE, Color.rgb(220, 228, 238))
                doAfterTextChanged {
                    this@DuplicatesActivity.filters[i] = it?.toString().orEmpty().trim()
                    handler.removeCallbacks(refresh)
                    handler.postDelayed(refresh, 220)
                }
            }
            filterBoxes.add(box)
            filterRow.addView(box, LinearLayout.LayoutParams(dp(c.widthDp), dp(42)).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            })
        }
        table.addView(headerRow)
        filterPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        filterPanel.addView(TextView(this).apply {
            text = "Filter individual columns"
            textSize = 11f
            setTextColor(Color.rgb(95, 111, 130))
            setPadding(dp(8), dp(6), dp(8), 0)
        })
        filterPanel.addView(filterRow)
        table.addView(filterPanel)

        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val vertical = ScrollView(this).apply { isFillViewport = true }
        vertical.addView(rowsBox)
        table.addView(vertical, LinearLayout.LayoutParams(tableWidth, 0, 1f))

        val horizontal = HorizontalScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        horizontal.addView(table, LinearLayout.LayoutParams(tableWidth, -1))
        root.addView(horizontal, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun toggleFilters() {
        filtersExpanded = !filtersExpanded
        filterPanel.visibility = if (filtersExpanded) View.VISIBLE else View.GONE
        filterToggle.text = if (filtersExpanded) "Hide filters" else "☷ Filters"
    }

    private fun render() {
        handler.removeCallbacks(refresh)
        for (i in cols.indices) {
            val arrow = if (i == sortCol) (if (ascending) "  ↑" else "  ↓") else ""
            headerViews[i].text = cols[i].title + arrow
        }

        val active = filters.withIndex().filter { it.value.isNotEmpty() }
        val filtered = all.filter { row ->
            val matchesGlobal = globalQuery.isBlank() || cols.any { it.value(row).contains(globalQuery, ignoreCase = true) }
            matchesGlobal && active.all { (i, f) -> cols[i].value(row).contains(f, ignoreCase = true) }
        }

        val col = cols[sortCol]
        val comparator = Comparator<DupRow> { a, b ->
            val x = col.value(a)
            val y = col.value(b)
            val c = if (col.numeric) compareValues(x.toIntOrNull(), y.toIntOrNull())
            else x.compareTo(y, ignoreCase = true)
            if (c != 0) c else compareValuesBy(a, b, { it.group }, { it.voter.ward }, { it.voter.sno })
        }
        val rows = filtered.sortedWith(if (ascending) comparator else comparator.reversed())

        rowsBox.removeAllViews()
        if (rows.isEmpty()) {
            rowsBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(34), dp(24), dp(34))
                addView(TextView(this@DuplicatesActivity).apply {
                    text = if (all.isEmpty()) "No duplicate names found" else "No matching records"
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.rgb(50, 67, 88))
                    gravity = Gravity.CENTER
                })
                addView(makeLabel(if (all.isEmpty()) "Import voter data to check for repeated names." else "Try another search term or clear the filters.").apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                })
            })
        } else {
            rows.forEachIndexed { index, r ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(if (index % 2 == 0) Color.WHITE else Color.rgb(246, 249, 253))
                }
                for ((columnIndex, c) in cols.withIndex()) {
                    val value = c.value(r)
                    line.addView(TextView(this).apply {
                        text = value.ifBlank { "—" }
                        textSize = 12.5f
                        setTextColor(if (columnIndex == 2) Color.rgb(28, 74, 125) else Color.rgb(47, 59, 74))
                        if (columnIndex == 2) setTypeface(typeface, Typeface.BOLD)
                        setPadding(dp(7), dp(10), dp(7), dp(10))
                        gravity = Gravity.CENTER_VERTICAL
                    }, LinearLayout.LayoutParams(dp(c.widthDp), -2))
                }
                rowsBox.addView(line)
                rowsBox.addView(View(this).apply { setBackgroundColor(Color.rgb(232, 237, 244)) }, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }

        val groups = rows.map { it.group }.distinct().size
        resultSummary.text = "${rows.size} voters  ·  $groups duplicate groups"
        val filterCount = active.size + if (globalQuery.isNotBlank()) 1 else 0
        status.text = if (all.isEmpty()) "No duplicate names found." else {
            val suffix = if (filterCount > 0) "  ·  $filterCount active filter(s)" else ""
            "Tap any column heading to sort. Swipe horizontally to see all voter details.$suffix"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        worker.shutdown()
        super.onDestroy()
    }
}
