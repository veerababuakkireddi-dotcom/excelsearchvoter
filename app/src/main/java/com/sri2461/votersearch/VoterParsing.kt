package com.sri2461.votersearch

/** One row of the voter list. Pure Kotlin so it can be tested without Android. */
data class Voter(
    val ward: Int,
    val sno: Int,
    val name: String,
    val nameEn: String,
    val relation: String,
    val relName: String,
    val relNameEn: String,
    val age: String,
    val gender: String,
    val house: String,
    val epic: String,
    val area: String
)

/** Minimal RFC-4180 CSV parser (quotes, escaped quotes, newlines inside quotes, BOM). */
fun parseCsv(input: String): List<List<String>> {
    val text = input.removePrefix("\uFEFF")
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (inQuotes) {
            if (c == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') {
                    field.append('"')
                    i++
                } else {
                    inQuotes = false
                }
            } else {
                field.append(c)
            }
        } else {
            when (c) {
                '"' -> inQuotes = true
                ',' -> { row.add(field.toString()); field.setLength(0) }
                '\r' -> { }
                '\n' -> {
                    row.add(field.toString()); field.setLength(0)
                    rows.add(row); row = ArrayList()
                }
                else -> field.append(c)
            }
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
        row.add(field.toString())
        rows.add(row)
    }
    return rows
}

private fun cleanNumber(s: String): String {
    val d = s.trim().toDoubleOrNull() ?: return s.trim()
    return if (d == Math.rint(d)) d.toLong().toString() else s.trim()
}

/** Turns table rows (first row = headers) into voters. Columns are matched by header name. */
fun rowsToVoters(rows: List<List<String>>): List<Voter> {
    require(rows.isNotEmpty()) { "The file is empty" }
    val header = rows[0].map { it.trim().lowercase() }
    fun col(name: String) = header.indexOf(name)

    val iWard = col("ward")
    val iSno = col("s.no")
    val iName = col("name")
    val iNameEn = col("name (english)")
    val iRelation = col("relation")
    val iRel = col("husband/father name")
    val iRelEn = col("husband/father name (english)")
    val iAge = col("age")
    val iGender = col("male/female")
    val iHouse = col("house no")
    val iEpic = col("voter card number")
    val iArea = col("area")
    require(iName >= 0 || iNameEn >= 0) {
        "Could not find the 'Name' / 'Name (English)' columns in the first row"
    }

    val out = ArrayList<Voter>(rows.size)
    for (r in rows.drop(1)) {
        fun get(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
        val name = get(iName)
        val nameEn = get(iNameEn)
        if (name.isEmpty() && nameEn.isEmpty()) continue
        out.add(
            Voter(
                ward = cleanNumber(get(iWard)).toIntOrNull() ?: 0,
                sno = cleanNumber(get(iSno)).toIntOrNull() ?: 0,
                name = name,
                nameEn = nameEn,
                relation = get(iRelation),
                relName = get(iRel),
                relNameEn = get(iRelEn),
                age = cleanNumber(get(iAge)),
                gender = get(iGender),
                house = get(iHouse),
                epic = get(iEpic),
                area = get(iArea)
            )
        )
    }
    return out
}

/** Index of the header row (the one containing "Name (English)" or "Name"), or -1. */
fun findHeaderRow(rows: List<List<String>>): Int =
    rows.indexOfFirst { r ->
        r.any { it.trim().equals("Name (English)", ignoreCase = true) } &&
            r.any { it.trim().equals("Name", ignoreCase = true) }
    }

/** One voter that shares a name with at least one other voter. */
data class DupRow(val group: Int, val count: Int, val voter: Voter)

private fun nameKey(v: Voter): String =
    v.nameEn.ifBlank { v.name }.trim().lowercase().replace(Regex("\\s+"), " ")

/**
 * Groups voters whose names match (English spelling, ignoring case and extra spaces;
 * Telugu name is used if there is no English one). Only groups of 2+ are returned,
 * numbered alphabetically, members ordered by ward and serial number.
 */
fun findDuplicates(voters: List<Voter>): List<DupRow> {
    val groups = voters.filter { nameKey(it).isNotEmpty() }
        .groupBy { nameKey(it) }
        .filter { it.value.size > 1 }
    val out = ArrayList<DupRow>()
    var number = 0
    for (key in groups.keys.sorted()) {
        number++
        val members = groups.getValue(key).sortedWith(compareBy({ it.ward }, { it.sno }))
        members.forEach { out.add(DupRow(number, members.size, it)) }
    }
    return out
}
