package com.arin.seclab

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A tool and how it was last seen. */
data class ToolRecord(val name: String, val version: String, val installedAt: Long, val lastVerified: Long, val state: String)

/** One saved run. */
data class ScanRecord(
    val id: Long,
    val kind: String,
    val target: String,
    val summary: String,
    val output: String,
    val openPorts: List<Int>,
    val createdAt: Long,
)

/** One command typed in the runner. */
data class CommandRecord(val id: Long, val command: String, val output: String, val createdAt: Long, val pinned: Boolean)

/**
 * The Security Lab's tables on the phone: InstalledTool, ScanResult and CommandHistory. Plain SQLite so the
 * app needs no annotation processor.
 */
class SecurityStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "security_lab.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE InstalledTool (name TEXT PRIMARY KEY, version TEXT NOT NULL DEFAULT '', installedAt INTEGER NOT NULL, lastVerified INTEGER NOT NULL, state TEXT NOT NULL)")
        db.execSQL("CREATE TABLE ScanResult (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, target TEXT NOT NULL, summary TEXT NOT NULL, output TEXT NOT NULL, openPorts TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE CommandHistory (id INTEGER PRIMARY KEY AUTOINCREMENT, command TEXT NOT NULL, output TEXT NOT NULL, createdAt INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS InstalledTool")
        db.execSQL("DROP TABLE IF EXISTS ScanResult")
        db.execSQL("DROP TABLE IF EXISTS CommandHistory")
        onCreate(db)
    }

    // ---- tools ----

    fun tools(): Map<String, ToolRecord> = runCatching {
        val out = linkedMapOf<String, ToolRecord>()
        readableDatabase.rawQuery("SELECT name,version,installedAt,lastVerified,state FROM InstalledTool", null).use { c ->
            while (c.moveToNext()) {
                val r = ToolRecord(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4))
                out[r.name] = r
            }
        }
        out
    }.getOrDefault(emptyMap())

    fun setTool(name: String, version: String, state: String) {
        runCatching {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("name", name)
                put("version", version.take(120))
                put("state", state)
                put("lastVerified", now)
                if (state == "installed") put("installedAt", now)
            }
            writableDatabase.insertWithOnConflict("InstalledTool", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            val update = ContentValues().apply {
                put("version", version.take(120))
                put("state", state)
                put("lastVerified", now)
            }
            writableDatabase.update("InstalledTool", update, "name = ?", arrayOf(name))
        }
    }

    fun clearTool(name: String) {
        runCatching { writableDatabase.delete("InstalledTool", "name = ?", arrayOf(name)) }
    }

    // ---- scans ----

    fun addScan(kind: String, target: String, summary: String, output: String, openPorts: List<Int>): Long = runCatching {
        val values = ContentValues().apply {
            put("kind", kind)
            put("target", target)
            put("summary", summary)
            put("output", output.take(200_000))
            put("openPorts", openPorts.joinToString(","))
            put("createdAt", System.currentTimeMillis())
        }
        writableDatabase.insert("ScanResult", null, values)
    }.getOrDefault(-1L)

    fun scans(limit: Int = 200): List<ScanRecord> = runCatching {
        val out = mutableListOf<ScanRecord>()
        readableDatabase.rawQuery("SELECT id,kind,target,summary,output,openPorts,createdAt FROM ScanResult ORDER BY id DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) {
                out += ScanRecord(
                    c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                    c.getString(5).split(',').mapNotNull { it.toIntOrNull() }, c.getLong(6),
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    fun deleteScan(id: Long) {
        runCatching { writableDatabase.delete("ScanResult", "id = ?", arrayOf(id.toString())) }
    }

    fun clearScans() {
        runCatching { writableDatabase.delete("ScanResult", null, null) }
    }

    // ---- command history ----

    fun addCommand(command: String, output: String): Long = runCatching {
        val values = ContentValues().apply {
            put("command", command)
            put("output", output.take(60_000))
            put("createdAt", System.currentTimeMillis())
        }
        writableDatabase.insert("CommandHistory", null, values)
    }.getOrDefault(-1L)

    fun history(limit: Int = 100): List<CommandRecord> = runCatching {
        val out = mutableListOf<CommandRecord>()
        readableDatabase.rawQuery("SELECT id,command,output,createdAt,pinned FROM CommandHistory ORDER BY pinned DESC, id DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) {
                out += CommandRecord(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) == 1)
            }
        }
        out
    }.getOrDefault(emptyList())

    fun setPinned(id: Long, pinned: Boolean) {
        runCatching {
            writableDatabase.update("CommandHistory", ContentValues().apply { put("pinned", if (pinned) 1 else 0) }, "id = ?", arrayOf(id.toString()))
        }
    }

    fun deleteCommand(id: Long) {
        runCatching { writableDatabase.delete("CommandHistory", "id = ?", arrayOf(id.toString())) }
    }

    fun clearHistory() {
        runCatching { writableDatabase.delete("CommandHistory", "pinned = 0", null) }
    }
}
