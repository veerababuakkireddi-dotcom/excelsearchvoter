package com.sri2461.votersearch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SearchResult(val total: Int, val rows: List<Voter>)

class VoterDb(context: Context) : SQLiteOpenHelper(context, "voters.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE voters (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "ward INTEGER NOT NULL, sno INTEGER NOT NULL, " +
                "name TEXT, name_en TEXT, relation TEXT, rel_name TEXT, rel_name_en TEXT, " +
                "age TEXT, gender TEXT, house TEXT, epic TEXT, area TEXT, search TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX voters_ward_idx ON voters(ward, sno)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 was the old PDF/OCR schema; it is replaced entirely.
        db.execSQL("DROP TABLE IF EXISTS voters")
        onCreate(db)
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

    fun replaceAll(voters: List<Voter>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("voters", null, null)
            for (v in voters) {
                val searchText = listOf(
                    v.name, v.nameEn, v.relName, v.relNameEn, v.house, v.epic, v.sno.toString()
                ).joinToString(" ").lowercase()
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
                }
                check(db.insert("voters", null, values) != -1L) { "Could not save row ${v.sno}" }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every word typed must appear somewhere in the voter's searchable text. */
    fun search(query: String, ward: Int?, limit: Int): SearchResult {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        for (t in tokens) {
            where.add("search LIKE ? ESCAPE '\\'")
            val escaped = t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            args.add("%$escaped%")
        }
        if (ward != null) {
            where.add("ward = ?")
            args.add(ward.toString())
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
