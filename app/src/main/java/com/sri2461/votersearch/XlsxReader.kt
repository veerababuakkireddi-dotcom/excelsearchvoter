package com.sri2461.votersearch

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Tiny .xlsx reader (no external library). Reads every worksheet, and returns the
 * largest table whose header row contains "Name" and "Name (English)".
 */
object XlsxReader {

    fun read(bytes: ByteArray): List<List<String>> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val n = entry.name
                if (n == "xl/sharedStrings.xml" ||
                    (n.startsWith("xl/worksheets/") && n.endsWith(".xml"))
                ) {
                    parts[n] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }

        val shared = parts["xl/sharedStrings.xml"]?.let { readSharedStrings(it) } ?: emptyList()

        var best: List<List<String>> = emptyList()
        for ((name, data) in parts) {
            if (!name.startsWith("xl/worksheets/")) continue
            val rows = readSheet(data, shared)
            val h = findHeaderRow(rows)
            if (h >= 0) {
                val table = rows.drop(h)
                if (table.size > best.size) best = table
            }
        }
        check(best.isNotEmpty()) {
            "Could not find a voter table (a sheet with 'Name' and 'Name (English)' columns)"
        }
        return best
    }

    private fun newParser(data: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(data), "UTF-8")
        return parser
    }

    private fun readSharedStrings(data: ByteArray): List<String> {
        val parser = newParser(data)
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inSi = false
        var phoneticDepth = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "si" -> { inSi = true; current.setLength(0) }
                    "rPh" -> phoneticDepth++
                    "t" -> {
                        val text = parser.nextText()
                        if (inSi && phoneticDepth == 0) current.append(text)
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "rPh" -> if (phoneticDepth > 0) phoneticDepth--
                    "si" -> { out.add(current.toString()); inSi = false }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun columnIndex(ref: String?, fallback: Int): Int {
        if (ref == null) return fallback
        var col = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') col = col * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') col = col * 26 + (ch - 'a' + 1)
            else break
        }
        return if (col > 0) col - 1 else fallback
    }

    private fun readSheet(data: ByteArray, shared: List<String>): List<List<String>> {
        val parser = newParser(data)
        val rows = ArrayList<List<String>>()
        var cells = HashMap<Int, String>()
        var nextCol = 0
        var cellCol = 0
        var cellType: String? = null
        var cellValue = StringBuilder()
        var inlineValue = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "row" -> { cells = HashMap(); nextCol = 0 }
                    "c" -> {
                        cellCol = columnIndex(parser.getAttributeValue(null, "r"), nextCol)
                        cellType = parser.getAttributeValue(null, "t")
                        cellValue = StringBuilder()
                        inlineValue = StringBuilder()
                    }
                    "v" -> cellValue.append(parser.nextText())
                    "t" -> inlineValue.append(parser.nextText())
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "c" -> {
                        val raw = cellValue.toString()
                        val value = when (cellType) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "inlineStr" -> inlineValue.toString()
                            "str", "e" -> raw
                            "b" -> raw
                            else -> {
                                val d = raw.toDoubleOrNull()
                                if (d != null && d == Math.rint(d) && Math.abs(d) < 1e15) {
                                    d.toLong().toString()
                                } else raw
                            }
                        }
                        if (value.isNotEmpty()) cells[cellCol] = value
                        nextCol = cellCol + 1
                    }
                    "row" -> {
                        if (cells.isNotEmpty()) {
                            val max = cells.keys.max()
                            rows.add(List(max + 1) { cells[it] ?: "" })
                        } else {
                            rows.add(emptyList())
                        }
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }
}
