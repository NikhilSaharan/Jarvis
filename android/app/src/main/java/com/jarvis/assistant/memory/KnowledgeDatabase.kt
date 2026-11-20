package com.jarvis.assistant.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Entity(
    val name: String,
    val relationship: String,
    val phone: String,
    val notes: String
)

class KnowledgeDatabase(context: Context) : SQLiteOpenHelper(context, "jarvis_memory.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS entities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT UNIQUE COLLATE NOCASE,
                relationship TEXT,
                phone TEXT,
                notes TEXT,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS life_facts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fact_key TEXT,
                fact_value TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS quarantined_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_app TEXT,
                sender TEXT,
                message_content TEXT,
                received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS entities")
        db.execSQL("DROP TABLE IF EXISTS life_facts")
        db.execSQL("DROP TABLE IF EXISTS quarantined_messages")
        onCreate(db)
    }

    fun saveEntity(name: String, relationship: String, phone: String, notes: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("relationship", relationship)
            put("phone", phone)
            put("notes", notes)
        }
        db.insertWithOnConflict("entities", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getEntity(name: String): Entity? {
        val db = readableDatabase
        val cursor = db.query(
            "entities",
            arrayOf("name", "relationship", "phone", "notes"),
            "name = ? COLLATE NOCASE",
            arrayOf(name),
            null, null, null
        )

        cursor.use {
            if (it.moveToFirst()) {
                return Entity(
                    name = it.getString(0),
                    relationship = it.getString(1) ?: "",
                    phone = it.getString(2) ?: "",
                    notes = it.getString(3) ?: ""
                )
            }
        }
        return null
    }

    fun isVip(senderName: String): Boolean {
        val lower = senderName.lowercase()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT name, relationship FROM entities", null)
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0).lowercase()
                val rel = it.getString(1).lowercase()
                if (lower.contains(name) || name.contains(lower)) return true
                if (rel in listOf("mummy", "mother", "papa", "father", "family", "vip", "urgent")) return true
            }
        }
        return false
    }
}
