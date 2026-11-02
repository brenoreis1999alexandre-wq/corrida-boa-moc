package com.moc.corridaboa

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.Intent
import android.location.Location
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.io.File

class MainActivity : Activity() {
    private lateinit var pageHost: FrameLayout
    private lateinit var gas: EditText
    private lateinit var consumo: EditText
    private lateinit var targetKm: EditText
    private lateinit var targetHour: EditText
    private lateinit var targetMinute: EditText
    private lateinit var floorKm: EditText
    private lateinit var floorHour: EditText
    private lateinit var floorMinute: EditText
    private var accessStatusLabel: TextView? = null
    private var overlayStatusLabel: TextView? = null
    private var selectedTab = 0
    private var historyFilter = 1 // current month
    private var historyVisibleCount = 20
    private var locationPermissionRequested = false
    private var pendingLocationPermissionToggle = false
    private var mapView: MapView? = null
    private var mapLocationCallback: LocationCallback? = null
    private var mapCurrentLocation: Location? = null
    private var mapSummaryLabel: TextView? = null
    private val prefs by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }

    private val bg = Color.rgb(13, 19, 36)
    private val surface = Color.rgb(27, 37, 62)
    private val surface2 = Color.rgb(37, 49, 79)
    private val accent = Color.rgb(71, 226, 188)
    private val lime = Color.rgb(67, 222, 145)
    private val pale = Color.rgb(234, 241, 255)
    private val secondary = Color.rgb(158, 174, 209)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        pageHost = FrameLayout(this)
        root.addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomNavigation(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
        setContentView(root)
        renderTab()
    }

    override fun onPause() {
        stopMapLocationUpdates()
        mapView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopMapLocationUpdates()
        mapView?.onDetach()
        mapView = null
        mapSummaryLabel = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        if (!::pageHost.isInitialized) return
        if (selectedTab == 0) renderTab()
        if (selectedTab == 1 && mapView != null) { refreshMapLocation(); startMapLocationUpdates() }
        if (prefs.getBoolean(Prefs.INTELLIGENT_MODE, false) || pendingLocationPermissionToggle || prefs.getBoolean(Prefs.SMART_ENABLE_PENDING, false)) {
            if (hasForegroundLocationPermission() && hasBackgroundLocationPermission()) {
                val wasPending = pendingLocationPermissionToggle || prefs.getBoolean(Prefs.SMART_ENABLE_PENDING, false)
                if (wasPending) prefs.edit().putBoolean(Prefs.INTELLIGENT_MODE, true).putBoolean(Prefs.SMART_ENABLE_PENDING, false).apply()
                pendingLocationPermissionToggle = false
                startSmartTracking()
                if (wasPending) renderTab()
            } else if (pendingLocationPermissionToggle || prefs.getBoolean(Prefs.SMART_ENABLE_PENDING, false)) {
                locationPermissionRequested = false
                ensureLocationPermissions()
            }
        }
        accessStatusLabel?.text = "Acessibilidade: ${if (isServiceEnabled()) "ativada" else "pendente"}"
        overlayStatusLabel?.text = "Janela flutuante: ${if (Settings.canDrawOverlays(this)) "permitida" else "pendente"}"
    }

    private fun renderTab() {
        if (!::pageHost.isInitialized) return
        pageHost.removeAllViews()
        accessStatusLabel = null
        overlayStatusLabel = null
        stopMapLocationUpdates()
        mapView?.onPause()
        mapView?.onDetach()
        mapView = null
        val page = when (selectedTab) {
            1 -> mapPage()
            2 -> historyPage()
            3 -> calculatorPage()
            4 -> settingsPage()
            else -> dashboardPage()
        }
        pageHost.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun bottomNavigation(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(7), dp(5), dp(7), dp(6))
        setBackgroundColor(Color.rgb(20, 29, 49))
        val items = listOf("⌂" to "Painel", "⌖" to "Mapa", "▤" to "Histórico", "▣" to "Calculadora", "⚙" to "Ajustes")
        items.forEachIndexed { index, item ->
            val navItem = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                val active = selectedTab == index
                val tint = if (active) accent else secondary
                addView(TextView(this@MainActivity).apply {
                    text = item.first
                    textSize = 20f
                    setTextColor(tint)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = item.second
                    textSize = 11f
                    setTextColor(tint)
                    gravity = Gravity.CENTER
                })
                setOnClickListener {
                    selectedTab = index
                    renderTab()
                }
            }
            addView(navItem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private data class MapArea(val name: String, val lat: Double, val lon: Double)

    private val mapAreas = listOf(
        MapArea("Independência", -16.71820, -43.81464), MapArea("Delfino Magalhães", -16.73610, -43.83805),
        MapArea("Jaraguá", -16.69672, -43.82233), MapArea("Planalto", -16.69867, -43.83668),
        MapArea("São José", -16.72114, -43.85765), MapArea("Major Prates", -16.74795, -43.87837),
        MapArea("Centro", -16.72299, -43.86535), MapArea("Ibituruna", -16.72599, -43.88586),
        MapArea("Canelas", -16.74407, -43.86689), MapArea("Unimontes", -16.72044, -43.87862),
        MapArea("Renascença", -16.69660, -43.85335), MapArea("Maracanã", -16.76130, -43.86614),
        MapArea("Morrinhos", -16.73293, -43.86457), MapArea("Santos Reis", -16.70025, -43.86994),
        MapArea("Nova Esperança", -16.54026, -43.96151), MapArea("Jardim São Luiz", -16.73055, -43.87812),
        MapArea("Vila Guilhermina", -16.73663, -43.86736), MapArea("Edgar Pereira", -16.70690, -43.86314),
        MapArea("Alcides Rabelo", -16.70729, -43.84316), MapArea("São Judas Tadeu", -16.74890, -43.86125),
        MapArea("Jardim São Geraldo", -16.75708, -43.87538), MapArea("Jardim Olímpico", -16.74604, -43.82906),
        MapArea("Vila Oliveira", -16.71374, -43.88524), MapArea("Todos os Santos", -16.71750, -43.87333),
        MapArea("Esplanada", -16.71310, -43.84046), MapArea("Monte Alegre", -16.72944, -43.84737),
        MapArea("Alice Maia", -16.70720, -43.85362), MapArea("Vera Cruz", -16.71368, -43.84582),
        MapArea("Santa Rita", -16.73244, -43.85874),
        MapArea("Rodoviária", -16.74278, -43.86830), MapArea("Hosp. Aroldo Tourinho", -16.70994, -43.86569),
        MapArea("Hosp. Dilson Godinho", -16.71743, -43.85835), MapArea("Santa Casa", -16.72536, -43.87026),
        MapArea("Montes Claros Shopping", -16.74216, -43.87051)
    )

    private fun mapPage(): View {
        val osmdroidCache = File(filesDir, "osmdroid").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = "RotaLume Android/1.9.3"
            setOsmdroidBasePath(osmdroidCache)
            setOsmdroidTileCache(File(osmdroidCache, "tiles").apply { mkdirs() })
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(bg) }
        root.addView(label("Mapa • Montes Claros", 22, pale, true))
        root.addView(label("Círculos aproximados por bairro. Cores = perfil local informado, não demanda nem trânsito ao vivo.", 12, secondary))
        val legend = label("🟢 faixa forte   🟡 média   🔴 fraca   🔵 sua localização", 12, pale, true)
        root.addView(legend)
        mapSummaryLabel = label("Buscando localização…", 13, pale, true)
        root.addView(mapSummaryLabel)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val locate = actionButton("Minha localização") { requestMapLocation() }.apply { textSize = 12f }
        val openGoogle = actionButton("Google Maps") { openExternalMap("google") }.apply { textSize = 12f }
        val openWaze = actionButton("Waze") { openExternalMap("waze") }.apply { textSize = 12f }
        controls.addView(locate, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        controls.addView(openGoogle, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        controls.addView(openWaze, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(controls)
        val map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.8)
            controller.setCenter(GeoPoint(-16.722, -43.865))
            setMinZoomLevel(10.0)
            setMaxZoomLevel(19.0)
        }
        mapView = map
        root.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = label("Mapa © OpenStreetMap contributors • perfil local informativo; sem promessa de retorno.", 10, secondary)
        root.addView(footer)
        drawLocalHeatProfile()
        if (!hasForegroundLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_MAP_LOCATION)
        } else {
            refreshMapLocation()
            startMapLocationUpdates()
        }
        return root
    }

    private fun drawLocalHeatProfile() {
        val map = mapView ?: return
        map.overlays.clear()
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY); val minute = now.get(Calendar.MINUTE); val weekday = now.get(Calendar.DAY_OF_WEEK)
        val holiday = LocalContextAdvisor.holidayName(now) != null
        for (area in mapAreas) {
            val level = mapProfile(area.name, hour, minute, weekday, holiday)
            val color = when(level) {
                LocalContextAdvisor.Level.HIGH -> Color.rgb(42, 205, 118)
                LocalContextAdvisor.Level.MEDIUM -> Color.rgb(245, 193, 66)
                LocalContextAdvisor.Level.LOW -> Color.rgb(235, 82, 87)
                LocalContextAdvisor.Level.UNKNOWN -> Color.GRAY
            }
            val center = GeoPoint(area.lat, area.lon)
            val circle = Polygon(map).apply {
                points = Polygon.pointsAsCircle(center, 420.0)
                setFillColor(android.graphics.Color.argb(55, Color.red(color), Color.green(color), Color.blue(color)))
                setStrokeColor(android.graphics.Color.argb(170, Color.red(color), Color.green(color), Color.blue(color)))
                setStrokeWidth(dp(2).toFloat())
            }
            map.overlays.add(circle)
            val marker = Marker(map).apply {
                position = center
                title = "${profileEmoji(level)} ${area.name} • ${profileText(level)}"
                snippet = "Referência local informada pelo motorista; não é dado de demanda ao vivo."
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE); setSize(dp(18), dp(18)) }
            }
            map.overlays.add(marker)
        }
        mapCurrentLocation?.let { location ->
            val marker = Marker(map).apply {
                position = GeoPoint(location.latitude, location.longitude); title = "Sua localização GPS"
                snippet = "Precisão aproximada: ${location.accuracy.toInt()} m"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.rgb(45, 145, 255)); setStroke(dp(3), Color.WHITE); setSize(dp(23), dp(23)) }
            }
            map.overlays.add(marker)
            val nearest = nearestStrongArea(location, hour, minute, weekday, holiday)
            if (nearest != null) {
                val kmAway = String.format(Locale("pt","BR"), "%.1f", nearest.second / 1000)
                marker.snippet = "Faixa forte mais próxima: ${nearest.first.name}, $kmAway km em linha reta. Não é previsão de corrida."
                mapSummaryLabel?.text = "GPS atual • faixa forte próxima no perfil: ${nearest.first.name} ($kmAway km em linha reta)"
            } else {
                mapSummaryLabel?.text = "GPS atual • sem faixa forte no perfil informado neste horário."
            }
            map.controller.animateTo(GeoPoint(location.latitude, location.longitude))
        } ?: run {
            mapSummaryLabel?.text = "GPS indisponível • toque em Minha localização."
        }
        map.invalidate()
    }

    private fun mapProfile(name: String, hour: Int, minute: Int, weekday: Int, holiday: Boolean): LocalContextAdvisor.Level {
        val n = name.lowercase(Locale.ROOT)
        val t = hour * 60 + minute
        if (n == "centro") return when {
            holiday || weekday == Calendar.SUNDAY -> LocalContextAdvisor.Level.LOW
            t < 630 -> LocalContextAdvisor.Level.LOW
            t < 1080 -> LocalContextAdvisor.Level.HIGH
            t < 1260 -> LocalContextAdvisor.Level.MEDIUM
            else -> LocalContextAdvisor.Level.LOW
        }
        if (n == "independência" && (t < 300 || t >= 540)) {
            return if (t >= 1020 && t < 1140) LocalContextAdvisor.Level.MEDIUM else LocalContextAdvisor.Level.LOW
        }
        if (n == "major prates" && (t < 360 || t >= 540 && t < 1020 || t >= 1200)) return LocalContextAdvisor.Level.LOW
        if ((n == "unimontes") && (holiday || weekday == Calendar.SATURDAY || weekday == Calendar.SUNDAY)) return LocalContextAdvisor.Level.LOW
        return LocalContextAdvisor.level(name, hour, minute, weekday, holiday)
    }

    private fun profileEmoji(level: LocalContextAdvisor.Level) = when(level) {
        LocalContextAdvisor.Level.HIGH -> "🟢"; LocalContextAdvisor.Level.MEDIUM -> "🟡"; LocalContextAdvisor.Level.LOW -> "🔴"; else -> "⚪"
    }
    private fun profileText(level: LocalContextAdvisor.Level) = when(level) {
        LocalContextAdvisor.Level.HIGH -> "faixa forte"; LocalContextAdvisor.Level.MEDIUM -> "faixa média"; LocalContextAdvisor.Level.LOW -> "faixa fraca"; else -> "sem perfil"
    }

    private fun nearestStrongArea(location: Location, hour: Int, minute: Int, weekday: Int, holiday: Boolean): Pair<MapArea, Float>? = mapAreas.mapNotNull { area ->
        if (mapProfile(area.name, hour, minute, weekday, holiday) != LocalContextAdvisor.Level.HIGH) return@mapNotNull null
        val out = FloatArray(1); Location.distanceBetween(location.latitude, location.longitude, area.lat, area.lon, out)
        area to out[0]
    }.minByOrNull { it.second }

    private fun requestMapLocation() {
        if (!hasForegroundLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_MAP_LOCATION)
            return
        }
        refreshMapLocation(); startMapLocationUpdates()
    }

    private fun refreshMapLocation() {
        if (!hasForegroundLocationPermission()) return
        try {
            LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnSuccessListener { location ->
                if (location != null) { mapCurrentLocation = location; drawLocalHeatProfile() }
            }
        } catch (_: SecurityException) { }
    }

    private fun startMapLocationUpdates() {
        if (!hasForegroundLocationPermission() || mapLocationCallback != null) return
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { mapCurrentLocation = it; drawLocalHeatProfile() }
            }
        }
        mapLocationCallback = cb
        try {
            LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000L).setMinUpdateDistanceMeters(40f).build(), cb, mainLooper
            )
        } catch (_: SecurityException) { mapLocationCallback = null }
    }

    private fun stopMapLocationUpdates() {
        mapLocationCallback?.let { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) }
        mapLocationCallback = null
    }

    private fun openExternalMap(provider: String) {
        val location = mapCurrentLocation
        if (location == null) {
            Toast.makeText(this, "Aguardando localização GPS", Toast.LENGTH_SHORT).show()
            refreshMapLocation()
            return
        }
        val now = Calendar.getInstance()
        val area = nearestStrongArea(location, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.DAY_OF_WEEK), LocalContextAdvisor.holidayName(now) != null)?.first
        if (area == null) {
            Toast.makeText(this, "Sem faixa forte no perfil local agora; consulte o mapa antes de se deslocar.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = if (provider == "waze") Uri.parse("waze://?ll=${area.lat},${area.lon}&navigate=yes") else Uri.parse("google.navigation:q=${area.lat},${area.lon}&mode=d")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${area.lat},${area.lon}?q=${area.lat},${area.lon}(${Uri.encode(area.name)})"))) }
    }

    private fun dashboardPage(): View {
        val content = vertical()
        val intelligentMode = prefs.getBoolean(Prefs.INTELLIGENT_MODE, false)
        val modeCard = card()
        modeCard.addView(label("MODO INTELIGENTE • MONTES CLAROS", 14, accent, true))
        modeCard.addView(label("Inteligência local automática: cruza oferta, bairros reconhecidos no endereço, dia/horário, feriados e GPS durante o turno. Usa regras locais informadas e histórico real do aparelho; não inventa probabilidade nem garante retorno. GPS/paradas ficam no aparelho e não confirmam demanda ou fim de corrida.", 12, secondary))
        modeCard.addView(label("Para acompanhar com a tela fechada: permita localização precisa e, depois, ‘Permitir o tempo todo’ nas configurações do Android. Uma notificação fixa aparece enquanto o modo estiver ligado; desligue aqui para parar.", 11, secondary))
        modeCard.addView(actionButton(if (intelligentMode) "Modo Inteligente: LIGADO" else "Modo Inteligente: DESLIGADO") {
            val next = !prefs.getBoolean(Prefs.INTELLIGENT_MODE, false)
            if (next) {
                if (!hasForegroundLocationPermission()) {
                    prefs.edit().putBoolean(Prefs.SMART_ENABLE_PENDING, true).apply()
                    pendingLocationPermissionToggle = true
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
                    locationPermissionRequested = true
                    return@actionButton
                }
                if (!hasBackgroundLocationPermission() && android.os.Build.VERSION.SDK_INT >= 29) {
                    prefs.edit().putBoolean(Prefs.SMART_ENABLE_PENDING, true).apply()
                    pendingLocationPermissionToggle = true
                    locationPermissionRequested = false
                    ensureLocationPermissions()
                    return@actionButton
                }
            }
            prefs.edit().putBoolean(Prefs.SMART_ENABLE_PENDING, false).putBoolean(Prefs.INTELLIGENT_MODE, next).apply()
            if (next) startSmartTracking() else stopSmartTracking()
            Toast.makeText(this, if (next) "Modo Inteligente ligado" else "Modo Inteligente desligado", Toast.LENGTH_SHORT).show()
            renderTab()
        })
        content.addView(modeCard)
        content.addView(space(8))
        val calendarCard = card()
        calendarCard.addView(label("CALENDÁRIO E EVENTOS", 13, accent, true))
        calendarCard.addView(label("Opcional: usa eventos de hoje (título/horário/local) e feriados para dar contexto. O conteúdo é lido no aparelho; evento não prova demanda.", 12, secondary))
        val calendarOn = prefs.getBoolean(Prefs.CALENDAR_CONTEXT_ENABLED, false) && checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        calendarCard.addView(actionButton(if (calendarOn) "Calendário: ligado" else "Ativar contexto do calendário") { requestCalendarContext() })
        content.addView(calendarCard)
        content.addView(space(9))
        val todayStart = startOfToday()
        val todays = uniqueRecentOffers(RideHistory(this).between(todayStart, startOfTomorrow()))
        val gross = todays.sumOf { it.fare }
        val net = todays.sumOf { it.net }
        val km = todays.sumOf { it.pickupKm + it.tripKm }
        val minutes = todays.sumOf { it.minutes }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }
        brandText.addView(label("Olá, motorista", 23, pale, true))
        brandText.addView(label("RotaLume • Montes Claros", 13, accent, true))
        header.addView(brandText)
        content.addView(header)
        content.addView(space(10))
        val monitorOn = prefs.getBoolean(Prefs.MONITORING_ENABLED, true)
        val toggleCard = card()
        toggleCard.addView(label(if (monitorOn) "MONITORAMENTO LIGADO" else "MONITORAMENTO DESLIGADO", 14, if (monitorOn) lime else secondary, true))
        toggleCard.addView(space(6))
        toggleCard.addView(actionButton(if (monitorOn) "Desligar monitoramento" else "Ligar monitoramento") {
            val newValue = !prefs.getBoolean(Prefs.MONITORING_ENABLED, true)
            CorridaBoaAccessibilityService.setMonitoringFromActivity(this, newValue)
            Toast.makeText(this, if (newValue) "Monitoramento ligado" else "Monitoramento desligado", Toast.LENGTH_SHORT).show()
            renderTab()
        })
        content.addView(toggleCard)
        content.addView(space(10))

        val dailyCard = card()
        dailyCard.addView(label("RESUMO DE HOJE", 13, accent, true))
        dailyCard.addView(label(net.money(), 34, pale, true))
        dailyCard.addView(label("Lucro estimado após custos cadastrados", 13, secondary))
        val dailyTarget = prefs.getFloat(Prefs.DAILY_GROSS_TARGET, 300f).toDouble()
        val targetInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText(if (prefs.contains(Prefs.DAILY_GROSS_TARGET)) dailyTarget.toString() else "300")
            textSize = 14f
            setTextColor(pale)
            background = rounded(surface2, dp(10), Color.rgb(72, 89, 126))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        dailyCard.addView(label("Meta diária bruta estimada (R$) — padrão R$ 300: R$ 100 combustível + R$ 200 que você quer sobrar. Ajuste conforme seus custos.", 12, secondary))
        dailyCard.addView(targetInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        dailyCard.addView(actionButton("Salvar meta diária") {
            val value = parse(targetInput.text.toString())
            if (value == null || value <= 0) Toast.makeText(this, "Informe uma meta maior que zero", Toast.LENGTH_SHORT).show()
            else {
                prefs.edit().putFloat(Prefs.DAILY_GROSS_TARGET, value.toFloat()).apply()
                Toast.makeText(this, "Meta diária salva", Toast.LENGTH_SHORT).show()
                renderTab()
            }
        })
        val progressPct = if (dailyTarget > 0) (gross / dailyTarget * 100).toInt().coerceIn(0, 100) else 0
        dailyCard.addView(label("Progresso estimado: ${gross.money()} de ${dailyTarget.money()} (${progressPct}%)", 13, accent, true))
        dailyCard.addView(label("Baseado apenas nas tarifas de ofertas analisadas hoje — não comprova corrida concluída nem dinheiro recebido. Não é lucro; o valor abaixo desconta somente custos cadastrados.", 11, secondary))
        val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = progressPct
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        dailyCard.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(9)))
        dailyCard.addView(space(7))
        dailyCard.addView(label("LUCRO ESTIMADO DAS OFERTAS APÓS CUSTOS CADASTRADOS", 12, secondary))
        dailyCard.addView(label("Lucro estimado após custos cadastrados", 13, secondary))
        dailyCard.addView(space(12))
        dailyCard.addView(metricRow(listOf(
            "OFERTAS" to todays.size.toString(),
            "BRUTO" to gross.money(),
            "KM" to "${km.oneDecimal()}"
        )))
        dailyCard.addView(space(7))
        dailyCard.addView(label("Tempo das ofertas: ${minutes.oneDecimal()} min", 12, secondary))
        dailyCard.addView(label("Estimativa das ofertas analisadas, não corridas feitas; repetições em até 3 min não contam duas vezes.", 11, secondary))
        content.addView(dailyCard)
        content.addView(space(10))
        val calcCard = card()
        calcCard.addView(label("CALCULADORA DE GANHOS", 14, accent, true))
        calcCard.addView(label("Calcule um valor por km considerando combustível, custos mensais e sua meta.", 12, secondary))
        calcCard.addView(actionButton("Abrir calculadora de ganhos") { selectedTab = 3; renderTab() })
        content.addView(calcCard)
        content.addView(space(14))

        val serviceCard = card()
        serviceCard.addView(label("MONITORAMENTO EM TEMPO REAL", 15, pale, true))
        serviceCard.addView(space(5))
        val accessOn = isServiceEnabled()
        val overlayOn = Settings.canDrawOverlays(this)
        val statusText = when {
            !monitorOn -> "MONITORAMENTO DESLIGADO"
            accessOn && overlayOn -> "ATIVO — pronto para ler ofertas"
            else -> "PRECISA DE PERMISSÃO"
        }
        serviceCard.addView(label(statusText, 14, if (monitorOn && accessOn && overlayOn) lime else Color.rgb(255, 205, 92), true))
        serviceCard.addView(space(7))
        serviceCard.addView(label("Como usar: 1) deixe o monitoramento ligado; 2) em Configurar permissões, ative Acessibilidade e a janela flutuante; 3) abra Uber, 99 ou inDrive e aguarde uma oferta aparecer na tela. O RotaLume analisa a oferta sozinho — não precisa pedir por voz nem tocar em aceitar.", 13, secondary))
        serviceCard.addView(space(8))
        serviceCard.addView(actionButton("Configurar permissões") { permissionDialog() })
        content.addView(serviceCard)
        content.addView(space(14))

        val appsCard = card()
        appsCard.addView(label("APPS MONITORADOS", 14, pale, true))
        appsCard.addView(label("Uber Driver  •  99 Motorista  •  inDrive", 13, accent))
        appsCard.addView(label("A leitura depende das informações que cada app disponibiliza à Acessibilidade.", 12, secondary))
        content.addView(appsCard)
        content.addView(space(15))
        content.addView(label("O RotaLume só recomenda. Não aceita nem recusa corridas.", 12, secondary))

        return scroll(content)
    }

    private fun historyPage(): View {
        val content = vertical()
        content.addView(label("Histórico de ofertas", 24, pale, true))
        content.addView(label("Leituras e estimativas salvas neste aparelho", 13, secondary))
        content.addView(space(10))
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("Hoje", "Este mês", "Tudo").forEachIndexed { index, title ->
            val chip = actionButton(title) {
                historyFilter = index
                historyVisibleCount = 20
                renderTab()
            }
            chip.textSize = 12f
            chip.backgroundTintList = android.content.res.ColorStateList.valueOf(if (historyFilter == index) accent else surface2)
            chip.setTextColor(if (historyFilter == index) bg else pale)
            filters.addView(chip, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        }
        content.addView(filters)
        content.addView(space(10))

        val history = RideHistory(this)
        val filteredRaw = when (historyFilter) {
            0 -> history.between(startOfToday(), startOfTomorrow())
            1 -> history.between(startOfMonth(), startOfTomorrow())
            else -> history.all()
        }
        val filtered = uniqueRecentOffers(filteredRaw)
        val stats = card()
        stats.addView(label("LUCRO ESTIMADO DAS OFERTAS", 12, accent, true))
        stats.addView(label(filtered.sumOf { it.net }.money(), 29, pale, true))
        stats.addView(metricRow(listOf(
            "OFERTAS" to filtered.size.toString(),
            "KM" to filtered.sumOf { it.pickupKm + it.tripKm }.oneDecimal(),
            "TEMPO" to "${filtered.sumOf { it.minutes }.oneDecimal()} min"
        )))
        content.addView(stats)
        content.addView(label("Leituras repetidas da mesma oferta em até 3 min não entram duas vezes nas somas.", 11, secondary))
        content.addView(space(8))
        content.addView(actionButton("Apagar histórico") {
            AlertDialog.Builder(this)
                .setTitle("Apagar histórico?")
                .setMessage("Isso remove todas as ofertas salvas neste aparelho.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Apagar") { _, _ ->
                    RideHistory(this).deleteAll()
                    Toast.makeText(this, "Histórico apagado", Toast.LENGTH_SHORT).show()
                    renderTab()
                }.show()
        }.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(surface2)
            setTextColor(pale)
        })
        content.addView(space(7))
        if (filtered.isEmpty()) {
            val empty = card()
            empty.addView(label("Ainda não há ofertas neste período.", 15, pale, true))
            empty.addView(label("Quando uma oferta compatível aparecer, ela será registrada aqui.", 13, secondary))
            content.addView(empty)
        } else {
            filtered.take(historyVisibleCount).forEach { content.addView(recordCard(it)); content.addView(space(8)) }
            if (filtered.size > historyVisibleCount) {
                content.addView(actionButton("Carregar mais 20 ofertas") {
                    historyVisibleCount += 20
                    renderTab()
                }.apply {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(surface2)
                    setTextColor(pale)
                })
            }
        }
        return scroll(content)
    }

    private fun uniqueRecentOffers(records: List<RideRecord>): List<RideRecord> {
        val lastSeen = HashMap<String, Long>()
        return records.sortedByDescending { it.createdAt }.filter { record ->
            val key = listOf(
                String.format(Locale.US, "%.2f", record.fare),
                String.format(Locale.US, "%.1f", record.pickupKm),
                String.format(Locale.US, "%.1f", record.tripKm),
                record.pickup.trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " "),
                record.dropoff.trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")
            ).joinToString("|")
            val previous = lastSeen.put(key, record.createdAt)
            previous == null || previous - record.createdAt >= 180_000L
        }
    }

    private fun calculatorPage(): View {
        val content = vertical()
        content.addView(label("Calculadora de ganhos", 24, pale, true))
        content.addView(label("Descubra quanto a oferta precisa render por km para cobrir seus custos e sua meta.", 13, secondary))
        content.addView(space(10))

        val inputs = card()
        inputs.addView(label("DADOS DO SEU CARRO", 14, accent, true))
        val consumption = editField(inputs, "Seu carro faz quantos km/L?", Prefs.CONSUMO, 12.0)
        val fuel = editField(inputs, "Preço da gasolina na sua cidade (R$/L)", Prefs.GAS, 6.20)
        val fixedCost = editField(inputs, "Aluguel ou financiamento mensal (R$; opcional)", Prefs.MONTHLY_FIXED, 0.0)
        val otherMonthlyCost = editField(inputs, "Outros custos mensais (R$; opcional)", Prefs.MONTHLY_OTHER, 0.0)
        val monthlyDistance = editField(inputs, "Quantos km você roda no mês?", Prefs.MONTHLY_KM, 0.0)
        val desiredNet = editField(inputs, "Quanto quer lucrar por km depois dos custos?", Prefs.CALC_GOAL_PER_KM, 2.0)
        inputs.addView(label("Custos mensais são divididos pelos km do mês e descontados de cada oferta. Informe todos os custos que quiser incluir.", 12, secondary))
        content.addView(inputs)
        content.addView(space(9))

        var idealOfferPerKm = 0.0
        val resultCard = card().apply { visibility = View.GONE }
        val result = label("", 15, pale)
        resultCard.addView(label("SEU RESULTADO", 13, accent, true))
        resultCard.addView(result)
        content.addView(resultCard)
        val applyButton = actionButton("Usar resultado nas metas por km") {
            prefs.edit()
                .putFloat(Prefs.MIN_KM, idealOfferPerKm.toFloat())
                .putFloat(Prefs.FLOOR_KM, (idealOfferPerKm * 0.75).toFloat())
                .apply()
            Toast.makeText(this, "Meta boa e piso amarelo do km atualizados", Toast.LENGTH_SHORT).show()
        }.apply { visibility = View.GONE }
        content.addView(applyButton)
        content.addView(space(8))
        content.addView(actionButton("Calcular meu km ideal") {
            val consumptionValue = parse(consumption.text.toString())
            val fuelValue = parse(fuel.text.toString())
            val fixedValue = parse(fixedCost.text.toString())
            val otherMonthlyValue = parse(otherMonthlyCost.text.toString())
            val monthlyKmValue = parse(monthlyDistance.text.toString())
            val desiredValue = parse(desiredNet.text.toString())
            if (listOf(consumptionValue, fuelValue, fixedValue, otherMonthlyValue, monthlyKmValue, desiredValue).any { it == null } ||
                (consumptionValue ?: 0.0) <= 0.0 || (fuelValue ?: 0.0) <= 0.0 ||
                (fixedValue ?: -1.0) < 0.0 || (otherMonthlyValue ?: -1.0) < 0.0 || (monthlyKmValue ?: -1.0) < 0.0 || (desiredValue ?: -1.0) < 0.0 ||
                (((fixedValue ?: 0.0) + (otherMonthlyValue ?: 0.0)) > 0.0 && (monthlyKmValue ?: 0.0) <= 0.0)) {
                Toast.makeText(this, "Confira os valores e informe km/mês se tiver custos mensais.", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            val consumptionNumber = consumptionValue!!
            val fuelNumber = fuelValue!!
            val fixedNumber = fixedValue!!
            val otherMonthlyNumber = otherMonthlyValue!!
            val monthlyKmNumber = monthlyKmValue!!
            val desiredNumber = desiredValue!!
            val fuelPerKm = fuelNumber / consumptionNumber
            val monthlyCostPerKm = if (fixedNumber + otherMonthlyNumber > 0.0) (fixedNumber + otherMonthlyNumber) / monthlyKmNumber else 0.0
            val breakEvenPerKm = fuelPerKm + monthlyCostPerKm
            val idealGrossPerKm = breakEvenPerKm + desiredNumber
            idealOfferPerKm = idealGrossPerKm
            prefs.edit()
                .putFloat(Prefs.GAS, fuelNumber.toFloat())
                .putFloat(Prefs.CONSUMO, consumptionNumber.toFloat())
                .putFloat(Prefs.MONTHLY_FIXED, fixedNumber.toFloat())
                .putFloat(Prefs.MONTHLY_OTHER, otherMonthlyNumber.toFloat())
                .putFloat(Prefs.MONTHLY_KM, monthlyKmNumber.toFloat())
                .putFloat(Prefs.CALC_GOAL_PER_KM, desiredNumber.toFloat())
                .apply()
            result.text = "Combustível: ${fuelPerKm.money()}/km\nCustos mensais rateados: ${monthlyCostPerKm.money()}/km\nPonto de equilíbrio: ${breakEvenPerKm.money()}/km\nOferta ideal: ${idealGrossPerKm.money()}/km para sobrar ${desiredNumber.money()}/km de lucro estimado."
            applyButton.text = "Usar ${idealOfferPerKm.money()}/km como meta bruta da oferta"
            resultCard.visibility = View.VISIBLE
            applyButton.visibility = View.VISIBLE
        })
        content.addView(space(10))
        content.addView(label("Lucro estimado com os custos cadastrados. Custos que não informar — como manutenção, pneus, impostos ou depreciação — ficam de fora.", 12, secondary))
        return scroll(content)
    }

    private fun settingsPage(): View {
        val content = vertical()
        content.addView(label("Ajustes", 24, pale, true))
        content.addView(label("Custos e limites usados nas recomendações", 13, secondary))
        content.addView(space(10))

        val costCard = card()
        costCard.addView(label("CÁLCULO DE CUSTOS", 14, accent, true))
        gas = editField(costCard, "Preço da gasolina (R$/L)", Prefs.GAS, 6.20)
        consumo = editField(costCard, "Consumo médio (km/L)", Prefs.CONSUMO, 12.0)
        val costPerKm = label("Custo estimado de combustível: — / km", 13, pale, true)
        costCard.addView(space(5))
        costCard.addView(costPerKm)
        content.addView(costCard)
        content.addView(space(10))

        val thresholdCard = card()
        thresholdCard.addView(label("METAS DE CADA INDICADOR", 14, accent, true))
        thresholdCard.addView(label("Escolha o valor BOM (verde) e o piso de cada indicador. Entre eles fica amarelo; abaixo do piso fica vermelho. Ex.: R$ 2,00/km e piso R$ 1,40 deixam R$ 1,50 amarelo; R$ 40/h e piso R$ 30 deixam R$ 36 amarelo; minuto R$ 0,60 e piso R$ 0,45 deixam R$ 0,50 amarelo. Salve para aplicar automaticamente às próximas ofertas.", 12, secondary))
        val goodKmValue = prefs.getFloat(Prefs.MIN_KM, 2f).coerceAtLeast(0f)
        val goodHourValue = prefs.getFloat(Prefs.BOA_HORA, 35f).coerceAtLeast(0f)
        val goodMinuteValue = prefs.getFloat(Prefs.MIN_MINUTO, 0.58f).coerceAtLeast(0f)
        val floorKmValue = prefs.getFloat(Prefs.FLOOR_KM, minOf(1.50f, goodKmValue)).coerceIn(0f, goodKmValue)
        val floorHourValue = prefs.getFloat(Prefs.FLOOR_HOUR, minOf(31f, goodHourValue)).coerceIn(0f, goodHourValue)
        val floorMinuteValue = prefs.getFloat(Prefs.FLOOR_MINUTE, minOf(0.49f, goodMinuteValue)).coerceIn(0f, goodMinuteValue)
        prefs.edit().putFloat(Prefs.FLOOR_KM, floorKmValue).putFloat(Prefs.FLOOR_HOUR, floorHourValue)
            .putFloat(Prefs.FLOOR_MINUTE, floorMinuteValue).apply()
        targetKm = editField(thresholdCard, "KM BOM/VERDE — meta mínima (R$/km)", Prefs.MIN_KM, 2.0)
        floorKm = editField(thresholdCard, "KM RUIM/VERMELHO — abaixo deste piso (R$/km)", Prefs.FLOOR_KM, floorKmValue.toDouble())
        targetHour = editField(thresholdCard, "HORA BOA/VERDE — meta mínima (R$/h)", Prefs.BOA_HORA, 35.0)
        floorHour = editField(thresholdCard, "HORA RUIM/VERMELHA — abaixo deste piso (R$/h)", Prefs.FLOOR_HOUR, floorHourValue.toDouble())
        targetMinute = editField(thresholdCard, "MINUTO BOM/VERDE — meta mínima (R$/min)", Prefs.MIN_MINUTO, 0.58)
        floorMinute = editField(thresholdCard, "MINUTO RUIM/VERMELHO — abaixo deste piso (R$/min)", Prefs.FLOOR_MINUTE, floorMinuteValue.toDouble())
        thresholdCard.addView(label("Recomendação geral: PEGAR com 2 ou 3 verdes e nenhum vermelho; AVALIAR se pelo menos 2 forem verdes/amarelos; NÃO PEGAR se menos de 2 forem aceitáveis ou o lucro não for positivo.", 12, secondary))
        content.addView(thresholdCard)
        content.addView(space(9))
        content.addView(actionButton("Salvar configurações") {
            val pairs = listOf(
                gas to Prefs.GAS, consumo to Prefs.CONSUMO,
                targetKm to Prefs.MIN_KM, floorKm to Prefs.FLOOR_KM,
                targetHour to Prefs.BOA_HORA, floorHour to Prefs.FLOOR_HOUR,
                targetMinute to Prefs.MIN_MINUTO, floorMinute to Prefs.FLOOR_MINUTE
            )
            val values = pairs.map { parse(it.first.text.toString()) }
            val goodKm = parse(targetKm.text.toString())
            val minKm = parse(floorKm.text.toString())
            val goodHour = parse(targetHour.text.toString())
            val minHour = parse(floorHour.text.toString())
            val goodMinute = parse(targetMinute.text.toString())
            val minMinute = parse(floorMinute.text.toString())
            if (values.any { it == null } || (values[1] ?: 0.0) <= 0.0 || values.drop(2).any { (it ?: -1.0) < 0.0 } ||
                minKm!! > goodKm!! || minHour!! > goodHour!! || minMinute!! > goodMinute!!) {
                Toast.makeText(this, "Confira os números: o consumo deve ser positivo e cada piso deve ser menor ou igual à meta boa.", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            prefs.edit().apply { pairs.forEachIndexed { index, pair -> putFloat(pair.second, values[index]!!.toFloat()) } }.apply()
            val cost = (values[0]!! / values[1]!!).money()
            costPerKm.text = "Custo estimado de combustível: $cost / km"
            Toast.makeText(this, "Configurações salvas", Toast.LENGTH_SHORT).show()
        })
        costPerKm.text = "Custo estimado de combustível: ${(prefs.getFloat(Prefs.GAS, 6.20f) / prefs.getFloat(Prefs.CONSUMO, 12f)).toDouble().money()} / km"
        content.addView(space(10))

        val accessCard = card()
        accessCard.addView(label("PERMISSÕES", 14, accent, true))
        val accessLabel = label("Acessibilidade: ${if (isServiceEnabled()) "ativada" else "pendente"}", 13, pale)
        val overlayLabel = label("Janela flutuante: ${if (Settings.canDrawOverlays(this)) "permitida" else "pendente"}", 13, pale)
        accessStatusLabel = accessLabel
        overlayStatusLabel = overlayLabel
        accessCard.addView(accessLabel)
        accessCard.addView(overlayLabel)
        accessCard.addView(space(5))
        accessCard.addView(actionButton("Configurar permissões") { permissionDialog() })
        content.addView(accessCard)
        content.addView(space(10))

        val apps = card()
        apps.addView(label("APPS MONITORADOS", 14, accent, true))
        listOf("Uber Driver", "99 Motorista", "inDrive").forEach { app ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label("•  $app", 14, pale), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label("ATIVO", 11, lime, true))
            apps.addView(row)
        }
        content.addView(apps)
        content.addView(space(10))
        val bubble = card()
        bubble.addView(label("BOLHA FLUTUANTE R$", 14, accent, true))
        bubble.addView(label("Arraste a bolinha para onde quiser. Toque para ler a tela atual por OCR; segure para ligar ou desligar o OCR contínuo (Android 11+). As imagens são processadas localmente; o modo contínuo pode gastar mais bateria.", 13, secondary))
        content.addView(bubble)
        content.addView(space(12))
        content.addView(label("O lucro usa combustível e custos mensais cadastrados na calculadora. O que não informar — manutenção, pneus, impostos ou depreciação — não será descontado.", 12, secondary))
        return scroll(content)
    }

    private fun permissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Ativar monitoramento")
            .setMessage("1. Permita a janela flutuante.\n2. Ative RotaLume em Acessibilidade. Se estiver bloqueado, abra Informações do app > ⋮ > Permitir configurações restritas e tente novamente.\n3. Abra o app de motorista. A bolinha R$ aparece e pode ser arrastada.\n\nO app apenas lê a oferta e recomenda; não toca em aceitar ou recusar.")
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Abrir permissões") { _, _ ->
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }.show()
    }

    private fun recordCard(r: RideRecord): LinearLayout {
        val color = when (r.status) {
            OverlayManager.GOOD -> lime
            OverlayManager.MAYBE -> Color.rgb(255, 206, 92)
            OverlayManager.BAD -> Color.rgb(255, 111, 121)
            else -> secondary
        }
        val title = when (r.status) {
            OverlayManager.GOOD -> "PEGAR CORRIDA"
            OverlayManager.MAYBE -> "AVALIAR CORRIDA"
            OverlayManager.BAD -> "NÃO PEGAR CORRIDA"
            else -> "LEITURA INCOMPLETA"
        }
        val card = card()
        card.addView(label(SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale("pt", "BR")).format(Date(r.createdAt)), 12, secondary))
        card.addView(label(title, 14, color, true))
        card.addView(label("${r.fare.money()}  •  ${ (r.pickupKm + r.tripKm).oneDecimal()} km  •  ${r.minutes.oneDecimal()} min", 14, pale, true))
        card.addView(label("Origem: ${r.pickup.ifBlank { "não identificada" }}", 12, secondary))
        card.addView(label("Destino: ${r.dropoff.ifBlank { "não identificado" }}", 12, secondary))
        card.addView(label("Gasolina: ${r.fuelCost.money()}  •  Custos mensais rateados: ${r.monthlyCost.money()}  •  Total: ${(r.fuelCost + r.monthlyCost).money()}", 12, pale))
        card.addView(label("Oferta bruta: ${r.grossKm.money()}/km  •  ${r.grossHour.money()}/h  •  ${r.grossMinute.money()}/min", 14, secondary))
        return card
    }

    private fun metricRow(items: List<Pair<String, String>>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { index, pair ->
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(7), dp(5), dp(4), dp(5))
                addView(label(pair.first, 10, secondary, true))
                addView(label(pair.second, 14, pale, true))
            }
            addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun editField(parent: LinearLayout, title: String, key: String, default: Double): EditText {
        parent.addView(label(title, 12, secondary))
        val current = prefs.getFloat(key, default.toFloat()).toString()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            setText(current)
            textSize = 15f
            setTextColor(pale)
            setHintTextColor(secondary)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(surface2, dp(10), Color.rgb(72, 89, 126))
        }
        parent.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(7) })
        return input
    }

    private fun actionButton(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 14f
        setTextColor(bg)
        backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(3) }
    }

    private fun vertical(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(17), dp(18), dp(18))
        setBackgroundColor(bg)
    }

    private fun scroll(content: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setBackgroundColor(bg)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(13), dp(15), dp(13))
        background = rounded(surface, dp(21), Color.rgb(53, 70, 110))
        elevation = dp(2).toFloat()
    }

    private fun rounded(color: Int, radius: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun hasForegroundLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocationPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestCalendarContext() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(Prefs.CALENDAR_CONTEXT_ENABLED, true).apply()
            Toast.makeText(this, "Contexto do calendário ativado", Toast.LENGTH_SHORT).show()
            renderTab()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Permitir leitura do calendário?")
            .setMessage("O RotaLume pode verificar eventos de hoje (título, horário e local) para considerar shows, aulas e datas especiais. A leitura acontece somente no aparelho; eventos não confirmam demanda. Você pode negar e continuar usando o app.")
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Continuar") { _, _ -> requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQUEST_CALENDAR) }
            .show()
    }

    private fun ensureLocationPermissions() {
        if (locationPermissionRequested) return
        locationPermissionRequested = true
        if (!hasForegroundLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
        } else if (!hasBackgroundLocationPermission() && android.os.Build.VERSION.SDK_INT >= 29) {
            if (android.os.Build.VERSION.SDK_INT == 29) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BACKGROUND_LOCATION)
                return
            }
            AlertDialog.Builder(this)
                .setTitle("Permitir localização em segundo plano")
                .setMessage("O Android separa localização durante o uso e em segundo plano. Primeiro autorize localização (selecione Precisa para identificar bairros; aproximada também funciona, com menor precisão). No Android 11 ou mais recente, abra Permissões do app → Localização → Permitir o tempo todo e ative Localização precisa. O rastreamento só ocorre com Modo Inteligente ligado, fica no aparelho e mostra notificação contínua.")
                .setNegativeButton("Agora não") { _, _ ->
                    if (!hasBackgroundLocationPermission()) {
                        pendingLocationPermissionToggle = false
                        prefs.edit().putBoolean(Prefs.SMART_ENABLE_PENDING, false).putBoolean(Prefs.INTELLIGENT_MODE, false).apply()
                        if (::pageHost.isInitialized) renderTab()
                    }
                }
                .setPositiveButton("Abrir permissões") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BACKGROUND_LOCATION)
                    }
                }.show()
        } else startSmartTracking()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MAP_LOCATION) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                refreshMapLocation(); startMapLocationUpdates()
            } else Toast.makeText(this, "Sem GPS; você ainda pode consultar o mapa manualmente.", Toast.LENGTH_LONG).show()
        } else if (requestCode == REQUEST_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            if (hasBackgroundLocationPermission()) {
                val enable = prefs.getBoolean(Prefs.SMART_ENABLE_PENDING, false)
                prefs.edit().putBoolean(Prefs.INTELLIGENT_MODE, enable).putBoolean(Prefs.SMART_ENABLE_PENDING, false).apply()
                pendingLocationPermissionToggle = false
                if (enable) startSmartTracking()
                if (::pageHost.isInitialized) renderTab()
            } else {
                locationPermissionRequested = false
                ensureLocationPermissions()
            }
        } else if (requestCode == REQUEST_CALENDAR) {
            val granted = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            prefs.edit().putBoolean(Prefs.CALENDAR_CONTEXT_ENABLED, granted).apply()
            Toast.makeText(this, if (granted) "Calendário ativado" else "Sem calendário; estratégia local continua ativa", Toast.LENGTH_LONG).show()
            if (::pageHost.isInitialized) renderTab()
        } else if (requestCode == REQUEST_BACKGROUND_LOCATION && hasBackgroundLocationPermission()) {
            val enable = prefs.getBoolean(Prefs.SMART_ENABLE_PENDING, false)
            prefs.edit().putBoolean(Prefs.INTELLIGENT_MODE, enable).putBoolean(Prefs.SMART_ENABLE_PENDING, false).apply()
            pendingLocationPermissionToggle = false
            if (enable) startSmartTracking()
        } else if (requestCode == REQUEST_LOCATION || requestCode == REQUEST_BACKGROUND_LOCATION) {
            prefs.edit().putBoolean(Prefs.INTELLIGENT_MODE, false).putBoolean(Prefs.SMART_ENABLE_PENDING, false).apply()
            Toast.makeText(this, "Sem localização, o Modo Inteligente não acompanha deslocamentos. O cálculo de ofertas continua disponível.", Toast.LENGTH_LONG).show()
            if (::pageHost.isInitialized) renderTab()
        }
    }

    private fun startSmartTracking() {
        if (!hasForegroundLocationPermission()) return
        val intent = Intent(this, SmartTrackingService::class.java).setAction(SmartTrackingService.ACTION_START)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) { }
    }

    private fun stopSmartTracking() {
        stopService(Intent(this, SmartTrackingService::class.java).setAction(SmartTrackingService.ACTION_STOP))
        prefs.edit().remove(Prefs.SMART_CURRENT_AREA).remove(Prefs.SMART_IDLE_STARTED_AT).apply()
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, CorridaBoaAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any {
            it.packageName == expected.packageName && it.className == expected.className
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfTomorrow(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    private fun startOfMonth(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun parse(value: String): Double? = value.trim().replace(" ", "").replace(",", ".").toDoubleOrNull()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

internal object Prefs {
    const val FILE = "corrida_boa_settings"
    const val GAS = "gas_price"
    const val CONSUMO = "consumo"
    const val MIN_HORA = "min_hour"
    const val BOA_HORA = "good_hour"
    const val MIN_KM = "good_km"
    const val FLOOR_KM = "floor_km"
    const val MIN_MINUTO = "good_minute"
    const val FLOOR_HOUR = "floor_hour"
    const val FLOOR_MINUTE = "floor_minute"
    const val MONITORING_ENABLED = "monitoring_enabled"
    const val MONTHLY_FIXED = "monthly_fixed_cost"
    const val MONTHLY_OTHER = "monthly_other_costs"
    const val MONTHLY_KM = "monthly_distance_km"
    const val CALC_GOAL_PER_KM = "calculator_goal_per_km"
    const val OCR_CONTINUOUS = "ocr_continuous"
    const val INTELLIGENT_MODE = "intelligent_mode_montes_claros"
    const val SMART_ACCEPTED_AT = "smart_accepted_at"
    const val SMART_DESTINATION = "smart_destination"
    const val SMART_ACTIVE_TRIP_STARTED = "smart_active_trip_started"
    const val SMART_ACTIVE_TRIP_DESTINATION = "smart_active_trip_destination"
    const val SMART_ENABLE_PENDING = "smart_enable_pending"
    const val SMART_CURRENT_AREA = "smart_current_area"
    const val SMART_IDLE_STARTED_AT = "smart_idle_started_at"
    const val DAILY_GROSS_TARGET = "daily_gross_target"
    const val CALENDAR_CONTEXT_ENABLED = "calendar_context_enabled"
}

private const val REQUEST_LOCATION = 7001
private const val REQUEST_BACKGROUND_LOCATION = 7002
private const val REQUEST_CALENDAR = 7003
private const val REQUEST_MAP_LOCATION = 7004
