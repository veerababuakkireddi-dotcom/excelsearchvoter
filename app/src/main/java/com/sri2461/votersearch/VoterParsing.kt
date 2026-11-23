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

/** One voter that belongs to a possible duplicate group. */
data class DupRow(
    val group: Int,
    val count: Int,
    val voter: Voter,
    val matchReason: String = "Similar name"
)

/**
 * Canonical key for common Indian-English transliteration differences.
 * Spaces and punctuation are removed so joined/split names compare equally.
 */
fun normalizeNameForMatch(value: String): String {
    var key = value.lowercase().filter { it.isLetterOrDigit() }
    key = key.replace("shree", "sri").replace("shri", "sri").replace("sree", "sri")
    key = key.replace("ksh", "x").replace("sh", "s")
    key = key.replace("ph", "f").replace("th", "t").replace("dh", "d")
        .replace("bh", "b").replace("kh", "k").replace("gh", "g")
        .replace("w", "v").replace("z", "j")
    key = key.replace("aa", "a").replace("ee", "i").replace("ii", "i")
        .replace("oo", "u").replace("uu", "u")
    key = key.replace(Regex("([bcdfghjklmnpqrstvwxyz])\\1+"), "$1")
    return key
}

private fun voterNameKey(v: Voter): String =
    normalizeNameForMatch(v.nameEn.ifBlank { v.name })

private fun relationNameKey(v: Voter): String =
    normalizeNameForMatch(v.relNameEn.ifBlank { v.relName })

private fun compactHouse(value: String): String =
    value.uppercase().filter { it.isLetterOrDigit() }

private fun compactAge(value: String): String =
    value.filter { it.isDigit() }

