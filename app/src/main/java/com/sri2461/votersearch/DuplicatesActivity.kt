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
