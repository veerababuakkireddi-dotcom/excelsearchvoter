package com.sri2461.votersearch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

class SearchResult(val total: Int, val rows: List<Voter>)

class VoterDb(context: Context) : SQLiteOpenHelper(context, "voters.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE voters (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "ward INTEGER NOT NULL, sno INTEGER NOT NULL, " +
                "name TEXT, name_en TEXT, relation TEXT, rel_name TEXT, rel_name_en TEXT, " +
                "age TEXT, gender TEXT, house TEXT, epic TEXT, area TEXT, search TEXT NOT NULL, name_key TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX voters_ward_idx ON voters(ward, sno)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 1 was the old PDF/OCR schema; replace it with the CSV/XLSX schema.
            db.execSQL("DROP TABLE IF EXISTS voters")
            onCreate(db)
            return
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE voters ADD COLUMN name_key TEXT NOT NULL DEFAULT ''")
            // Preserve the currently imported voter list while adding phonetic-name keys.
            val existingNames = ArrayList<Triple<Long, String, String>>()
            db.rawQuery("SELECT id, name, name_en FROM voters", null).use { c ->
                while (c.moveToNext()) {
                    existingNames.add(Triple(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty()))
                }
            }
            for ((id, name, nameEn) in existingNames) {
                val key = listOf(nameEn, name).filter { it.isNotBlank() }
                    .joinToString(" ") { normalizeNameForMatch(it) }
                val values = ContentValues().apply { put("name_key", key) }
                db.update("voters", values, "id = ?", arrayOf(id.toString()))
            }
        }
        if (oldVersion < 4) {
            // Rebuild existing search keys with expanded phonetic normalization.
            val existingNames = ArrayList<Triple<Long, String, String>>()
            db.rawQuery("SELECT id, name, name_en FROM voters", null).use { c ->
                while (c.moveToNext()) {
                    existingNames.add(Triple(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty()))
                }
            }
            for ((id, name, nameEn) in existingNames) {
                val key = listOf(nameEn, name).filter { it.isNotBlank() }
                    .joinToString(" ") { normalizeNameForMatch(it) }
                db.update("voters", ContentValues().apply { put("name_key", key) }, "id = ?", arrayOf(id.toString()))
            }
        }
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM voters", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun all(): List<Voter> = search("", null, Int.MAX_VALUE).rows

    fun wards(): List<Int> {
        val out = ArrayList<Int>()
        readableDatabase.rawQuery("SELECT DISTINCT ward FROM voters ORDER BY ward", null).use {
            while (it.moveToNext()) out.add(it.getInt(0))
        }
        return out
    }

    fun genders(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT DISTINCT gender FROM voters WHERE TRIM(gender) <> '' ORDER BY gender", null).use {
            while (it.moveToNext()) out.add(it.getString(0).orEmpty())
        }
        return out
    }

    fun areas(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT DISTINCT area FROM voters WHERE TRIM(area) <> '' ORDER BY area", null).use {
            while (it.moveToNext()) out.add(it.getString(0).orEmpty())
        }
        return out
    }

    fun replaceAll(voters: List<Voter>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("voters", null, null)
            for (v in voters) {
                val searchText = listOf(
                    v.name, v.nameEn, v.relName, v.relNameEn, v.house, v.epic, v.sno.toString()
                ).joinToString(" ").lowercase(Locale.ROOT)
                val values = ContentValues().apply {
                    put("ward", v.ward)
                    put("sno", v.sno)
                    put("name", v.name)
                    put("name_en", v.nameEn)
                    put("relation", v.relation)
                    put("rel_name", v.relName)
                    put("rel_name_en", v.relNameEn)
                    put("age", v.age)
                    put("gender", v.gender)
                    put("house", v.house)
                    put("epic", v.epic)
                    put("area", v.area)
                    put("search", searchText)
                    put("name_key", listOf(v.nameEn, v.name).filter { it.isNotBlank() }
                        .joinToString(" ") { normalizeNameForMatch(it) })
                }
                check(db.insert("voters", null, values) != -1L) { "Could not save row ${v.sno}" }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every word typed must appear somewhere in the voter's searchable text. */
    fun search(query: String, ward: Int?, limit: Int, initial: String? = null, area: String? = null, gender: String? = null, ageGroup: String? = null): SearchResult {
        // Search both ordinary text (IDs/house numbers/etc.) and canonicalized
        // voter-name keys (spaces and common Roman spelling variants ignored).
        val compactQuery = query.lowercase(Locale.ROOT).filterNot { it.isWhitespace() }
        val nameQuery = normalizeNameForMatch(query)
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (compactQuery.isNotEmpty()) {
            val escaped = compactQuery.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            where.add("REPLACE(LOWER(search), ' ', '') LIKE ? ESCAPE '\\'")
            args.add("%$escaped%")
            if (nameQuery.isNotEmpty()) {
                val nameEscaped = nameQuery.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                where[where.lastIndex] = "(" + where.last() + " OR name_key LIKE ? ESCAPE '\\')"
                args.add("%$nameEscaped%")
            }
        }
        if (ward != null) {
            where.add("ward = ?")
            args.add(ward.toString())
        }
        if (!initial.isNullOrBlank()) {
            where.add("UPPER(name_en) LIKE ?")
            args.add(initial.uppercase(Locale.ROOT) + "%")
        }
        if (!area.isNullOrBlank()) {
            where.add("area = ?")
            args.add(area)
        }
        if (!gender.isNullOrBlank()) {
            where.add("gender = ?")
            args.add(gender)
        }
        when (ageGroup) {
            "18–25" -> where.add("CAST(age AS INTEGER) BETWEEN 18 AND 25")
            "26–40" -> where.add("CAST(age AS INTEGER) BETWEEN 26 AND 40")
            "41–60" -> where.add("CAST(age AS INTEGER) BETWEEN 41 AND 60")
            "60+" -> where.add("CAST(age AS INTEGER) > 60")
        }
        val clause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val argArray = args.toTypedArray()

        val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM voters$clause", argArray).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val rows = ArrayList<Voter>()
        readableDatabase.rawQuery(
            "SELECT ward, sno, name, name_en, relation, rel_name, rel_name_en, " +
                "age, gender, house, epic, area FROM voters$clause ORDER BY ward, sno LIMIT $limit",
            argArray
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Voter(
                        ward = c.getInt(0), sno = c.getInt(1),
                        name = c.getString(2).orEmpty(), nameEn = c.getString(3).orEmpty(),
                        relation = c.getString(4).orEmpty(), relName = c.getString(5).orEmpty(),
                        relNameEn = c.getString(6).orEmpty(), age = c.getString(7).orEmpty(),
                        gender = c.getString(8).orEmpty(), house = c.getString(9).orEmpty(),
                        epic = c.getString(10).orEmpty(), area = c.getString(11).orEmpty()
                    )
                )
            }
        }
        return SearchResult(total, rows)
    }
}
