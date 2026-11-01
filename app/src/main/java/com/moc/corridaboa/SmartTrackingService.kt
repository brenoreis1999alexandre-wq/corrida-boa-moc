package com.moc.corridaboa

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.util.concurrent.Executors

/** Optional local GPS observations for RotaLume's intelligent mode. */
class SmartTrackingService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var history: SmartDecisionHistory
    private val geocoderExecutor = Executors.newSingleThreadExecutor()
    private var activeTripStartedAt = 0L
    private var activeTripDestination = ""
    private var lastSampleAt = 0L
    private var lastArea = ""
    private var lastLocation: Location? = null
    private var currentStayStartedAt = 0L
    private var currentStaySaved = false
    private var lastRecordedStayStartedAt = 0L
    private var sampledStays = 0
    private var matchingDestinationStays = 0
    private var sumObservedReturnMinutes = 0.0
    private var firstDestinationStayAt = 0L
    private var firstDestinationStayMinutes = 0.0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
                handleLocation(
                    location,
                    prefs.getString(Prefs.SMART_DESTINATION, "").orEmpty(),
                    prefs.getLong(Prefs.SMART_ACCEPTED_AT, 0L)
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        history = SmartDecisionHistory(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLocationUpdates()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ACCEPTED) {
            activeTripStartedAt = intent.getLongExtra(EXTRA_ACCEPTED_AT, System.currentTimeMillis())
            activeTripDestination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
            lastRecordedStayStartedAt = 0L
            getSharedPreferences(Prefs.FILE, MODE_PRIVATE).edit()
                .putLong(Prefs.SMART_ACTIVE_TRIP_STARTED, activeTripStartedAt)
                .putString(Prefs.SMART_ACTIVE_TRIP_DESTINATION, activeTripDestination).apply()
            resetTripSamples()
        } else {
            val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
            activeTripStartedAt = prefs.getLong(Prefs.SMART_ACTIVE_TRIP_STARTED, 0L)
            activeTripDestination = prefs.getString(Prefs.SMART_ACTIVE_TRIP_DESTINATION, "").orEmpty()
        }
        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        requestLocationUpdates()
        return START_STICKY
    }

    private fun resetTripSamples() {
        sampledStays = 0
        matchingDestinationStays = 0
        sumObservedReturnMinutes = 0.0
        firstDestinationStayAt = 0L
        firstDestinationStayMinutes = 0.0
        lastRecordedStayStartedAt = 0L
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationUpdates() {
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .setMinUpdateDistanceMeters(60f)
            .build()
        try { fused.requestLocationUpdates(request, callback, Looper.getMainLooper()) } catch (_: SecurityException) { }
    }

    private fun handleLocation(location: Location, expectedDestination: String, acceptedAt: Long) {
        val now = System.currentTimeMillis()
        val previous = lastLocation
        if (previous != null && lastArea.isNotBlank()) {
            val distance = previous.distanceTo(location)
            val speed = if (location.hasSpeed()) location.speed else distance / ((now - lastSampleAt).coerceAtLeast(1L) / 1000.0).toFloat()
            if (speed < 0.8f && distance < 80f) {
                if (currentStayStartedAt == 0L) currentStayStartedAt = now
                if (!currentStaySaved && now - currentStayStartedAt >= MIN_STOP_TO_RECORD_MS) {
                    recordStay(currentStayStartedAt, now, lastArea, expectedDestination, acceptedAt)
                    currentStaySaved = true
                }
            } else {
                if (currentStayStartedAt > 0L && !currentStaySaved && now - currentStayStartedAt >= MIN_STOP_TO_RECORD_MS) {
                    recordStay(currentStayStartedAt, now, lastArea, expectedDestination, acceptedAt)
                }
                currentStayStartedAt = 0L
                currentStaySaved = false
            }
        }
        lastLocation = location
        lastSampleAt = now
        reverseGeocode(location)
    }

    private fun recordStay(startedAt: Long, endedAt: Long, area: String, destination: String, acceptedAt: Long) {
        if (startedAt <= lastRecordedStayStartedAt) return
        lastRecordedStayStartedAt = startedAt
        val elapsed = (startedAt - acceptedAt).coerceAtLeast(0L)
        history.saveObservedStay(startedAt, endedAt, area, null, null, destination)
        sampledStays++
        if (acceptedAt > 0L && elapsed <= RETURN_WINDOW_MS && isDestinationArea(area, destination)) {
            matchingDestinationStays++
            val minutes = elapsed / 60_000.0
            sumObservedReturnMinutes += minutes
            if (firstDestinationStayAt == 0L) {
                firstDestinationStayAt = startedAt
                firstDestinationStayMinutes = minutes
            }
        }
        if (sampledStays >= MIN_STAY_SAMPLES && firstDestinationStayAt > 0L && acceptedAt > 0L) {
            val hour = java.util.Calendar.getInstance().apply { timeInMillis = acceptedAt }
                .get(java.util.Calendar.HOUR_OF_DAY)
            history.saveObservedReturn(
                acceptedAt, destination, firstDestinationStayAt,
                if (matchingDestinationStays > 0) sumObservedReturnMinutes / matchingDestinationStays else firstDestinationStayMinutes,
                matchingDestinationStays, sampledStays, hour
            )
            getSharedPreferences(Prefs.FILE, MODE_PRIVATE).edit()
                .remove(Prefs.SMART_ACTIVE_TRIP_STARTED)
                .remove(Prefs.SMART_ACTIVE_TRIP_DESTINATION).apply()
            activeTripStartedAt = 0L
            resetTripSamples()
        } else if (sampledStays >= MIN_STAY_SAMPLES && acceptedAt > 0L && System.currentTimeMillis() - acceptedAt > RETURN_WINDOW_MS) {
            getSharedPreferences(Prefs.FILE, MODE_PRIVATE).edit()
                .remove(Prefs.SMART_ACTIVE_TRIP_STARTED)
                .remove(Prefs.SMART_ACTIVE_TRIP_DESTINATION).apply()
            activeTripStartedAt = 0L
            resetTripSamples()
        }
    }

    private fun reverseGeocode(location: Location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Geocoder(this, Locale("pt", "BR")).getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                updateArea(addresses.firstOrNull()?.subLocality ?: addresses.firstOrNull()?.locality.orEmpty())
            }
        } else {
            geocoderExecutor.execute {
                runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(this, Locale("pt", "BR")).getFromLocation(location.latitude, location.longitude, 1)
                }.getOrNull()?.firstOrNull()?.let { address -> updateArea(address.subLocality ?: address.locality.orEmpty()) }
            }
        }
    }

    private fun updateArea(rawArea: String) {
        val area = normalizeArea(rawArea)
        if (area.isBlank() || area == lastArea) return
        lastArea = area
        currentStayStartedAt = 0L
        currentStaySaved = false
    }

    private fun isDestinationArea(area: String, destination: String): Boolean {
        val a = normalize(area)
        val d = normalize(destination)
        return a.isNotBlank() && d.isNotBlank() && d != normalize("Destino não identificado") &&
            (a.contains(d) || d.contains(a) || d.split(" ").any { it.length >= 5 && a.contains(it) })
    }

    private fun normalizeArea(text: String): String {
        val known = listOf("Major Prates", "Ibituruna", "São José", "Unimontes", "Delfino Magalhães", "Canelas", "Jardim São Luiz", "Centro")
        return known.firstOrNull { text.contains(it, ignoreCase = true) } ?: text.trim().takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun normalize(text: String) = text.lowercase(Locale("pt", "BR")).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Modo Inteligente RotaLume", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("RotaLume • Modo Inteligente ativo")
            .setContentText("Observando localização e paradas para seu histórico local")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun stopLocationUpdates() { if (::fused.isInitialized) fused.removeLocationUpdates(callback) }

    override fun onDestroy() {
        stopLocationUpdates()
        geocoderExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.moc.corridaboa.SMART_START"
        const val ACTION_ACCEPTED = "com.moc.corridaboa.SMART_ACCEPTED"
        const val ACTION_STOP = "com.moc.corridaboa.SMART_STOP"
        const val EXTRA_ACCEPTED_AT = "accepted_at"
        const val EXTRA_DESTINATION = "destination"
        private const val CHANNEL_ID = "rotalume_smart_tracking"
        private const val NOTIFICATION_ID = 4172
        private const val MIN_STOP_TO_RECORD_MS = 3 * 60_000L
        private const val MIN_STAY_SAMPLES = 3
        private const val RETURN_WINDOW_MS = 4 * 60 * 60_000L
    }
}