private fun editSimilarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (kotlin.math.abs(a.length - b.length) > maxOf(a.length, b.length) / 2) return 0.0
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in a.indices) {
        current[0] = i + 1
        for (j in b.indices) {
            val cost = if (a[i] == b[j]) 0 else 1
            current[j + 1] = minOf(
                current[j] + 1,
                previous[j + 1] + 1,
                previous[j] + cost
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
}

/** Compare both full transliteration and consonant sound pattern. */
private fun phoneticSimilarity(a: String, b: String): Double {
    if (a.isBlank() || b.isBlank()) return 0.0
    val ca = normalizeNameForMatch(a)
    val cb = normalizeNameForMatch(b)
    val consonantsA = ca.filterNot { it in "aeiou" }
    val consonantsB = cb.filterNot { it in "aeiou" }
    return maxOf(editSimilarity(ca, cb), editSimilarity(consonantsA, consonantsB))
}

private data class PairMatch(val left: Int, val right: Int, val reason: String, val strength: Int)

/**
 * Find duplicate candidates using several independent clues instead of exact name keys only.
 * A fuzzy name match is accepted when supported by the same age+house or a similar
 * father/husband name. Strongly similar names can also match without those fields.
 * Results are review suggestions, not automatic voter-record merges.
 */
fun findDuplicates(voters: List<Voter>): List<DupRow> {
    val usable = voters.indices.filter { voterNameKey(voters[it]).isNotBlank() }
    if (usable.size < 2) return emptyList()

    // Candidate blocking avoids comparing every voter with every other voter.
    val buckets = HashMap<String, MutableList<Int>>()
    fun addBucket(key: String, index: Int) {
        if (key.isNotBlank()) buckets.getOrPut(key) { ArrayList() }.add(index)
    }
    for (i in usable) {
        val v = voters[i]
        val name = voterNameKey(v)
        val relation = relationNameKey(v)
        val consonantBlock = name.filterNot { it in "aeiou" }.take(5)
        val house = compactHouse(v.house)
        val age = compactAge(v.age)
        val relationBlock = relation.filterNot { it in "aeiou" }.take(5)
        addBucket("N:$name", i)
        addBucket("P:$consonantBlock", i)
        if (house.isNotBlank() && age.isNotBlank()) addBucket("H:$house:$age", i)
        if (relation.isNotBlank()) {
            addBucket("R:$relation", i)
            addBucket("RP:$relationBlock", i)
        }
    }

    val candidatePairs = HashSet<Long>()
    for (members in buckets.values) {
        if (members.size < 2) continue
        for (a in 0 until members.size) for (b in a + 1 until members.size) {
            val x = minOf(members[a], members[b])
            val y = maxOf(members[a], members[b])
            candidatePairs.add((x.toLong() shl 32) or (y.toLong() and 0xffffffffL))
        }
    }

    val matches = ArrayList<PairMatch>()
    for (pair in candidatePairs) {
        val i = (pair ushr 32).toInt()
        val j = pair.toInt()
        val a = voters[i]
        val b = voters[j]
        val nameA = voterNameKey(a)
        val nameB = voterNameKey(b)
        val nameScore = phoneticSimilarity(nameA, nameB)
        val sameHouse = compactHouse(a.house).isNotBlank() &&
            compactHouse(a.house) == compactHouse(b.house)
        val sameAge = compactAge(a.age).isNotBlank() && compactAge(a.age) == compactAge(b.age)
        val relationScore = phoneticSimilarity(relationNameKey(a), relationNameKey(b))
        val genderA = a.gender.trim().lowercase()
        val genderB = b.gender.trim().lowercase()
        val genderConflict = genderA.isNotBlank() && genderB.isNotBlank() && genderA != genderB

        val exactName = nameA == nameB
        val sameHouseAge = sameHouse && sameAge
        val nameStrong = nameScore >= 0.88
        val nameWithHouse = nameScore >= 0.70 && sameHouseAge
        val nameWithRelation = nameScore >= 0.68 && relationScore >= 0.78
        val nameWithAllClues = nameScore >= 0.62 && sameHouseAge && relationScore >= 0.68

        if (genderConflict && !exactName) continue
        val reason: String
        val strength: Int
        when {
            exactName -> { reason = "Same phonetic name"; strength = 5 }
            nameWithAllClues -> { reason = "Name + age + house + relative"; strength = 5 }
            nameWithHouse && relationScore >= 0.55 -> { reason = "Name + age + house"; strength = 4 }
            nameWithRelation -> { reason = "Name + father/husband name"; strength = 4 }
            nameWithHouse -> { reason = "Name + age + house"; strength = 3 }
            nameStrong -> { reason = "Very similar name"; strength = 3 }
            else -> continue
        }
        matches.add(PairMatch(i, j, reason, strength))
    }

    if (matches.isEmpty()) return emptyList()

    // Connected components form review groups. Keep the strongest explanation per group.
    val parent = IntArray(voters.size) { it }
    fun root(value: Int): Int {
        var x = value
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }
    fun union(a: Int, b: Int) {
        val ra = root(a)
        val rb = root(b)
        if (ra != rb) parent[rb] = ra
    }
    matches.forEach { union(it.left, it.right) }

    val grouped = matches.groupBy { root(it.left) }
    val output = ArrayList<DupRow>()
    var groupNo = 0
    for ((_, edges) in grouped.entries.sortedBy { entry ->
        entry.value.minOf { minOf(voters[it.left].ward, voters[it.right].ward) } * 100000L +
            entry.value.minOf { minOf(voters[it.left].sno, voters[it.right].sno) }
    }) {
        val memberIds = edges.flatMap { listOf(it.left, it.right) }.distinct()
        if (memberIds.size < 2) continue
        groupNo++
        val count = memberIds.size
        val explanation = edges.maxByOrNull { it.strength }?.reason ?: "Similar name"
        memberIds.map { voters[it] }
            .sortedWith(compareBy({ it.ward }, { it.sno }))
            .forEach { output.add(DupRow(groupNo, count, it, explanation)) }
    }
    return output
}
