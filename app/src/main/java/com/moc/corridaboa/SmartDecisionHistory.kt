package com.moc.corridaboa

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar
import java.util.Locale

internal data class DestinationLearning(
    val destination: String,
    val observations: Int,
    val accepted: Int,
    val rejected: Int,
    val acceptancePercent: Int,
    val acceptedOfferAverage: Double,
    val acceptedProfitAverage: Double
)

internal class SmartDecisionHistory(context: Context) : SQLiteOpenHelper(context, "aprendizado_corridas.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE decisions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created_at INTEGER NOT NULL,
            platform TEXT NOT NULL,
            fare REAL NOT NULL,
            pickup TEXT NOT NULL,
            destination TEXT NOT NULL,
            total_km REAL NOT NULL,
            total_minutes REAL NOT NULL,
            estimated_profit REAL NOT NULL,
            accepted INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX idx_decisions_time ON decisions(created_at)")
        db.execSQL("""CREATE TABLE observed_stays (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            started_at INTEGER NOT NULL,
            ended_at INTEGER NOT NULL,
            area TEXT NOT NULL,
            latitude REAL,
            longitude REAL,
            planned_destination TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE observed_returns (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            accepted_at INTEGER NOT NULL,
            destination TEXT NOT NULL,
            first_observed_at INTEGER NOT NULL,
            minutes_until_first_observed REAL NOT NULL,
            matching_stops INTEGER NOT NULL,
            total_stops INTEGER NOT NULL,
            acceptance_hour INTEGER NOT NULL DEFAULT -1
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_returns_destination_time ON observed_returns(destination, accepted_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS observed_stays (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                area TEXT NOT NULL,
                latitude REAL,
                longitude REAL,
                planned_destination TEXT NOT NULL DEFAULT ''
            )""")
        }
        if (oldVersion < 3) createObservedReturns(db)
        if (oldVersion < 4) db.execSQL("ALTER TABLE observed_returns ADD COLUMN acceptance_hour INTEGER NOT NULL DEFAULT -1")
    }

    fun saveDecision(
        timestamp: Long,
        platform: String,
        fare: Double,
        pickup: String,
        destination: String,
        totalKm: Double,
        totalMinutes: Double,
        estimatedProfit: Double,
        accepted: Boolean
    ) {
        val values = ContentValues().apply {
            put("created_at", timestamp)
            put("platform", platform)
            put("fare", fare)
            put("pickup", pickup)
            put("destination", destination)
            put("total_km", totalKm)
            put("total_minutes", totalMinutes)
            put("estimated_profit", estimatedProfit)
            put("accepted", if (accepted) 1 else 0)
        }
        writableDatabase.insert("decisions", null, values)
    }

    private fun createObservedReturns(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS observed_returns (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            accepted_at INTEGER NOT NULL,
            destination TEXT NOT NULL,
            first_observed_at INTEGER NOT NULL,
            minutes_until_first_observed REAL NOT NULL,
            matching_stops INTEGER NOT NULL,
            total_stops INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_returns_destination_time ON observed_returns(destination, accepted_at)")
    }

    fun saveObservedReturn(acceptedAt: Long, destination: String, firstObservedAt: Long, minutesUntilFirstObserved: Double, matchingStops: Int, totalStops: Int, acceptanceHour: Int) {
        val values = ContentValues().apply {
            put("accepted_at", acceptedAt)
            put("destination", destination)
            put("first_observed_at", firstObservedAt)
            put("minutes_until_first_observed", minutesUntilFirstObserved)
            put("matching_stops", matchingStops)
            put("total_stops", totalStops)
            put("acceptance_hour", acceptanceHour)
        }
        writableDatabase.insert("observed_returns", null, values)
    }

    fun observedReturnSummary(destination: String, hour: Int, currentTime: Long): Triple<Int, Double, Double>? {
        val key = normalize(destination)
        if (key.isBlank()) return null
        var sampleCount = 0
        var sumShare = 0.0
        var sumReturnMinutes = 0.0
        readableDatabase.query("observed_returns", arrayOf("accepted_at", "matching_stops", "total_stops", "destination", "acceptance_hour", "minutes_until_first_observed"),
            null, null, null, null, "accepted_at DESC", "300").use { c ->
            while (c.moveToNext()) {
                if (normalize(c.getString(3) ?: "") != key) continue
                val acceptedHour = c.getInt(4)
                if (acceptedHour != hour || currentTime < c.getLong(0) + 4 * 60 * 60_000L) continue
                val total = c.getInt(2)
                if (total <= 0) continue
                sampleCount++
                sumShare += c.getInt(1).toDouble() / total
                sumReturnMinutes += c.getDouble(5)
            }
        }
        if (sampleCount < 3) return null
        return Triple(sampleCount, sumShare * 100.0 / sampleCount, sumReturnMinutes / sampleCount)
    }

    fun saveObservedStay(startedAt: Long, endedAt: Long, area: String, latitude: Double?, longitude: Double?, plannedDestination: String) {
        val values = ContentValues().apply {
            put("started_at", startedAt)
            put("ended_at", endedAt)
            put("area", area)
            if (latitude != null) put("latitude", latitude)
            if (longitude != null) put("longitude", longitude)
            put("planned_destination", plannedDestination)
        }
        writableDatabase.insert("observed_stays", null, values)
    }

    fun observedStaySummary(area: String): Pair<Int, Double>? {
        val normalized = normalize(area)
        if (normalized.isBlank()) return null
        var count = 0
        var minutes = 0.0
        readableDatabase.query(
            "observed_stays", arrayOf("area", "started_at", "ended_at"),
            null, null, null, null, "started_at DESC", "500"
        ).use { c ->
            while (c.moveToNext()) {
                if (normalize(c.getString(0) ?: "") != normalized) continue
                val duration = (c.getLong(2) - c.getLong(1)).coerceAtLeast(0L)
                count++
                minutes += duration / 60_000.0
            }
        }
        if (count == 0) return null
        return count to (minutes / count)
    }

    fun destinationLearning(destination: String, hour: Int): DestinationLearning? {
        val normalized = normalize(destination)
        if (normalized.isBlank()) return null
        var total = 0
        var accepted = 0
        var rejected = 0
        var acceptedFare = 0.0
        var acceptedProfit = 0.0
        readableDatabase.query(
            "decisions", arrayOf("created_at", "fare", "estimated_profit", "accepted", "destination"),
            null, null, null, null, "created_at DESC", "300"
        ).use { c ->
            while (c.moveToNext()) {
                if (normalize(c.getString(4) ?: "") != normalized) continue
                val atHour = Calendar.getInstance().apply { timeInMillis = c.getLong(0) }.get(Calendar.HOUR_OF_DAY)
                if (atHour != hour) continue
                total++
                if (c.getInt(3) == 1) {
                    accepted++
                    acceptedFare += c.getDouble(1)
                    acceptedProfit += c.getDouble(2)
                } else rejected++
            }
        }
        if (total == 0) return null
        val count = accepted + rejected
        val acceptancePercent = if (count == 0) 0 else (accepted * 100.0 / count).toInt()
        val acceptedCount = accepted.coerceAtLeast(1)
        return DestinationLearning(
            destination, total, accepted, rejected, acceptancePercent,
            acceptedFare / acceptedCount, acceptedProfit / acceptedCount
        )
    }

    private fun normalize(text: String): String = text
        .lowercase(Locale("pt", "BR"))
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
