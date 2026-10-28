package com.boxymoron.boxybatt.util

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.boxymoron.boxybatt.model.BatteryInfo

/**
 * SQLite-backed storage for battery history data.
 * Logs battery snapshots at configurable intervals.
 */
class BatteryHistoryStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "BatteryHistoryStore"
        private const val DB_NAME = "boxybatt_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "battery_log"

        private const val COL_ID = "_id"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_LEVEL = "level"
        private const val COL_TEMPERATURE = "temperature"
        private const val COL_VOLTAGE = "voltage"
        private const val COL_STATUS = "status"
        private const val COL_HEALTH = "health"
        private const val COL_CURRENT = "current_now"
        private const val COL_PLUGGED = "plugged"

        @Volatile
        private var instance: BatteryHistoryStore? = null

        fun getInstance(context: Context): BatteryHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: BatteryHistoryStore(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_LEVEL INTEGER NOT NULL,
                $COL_TEMPERATURE REAL NOT NULL,
                $COL_VOLTAGE REAL NOT NULL,
                $COL_STATUS INTEGER NOT NULL,
                $COL_HEALTH INTEGER NOT NULL,
                $COL_CURRENT INTEGER DEFAULT 0,
                $COL_PLUGGED INTEGER DEFAULT 0
            )
        """)
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE ($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /**
     * Insert a battery snapshot.
     */
    fun logBattery(info: BatteryInfo) {
        try {
            val values = ContentValues().apply {
                put(COL_TIMESTAMP, info.timestamp)
                put(COL_LEVEL, info.percentage)
                put(COL_TEMPERATURE, info.temperature)
                put(COL_VOLTAGE, info.voltage)
                put(COL_STATUS, info.status)
                put(COL_HEALTH, info.health)
                put(COL_CURRENT, info.currentNow)
                put(COL_PLUGGED, info.plugged)
            }
            writableDatabase.insert(TABLE, null, values)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log battery: ${e.message}")
        }
    }

    /**
     * Data class for chart entries.
     */
    data class HistoryEntry(
        val timestamp: Long,
        val level: Int,
        val temperature: Float,
        val voltage: Float,
        val status: Int,
        val plugged: Int,
        val currentNow: Int
    )

    /**
     * Query history entries within a time range.
     */
    fun getHistory(sinceMs: Long): List<HistoryEntry> {
        val entries = mutableListOf<HistoryEntry>()
        try {
            val cursor = readableDatabase.query(
                TABLE,
                null,
                "$COL_TIMESTAMP >= ?",
                arrayOf(sinceMs.toString()),
                null, null,
                "$COL_TIMESTAMP ASC"
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    entries.add(
                        HistoryEntry(
                            timestamp = c.getLong(c.getColumnIndexOrThrow(COL_TIMESTAMP)),
                            level = c.getInt(c.getColumnIndexOrThrow(COL_LEVEL)),
                            temperature = c.getFloat(c.getColumnIndexOrThrow(COL_TEMPERATURE)),
                            voltage = c.getFloat(c.getColumnIndexOrThrow(COL_VOLTAGE)),
                            status = c.getInt(c.getColumnIndexOrThrow(COL_STATUS)),
                            plugged = c.getInt(c.getColumnIndexOrThrow(COL_PLUGGED)),
                            currentNow = c.getInt(c.getColumnIndexOrThrow(COL_CURRENT))
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read history: ${e.message}")
        }
        return entries
    }

    /**
     * Get total record count.
     */
    fun getRecordCount(): Int {
        return try {
            val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
            cursor.use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (_: Exception) { 0 }
    }

    /**
     * Delete old entries beyond retention period (default: 30 days).
     */
    fun pruneOldEntries(retentionMs: Long = 30L * 24 * 60 * 60 * 1000) {
        try {
            val cutoff = System.currentTimeMillis() - retentionMs
            writableDatabase.delete(TABLE, "$COL_TIMESTAMP < ?", arrayOf(cutoff.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prune: ${e.message}")
        }
    }
}
