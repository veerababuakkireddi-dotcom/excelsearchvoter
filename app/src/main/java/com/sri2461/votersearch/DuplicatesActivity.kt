package com.sri2461.votersearch

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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

/** Table of voters who share a name. Tap a heading to sort; type under a heading to filter. */
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

    private lateinit var status: TextView
    private lateinit var rowsBox: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { render() }
    private val worker = Executors.newSingleThreadExecutor()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        status.text = "Finding duplicate names…"
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(dp(8) + bars.left, dp(8) + bars.top, dp(8) + bars.right, dp(8) + bars.bottom)
            insets
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this).apply {
            text = "Duplicate names"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Button(this).apply {
            text = "Clear filters"
            setOnClickListener { filterBoxes.forEach { it.setText("") } }
        })
        root.addView(top)

        status = TextView(this).apply { setPadding(0, dp(2), 0, dp(6)) }
        root.addView(status)

        val tableWidth = cols.sumOf { dp(it.widthDp) }
        val table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cols.forEachIndexed { i, c ->
            val header = TextView(this).apply {
                text = c.title
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setBackgroundColor(0xFFE0E0E0.toInt())
                setOnClickListener {
                    if (sortCol == i) ascending = !ascending else { sortCol = i; ascending = true }
                    render()
                }
            }
            headerViews.add(header)
            headerRow.addView(header, LinearLayout.LayoutParams(dp(c.widthDp), -2))

            val box = EditText(this).apply {
                hint = "filter"
                textSize = 12f
                setSingleLine(true)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                doAfterTextChanged {
                    this@DuplicatesActivity.filters[i] = it?.toString().orEmpty().trim()
                    handler.removeCallbacks(refresh)
                    handler.postDelayed(refresh, 250)
                }
            }
            filterBoxes.add(box)
            filterRow.addView(box, LinearLayout.LayoutParams(dp(c.widthDp), -2))
        }
        table.addView(headerRow)
        table.addView(filterRow)

        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val vertical = ScrollView(this)
        vertical.addView(rowsBox)
        table.addView(vertical, LinearLayout.LayoutParams(tableWidth, 0, 1f))

        val horizontal = HorizontalScrollView(this).apply { isFillViewport = true }
        horizontal.addView(table, LinearLayout.LayoutParams(tableWidth, -1))
        root.addView(horizontal, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun render() {
        handler.removeCallbacks(refresh)
        for (i in cols.indices) {
            val arrow = if (i == sortCol) (if (ascending) " ▲" else " ▼") else ""
            headerViews[i].text = cols[i].title + arrow
        }

        val active = filters.withIndex().filter { it.value.isNotEmpty() }
        val filtered = all.filter { row ->
            active.all { (i, f) -> cols[i].value(row).contains(f, ignoreCase = true) }
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
        for (r in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                // Alternate shading by group so each set of same-name voters stands out.
                setBackgroundColor(if (r.group % 2 == 0) 0xFFEAF2FF.toInt() else 0xFFFFFFFF.toInt())
            }
            for (c in cols) {
                line.addView(TextView(this).apply {
                    text = c.value(r)
                    textSize = 13f
                    setTextColor(0xFF222222.toInt())
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }, LinearLayout.LayoutParams(dp(c.widthDp), -2))
            }
            rowsBox.addView(line)
        }

        status.text = when {
            all.isEmpty() -> "No duplicate names found."
            else -> {
                val groups = rows.map { it.group }.distinct().size
                val of = if (rows.size != all.size) " (filtered from ${all.size})" else ""
                "${rows.size} voters in $groups same-name groups$of. Tap a heading to sort."
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        worker.shutdown()
        super.onDestroy()
    }
}
