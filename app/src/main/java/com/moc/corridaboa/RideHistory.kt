package com.moc.corridaboa

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class RideRecord(
    val createdAt: Long,
    val pickup: String,
    val dropoff: String,
    val fare: Double,
    val pickupKm: Double,
    val tripKm: Double,
    val minutes: Double,
    val fuelCost: Double,
    val net: Double,
    val grossHour: Double,
    val grossKm: Double,
    val grossMinute: Double,
    val netHour: Double,
    val netKm: Double,
    val netMinute: Double,
    val status: Int
)

internal class RideHistory(context: Context) : SQLiteOpenHelper(context, "corrida_boa_history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE offers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created_at INTEGER NOT NULL,
            pickup TEXT NOT NULL,
            dropoff TEXT NOT NULL,
            fare REAL NOT NULL,
            pickup_km REAL NOT NULL,
            trip_km REAL NOT NULL,
            minutes REAL NOT NULL,
            fuel_cost REAL NOT NULL,
            net REAL NOT NULL,
            gross_hour REAL NOT NULL,
            gross_km REAL NOT NULL,
            gross_minute REAL NOT NULL,
            net_hour REAL NOT NULL,
            net_km REAL NOT NULL,
            net_minute REAL NOT NULL,
            status INTEGER NOT NULL
        )""".trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }

    fun save(r: RideRecord) {
        val values = ContentValues().apply {
            put("created_at", r.createdAt); put("pickup", r.pickup); put("dropoff", r.dropoff)
            put("fare", r.fare); put("pickup_km", r.pickupKm); put("trip_km", r.tripKm)
            put("minutes", r.minutes); put("fuel_cost", r.fuelCost); put("net", r.net)
            put("gross_hour", r.grossHour); put("gross_km", r.grossKm); put("gross_minute", r.grossMinute)
            put("net_hour", r.netHour); put("net_km", r.netKm); put("net_minute", r.netMinute)
            put("status", r.status)
        }
        writableDatabase.insert("offers", null, values)
    }

    fun deleteAll() {
        writableDatabase.delete("offers", null, null)
    }

    fun latest(limit: Int = 100): List<RideRecord> {
        val rows = mutableListOf<RideRecord>()
        readableDatabase.query("offers", null, null, null, null, null, "created_at DESC", limit.toString()).use { c ->
            while (c.moveToNext()) {
                rows += RideRecord(
                    c.getLong(c.getColumnIndexOrThrow("created_at")),
                    c.getString(c.getColumnIndexOrThrow("pickup")) ?: "",
                    c.getString(c.getColumnIndexOrThrow("dropoff")) ?: "",
                    c.getDouble(c.getColumnIndexOrThrow("fare")),
                    c.getDouble(c.getColumnIndexOrThrow("pickup_km")),
                    c.getDouble(c.getColumnIndexOrThrow("trip_km")),
                    c.getDouble(c.getColumnIndexOrThrow("minutes")),
                    c.getDouble(c.getColumnIndexOrThrow("fuel_cost")),
                    c.getDouble(c.getColumnIndexOrThrow("net")),
                    c.getDouble(c.getColumnIndexOrThrow("gross_hour")),
                    c.getDouble(c.getColumnIndexOrThrow("gross_km")),
                    c.getDouble(c.getColumnIndexOrThrow("gross_minute")),
                    c.getDouble(c.getColumnIndexOrThrow("net_hour")),
                    c.getDouble(c.getColumnIndexOrThrow("net_km")),
                    c.getDouble(c.getColumnIndexOrThrow("net_minute")),
                    c.getInt(c.getColumnIndexOrThrow("status"))
                )
            }
        }
        return rows
    }
}
