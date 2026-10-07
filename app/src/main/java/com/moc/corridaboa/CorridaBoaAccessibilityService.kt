package com.moc.corridaboa

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.provider.Settings
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import kotlin.math.max

class CorridaBoaAccessibilityService : AccessibilityService() {
    private var lastSignature = ""
    private var lastAnnouncedAt = 0L
    private var lastRidePackage: String? = null
    private var lastRideScreenText: String? = null
    private var screenshotInFlight = false
    private var lastOcrRequestAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val supportedPackages = setOf(
        "com.ubercab.driver", "com.app99.driver", "com.d99.android.driver", "com.99Taxis.driver", "com.didi.driver",
        "sinet.startup.inDriver"
    )
    private val settings by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }
    private val history by lazy { RideHistory(this) }
    private val continuousOcrLoop = object : Runnable {
        override fun run() {
            if (!settings.getBoolean(Prefs.OCR_CONTINUOUS, false)) return
            captureVisibleScreen(manual = false, sourcePackage = "visual-scan", baseText = null)
            handler.postDelayed(this, 1_400)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val continuous = settings.getBoolean(Prefs.OCR_CONTINUOUS, false)
        if (Settings.canDrawOverlays(this)) {
            OverlayManager.showFloatingButton(this, { rereadCurrentOffer() }, { toggleContinuousOcr() })
            OverlayManager.setOcrMode(continuous)
        }
        if (continuous && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) handler.post(continuousOcrLoop)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in supportedPackages) {
            if (pkg != packageName) {
                lastSignature = ""
                lastRidePackage = null
                lastRideScreenText = null
            }
            return
        }
        if (Settings.canDrawOverlays(this)) {
            OverlayManager.showFloatingButton(this, { rereadCurrentOffer() }, { toggleContinuousOcr() })
        }
        val root = rootInActiveWindow
        val screenText = collectText(root).replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
        lastRidePackage = pkg
        lastRideScreenText = screenText
        if (hasEssentialText(screenText)) {
            analyzeScreen(pkg, screenText, manual = false)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // If the offer is drawn as pixels rather than accessibility text, try local OCR.
            captureVisibleScreen(manual = false, sourcePackage = pkg, baseText = screenText)
        } else if (screenText.isNotBlank()) {
            analyzeScreen(pkg, screenText, manual = false)
        }
    }

    private fun rereadCurrentOffer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureVisibleScreen(manual = true, sourcePackage = "visual-scan", baseText = null)
        } else {
            val pkg = lastRidePackage
            val text = lastRideScreenText
            if (pkg != null && text != null) analyzeScreen(pkg, text, manual = true)
            else showScanMessage("A leitura visual precisa do Android 11 ou mais recente.", OverlayManager.WARNING)
        }
    }

    private fun toggleContinuousOcr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showScanMessage("OCR contínuo exige Android 11 ou mais recente.", OverlayManager.WARNING)
            return
        }
        val enabled = !settings.getBoolean(Prefs.OCR_CONTINUOUS, false)
        settings.edit().putBoolean(Prefs.OCR_CONTINUOUS, enabled).apply()
        OverlayManager.setOcrMode(enabled)
        if (enabled) {
            handler.removeCallbacks(continuousOcrLoop)
            handler.post(continuousOcrLoop)
            android.widget.Toast.makeText(this, "OCR contínuo ligado — segure a bolinha para desligar", android.widget.Toast.LENGTH_LONG).show()
        } else {
            handler.removeCallbacks(continuousOcrLoop)
            android.widget.Toast.makeText(this, "OCR contínuo desligado", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun captureVisibleScreen(manual: Boolean, sourcePackage: String, baseText: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (manual) {
                val cached = lastRideScreenText
                if (cached != null && lastRidePackage != null) analyzeScreen(lastRidePackage!!, cached, manual = true)
                else showScanMessage("A leitura visual precisa do Android 11 ou mais recente.", OverlayManager.WARNING)
            }
            return
        }
        val now = System.currentTimeMillis()
        if (screenshotInFlight || (!manual && now - lastOcrRequestAt < 1_100)) return
        screenshotInFlight = true
        lastOcrRequestAt = now
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Exception) { null }
                    try { screenshot.hardwareBuffer.close() } catch (_: Exception) { }
                    if (bitmap == null) {
                        screenshotInFlight = false
                        if (manual) screenshotFailed()
                        return
                    }
                    textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { vision ->
                            val visibleText = vision.text
                            bitmap.recycle()
                            screenshotInFlight = false
                            val combined = if (sourcePackage in supportedPackages && !baseText.isNullOrBlank()) {
                                "$baseText $visibleText"
                            } else visibleText
                            if (combined.isNotBlank()) {
                                if (sourcePackage in supportedPackages) {
                                    lastRidePackage = sourcePackage
                                    lastRideScreenText = combined
                                }
                                analyzeScreen(sourcePackage, combined, manual)
                            } else if (manual) {
                                showScanMessage("Não encontrei texto legível nesta tela. Tente enquanto o preço estiver aparecendo.", OverlayManager.WARNING)
                            }
                        }
                        .addOnFailureListener {
                            bitmap.recycle()
                            screenshotInFlight = false
                            if (manual) screenshotFailed()
                        }
                }

                override fun onFailure(errorCode: Int) {
                    screenshotInFlight = false
                    if (manual) screenshotFailed()
                }
            })
        } catch (_: Exception) {
            screenshotInFlight = false
            if (manual) screenshotFailed()
        }
    }

    private fun screenshotFailed() {
        val pkg = lastRidePackage
        val text = lastRideScreenText
        if (pkg != null && !text.isNullOrBlank() && hasEssentialText(text)) {
            analyzeScreen(pkg, text, manual = true)
        } else {
            showScanMessage("Não consegui capturar esta tela. Ela pode estar protegida ou o texto pode ter passado rápido.", OverlayManager.WARNING)
        }
    }

    private fun showScanMessage(message: String, status: Int) {
        OverlayManager.show(this, message, status)
    }

    private fun hasEssentialText(text: String): Boolean {
        if (!PRICE.containsMatchIn(text)) return false
        val routes = ROUTE.findAll(text).count()
        val distances = KM.findAll(text).count()
        val minutes = MINUTES.findAll(text).count()
        return routes >= 2 || (distances >= 2 && minutes >= 2)
    }

    private fun analyzeScreen(pkg: String, rawText: String, manual: Boolean) {
        val screenText = rawText.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
        val fare = PRICE.find(screenText)?.groupValues?.getOrNull(1)?.toBrazilianDouble()
        if (fare == null) {
            if (manual) showScanMessage("Não encontrei um preço de corrida nesta tela.", OverlayManager.WARNING)
            return
        }

        val routeParts = ROUTE.findAll(screenText).toList()
        val allDistances = KM.findAll(screenText).mapNotNull { it.groupValues.getOrNull(1)?.toBrazilianDouble() }.toList()
        val allMinutes = MINUTES.findAll(screenText).mapNotNull { it.groupValues.getOrNull(1)?.toBrazilianDouble() }.toList()
        val completeRoute = routeParts.size >= 2 || (allDistances.size >= 2 && allMinutes.size >= 2)
        if (!completeRoute) {
            val partial = buildString {
                append("OFERTA DETECTADA — LEITURA PARCIAL\nValor mostrado: ${fare.money()}\n")
                if (allDistances.isNotEmpty()) append("Quilômetros lidos: ${allDistances.take(2).sum().oneDecimal()} km\n")
                if (allMinutes.isNotEmpty()) append("Tempo lido: ${allMinutes.take(2).sum().oneDecimal()} min\n")
                append("Não calculei o líquido porque faltam dados da rota.")
            }
            val signature = "incompleto:$fare:${screenText.hashCode()}"
            if (manual || shouldDisplay(signature)) OverlayManager.show(this, partial, OverlayManager.WARNING)
            return
        }
        val kmBusca = if (routeParts.size >= 2) {
            routeParts[0].groupValues[2].toBrazilianDouble() ?: 0.0
        } else allDistances[0]
        val kmViagem = if (routeParts.size >= 2) {
            routeParts[1].groupValues[2].toBrazilianDouble() ?: 0.0
        } else allDistances[1]
        val tempoTotal = if (routeParts.size >= 2) {
            (routeParts[0].groupValues[1].toBrazilianDouble() ?: 0.0) +
                (routeParts[1].groupValues[1].toBrazilianDouble() ?: 0.0)
        } else allMinutes.take(2).sum()
        val pickup = routeParts.getOrNull(0)?.groupValues?.getOrNull(3).orEmpty().cleanRouteText()
        val dropoff = routeParts.getOrNull(1)?.groupValues?.getOrNull(3).orEmpty().cleanRouteText()
        val totalKm = kmBusca + kmViagem
        if (totalKm <= 0.0 || tempoTotal <= 0.0) return

        val gasPrice = settings.getFloat(Prefs.GAS, 6.20f).toDouble()
        val consumption = max(0.1, settings.getFloat(Prefs.CONSUMO, 12f).toDouble())
        val targetPerHour = settings.getFloat(Prefs.BOA_HORA, 35f).toDouble()
        val targetPerKm = settings.getFloat(Prefs.MIN_KM, 2f).toDouble()
        val targetPerMinute = settings.getFloat(Prefs.MIN_MINUTO, 0.58f).toDouble()
        val fuelCost = totalKm * gasPrice / consumption
        val net = fare - fuelCost
        val grossHour = fare / (tempoTotal / 60.0)
        val grossMinute = fare / tempoTotal
        val grossKm = fare / totalKm
        val netHour = net / (tempoTotal / 60.0)
        val netMinute = net / tempoTotal
        val netKm = net / totalKm
        val meetsKm = netKm >= targetPerKm
        val meetsHour = netHour >= targetPerHour
        val meetsMinute = netMinute >= targetPerMinute
        val status = when {
            net <= 0.0 || (!meetsKm && !meetsHour && !meetsMinute) -> OverlayManager.BAD
            meetsKm && meetsHour && meetsMinute -> OverlayManager.GOOD
            else -> OverlayManager.MAYBE
        }
        val signature = listOf(pkg, fare, kmBusca, kmViagem, tempoTotal, pickup, dropoff).joinToString("|")
        val now = System.currentTimeMillis()
        val duplicate = signature == lastSignature && now - lastAnnouncedAt < 180_000
        if (duplicate && !manual) return
        if (!duplicate) {
            lastSignature = signature
            lastAnnouncedAt = now
            val record = RideRecord(
                now, pickup, dropoff, fare, kmBusca, kmViagem, tempoTotal, fuelCost, net,
                grossHour, grossKm, grossMinute, netHour, netKm, netMinute, status
            )
            runCatching { history.save(record) }
        }
        val title = when (status) {
            OverlayManager.GOOD -> "CORRIDA BOA — ACEITAR"
            OverlayManager.MAYBE -> "MÉDIA — AVALIAR"
            else -> "CORRIDA RUIM — NÃO ACEITAR"
        }
        val details = """$title
Valor da oferta (bruto): ${fare.money()}
Combustível estimado: -${fuelCost.money()}
Sobra estimada para você: ${net.money()}
Busca ${kmBusca.oneDecimal()} km + viagem ${kmViagem.oneDecimal()} km = ${totalKm.oneDecimal()} km • ${tempoTotal.oneDecimal()} min
Seus ganhos líquidos: ${netKm.money()}/km • ${netHour.money()}/h • ${netMinute.money()}/min
Metas atingidas: km ${if (meetsKm) "sim" else "não"} • hora ${if (meetsHour) "sim" else "não"} • minuto ${if (meetsMinute) "sim" else "não"}
Bruto: ${grossKm.money()}/km • ${grossHour.money()}/h • ${grossMinute.money()}/min"""
        OverlayManager.show(this, details, status)
    }

    private fun shouldDisplay(signature: String): Boolean {
        val now = System.currentTimeMillis()
        if (signature == lastSignature && now - lastAnnouncedAt < 180_000) return false
        lastSignature = signature
        lastAnnouncedAt = now
        return true
    }

    private fun collectText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val out = StringBuilder()
        try {
            node.text?.let { out.append(it).append(' ') }
            node.contentDescription?.let { out.append(it).append(' ') }
            for (i in 0 until node.childCount) out.append(collectText(node.getChild(i))).append(' ')
        } catch (_: Exception) { }
        return out.toString()
    }

    override fun onInterrupt() { }
    override fun onDestroy() {
        handler.removeCallbacks(continuousOcrLoop)
        textRecognizer.close()
        OverlayManager.hide(this)
        super.onDestroy()
    }

    companion object {
        private val PRICE = Regex("""R\$\s*([0-9]{1,3}(?:\.[0-9]{3})*,[0-9]{2}|[0-9]+,[0-9]{2}|[0-9]+(?:\.[0-9]{2})?)""", RegexOption.IGNORE_CASE)
        private val KM = Regex("""([0-9]+(?:[.,][0-9]+)?)\s?km""", RegexOption.IGNORE_CASE)
        private val MINUTES = Regex("""([0-9]+(?:[.,][0-9]+)?)\s?min""", RegexOption.IGNORE_CASE)
        private val ROUTE = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*min\s*\(\s*([0-9]+(?:[.,][0-9]+)?)\s*km\s*\)\s*(.*?)(?=[0-9]+(?:[.,][0-9]+)?\s*min\s*\(\s*[0-9]+(?:[.,][0-9]+)?\s*km\s*\)|$)""", RegexOption.IGNORE_CASE)
    }
}

private fun String.toBrazilianDouble(): Double? {
    val value = trim()
    val normalized = if (value.contains(',')) value.replace(".", "").replace(',', '.') else value
    return normalized.toDoubleOrNull()
}
private fun String.cleanRouteText(): String = replace(Regex("""\s+"""), " ").trim().trim(' ', '-', '•', '|').take(150)
internal fun Double.money(): String = "R$ " + String.format(Locale("pt", "BR"), "%.2f", this)
internal fun Double.oneDecimal(): String = String.format(Locale("pt", "BR"), "%.1f", this)
