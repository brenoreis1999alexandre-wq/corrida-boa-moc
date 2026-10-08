package com.moc.corridaboa

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
    private data class OfferContext(val text: String, val fare: Double, val distancesKm: List<Double>, val durationsMin: List<Double>)
    private fun monitoringEnabled() = settings.getBoolean(Prefs.MONITORING_ENABLED, true)
    private val continuousOcrLoop = object : Runnable {
        override fun run() {
            if (!monitoringEnabled() || !settings.getBoolean(Prefs.OCR_CONTINUOUS, false)) return
            val root = rootInActiveWindow
            val activePackage = root?.packageName?.toString()
            if (activePackage != null && activePackage in supportedPackages) {
                // Read the visible offer card; don't reuse route text from the active trip.
                captureVisibleScreen(manual = false, sourcePackage = activePackage)
            }
            handler.postDelayed(this, 1_400)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        applyMonitoringState()
    }

    private fun applyMonitoringState() {
        handler.removeCallbacks(continuousOcrLoop)
        if (monitoringEnabled() && Settings.canDrawOverlays(this)) {
            OverlayManager.showFloatingButton(this, { rereadCurrentOffer() }, { toggleContinuousOcr() })
            val continuous = settings.getBoolean(Prefs.OCR_CONTINUOUS, false)
            OverlayManager.setOcrMode(this, continuous)
            if (continuous && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) handler.post(continuousOcrLoop)
        } else {
            lastSignature = ""
            lastAnnouncedAt = 0L
            OverlayManager.hide(this)
        }
    }

    private fun activeDriverPackage(): String? = rootInActiveWindow?.packageName?.toString()?.takeIf { it in supportedPackages }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!monitoringEnabled()) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in supportedPackages) {
            if (pkg != packageName) lastSignature = ""
            return
        }
        if (Settings.canDrawOverlays(this)) {
            OverlayManager.showFloatingButton(this, { rereadCurrentOffer() }, { toggleContinuousOcr() })
        }
        val root = rootInActiveWindow
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Prefer the visible offer card geometry when another ride is active.
            captureVisibleScreen(manual = false, sourcePackage = pkg)
        } else {
            val exactOffer = extractOfferContextFromNode(root)
            if (exactOffer != null) analyzeScreen(pkg, exactOffer.text, manual = false)
        }
    }

    private fun rereadCurrentOffer() {
        if (!monitoringEnabled()) return
        val pkg = activeDriverPackage()
        if (pkg == null) {
            showScanMessage("Abra Uber Driver, 99 Motorista ou inDrive e deixe a oferta na tela.", OverlayManager.WARNING)
            return
        }
        val root = rootInActiveWindow
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureVisibleScreen(manual = true, sourcePackage = pkg)
        } else {
            val exactOffer = extractOfferContextFromNode(root)
            if (exactOffer != null) analyzeScreen(pkg, exactOffer.text, manual = true)
            else showScanMessage("Não consegui separar esta oferta com segurança. Não calculei para não misturar com a corrida atual.", OverlayManager.WARNING)
        }
    }

    private fun toggleContinuousOcr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showScanMessage("OCR contínuo exige Android 11 ou mais recente.", OverlayManager.WARNING)
            return
        }
        val enabled = !settings.getBoolean(Prefs.OCR_CONTINUOUS, false)
        settings.edit().putBoolean(Prefs.OCR_CONTINUOUS, enabled).apply()
        OverlayManager.setOcrMode(this, enabled)
        if (enabled) {
            handler.removeCallbacks(continuousOcrLoop)
            handler.post(continuousOcrLoop)
            android.widget.Toast.makeText(this, "OCR contínuo ligado — segure a bolinha para desligar", android.widget.Toast.LENGTH_LONG).show()
        } else {
            handler.removeCallbacks(continuousOcrLoop)
            android.widget.Toast.makeText(this, "OCR contínuo desligado", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun captureVisibleScreen(manual: Boolean, sourcePackage: String) {
        if (!monitoringEnabled() || sourcePackage !in supportedPackages) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (manual) showScanMessage("Sem leitura visual neste Android, não consegui isolar a nova oferta com segurança.", OverlayManager.WARNING)
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
                            val excludedBounds = OverlayManager.screenshotExclusionRects()
                            val visibleText = vision.textBlocks.flatMap { it.lines }
                                .filter { line ->
                                    val box = line.boundingBox
                                    box == null || excludedBounds.none { excluded -> Rect.intersects(box, excluded) }
                                }
                                .sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: 0 }))
                                .joinToString(" ") { it.text }
                                .replace(Regex("""\s+"""), " ").trim()
                            bitmap.recycle()
                            screenshotInFlight = false
                            // Parse only OCR lines visible outside our own overlays. Do not
                            // merge the flattened Accessibility tree back in: it can mix the
                            // active trip route with the incoming offer card.
                            val exactOffer = extractOfferContext(visibleText)
                            if (exactOffer != null) {
                                analyzeScreen(sourcePackage, exactOffer.text, manual)
                            } else if (manual && visibleText.isNotBlank()) {
                                analyzeScreen(sourcePackage, visibleText, manual = true)
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
        showScanMessage("Não consegui confirmar os dados visíveis. Não calculei para evitar misturar a corrida atual com a nova oferta.", OverlayManager.WARNING)
    }

    private fun showScanMessage(message: String, status: Int) {
        OverlayManager.show(this, message, status)
    }

    private fun extractOfferContext(text: String): OfferContext? {
        val actions = OFFER_ACTION.findAll(text).toList()
        if (actions.isEmpty()) return null
        val candidates = linkedMapOf<String, OfferContext>()
        val prices = PRICE.findAll(text).filterNot { match ->
            val suffix = text.substring((match.range.last + 1).coerceAtMost(text.length))
            val prefix = text.substring(0, match.range.first).takeLast(12).trimEnd()
            UNIT_RATE_SUFFIX.containsMatchIn(suffix) ||
                (prefix.endsWith("+") && INCLUDED_BONUS_SUFFIX.containsMatchIn(suffix))
        }.toList()

        // A new-offer card starts at its displayed payout. Never take route details
        // before that payout (they can belong to the ride already in progress).
        // Only accept a price/action pair enclosing exactly two complete offer legs.
        for (price in prices) {
            val fare = price.groupValues.getOrNull(1)?.toBrazilianDouble() ?: continue
            for (action in actions) {
                if (action.range.first <= price.range.last || action.range.last - price.range.first > MAX_OFFER_CONTEXT) continue
                val routeText = text.substring(price.range.last + 1, action.range.first)
                val headers = ROUTE_HEADER.findAll(routeText).toList()
                val distances: List<Double>
                val durations: List<Double>
                if (headers.isNotEmpty()) {
                    // An extra route header means another trip/route is mixed in: fail closed.
                    if (headers.size != 2) continue
                    durations = headers.mapNotNull { it.groupValues[1].toBrazilianDouble() }
                    distances = headers.mapNotNull { it.groupValues[2].toBrazilianDouble() }
                    if (durations.size != 2 || distances.size != 2) continue
                } else {
                    // Some versions expose time and distance as separate text nodes.
                    // Exclude the displayed unit rate (e.g. R$ 1,16/km) from route km.
                    val distanceMatches = KM.findAll(routeText).filterNot { km ->
                        val prefix = routeText.substring(0, km.range.first).takeLast(14).trimEnd()
                        prefix.endsWith("/") || prefix.endsWith("por", ignoreCase = true)
                    }.toList()
                    val minuteMatches = MINUTES.findAll(routeText).toList()
                    if (distanceMatches.size != 2 || minuteMatches.size != 2) continue
                    distances = distanceMatches.mapNotNull { it.groupValues[1].toBrazilianDouble() }
                    durations = minuteMatches.mapNotNull { it.groupValues[1].toBrazilianDouble() }
                    if (durations.size != 2 || distances.size != 2) continue
                }
                val contextText = text.substring(price.range.first, action.range.last + 1).trim()
                val key = "$fare:${distances.joinToString(",")}:${durations.joinToString(",")}"
                candidates[key] = OfferContext(contextText, fare, distances, durations)
            }
        }
        // If there is more than one plausible payout/route grouping, do not guess.
        return candidates.values.singleOrNull()
    }

    private fun extractOfferContextFromNode(root: AccessibilityNodeInfo?): OfferContext? {
        if (root == null) return null
        val contexts = linkedMapOf<String, OfferContext>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val ownText = listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
                    .filter { it.isNotBlank() }.distinct().joinToString(" ")
                if (OFFER_ACTION.containsMatchIn(ownText)) {
                    var ancestor: AccessibilityNodeInfo? = node
                    var depth = 0
                    while (ancestor != null && depth < 12) {
                        val scope = collectText(ancestor).replace(Regex("""\s+"""), " ").trim()
                        val exact = extractOfferContext(scope)
                        if (exact != null) {
                            contexts.putIfAbsent("${exact.fare}:${exact.text}", exact)
                            break
                        }
                        ancestor = ancestor.parent
                        depth++
                    }
                }
                for (i in 0 until node.childCount) visit(node.getChild(i))
            } catch (_: Exception) { }
        }
        visit(root)
        return contexts.values.singleOrNull()
    }

    private fun analyzeScreen(pkg: String, rawText: String, manual: Boolean) {
        if (!monitoringEnabled() || pkg !in supportedPackages) return
        val fullText = rawText.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
        if (!OFFER_ACTION.containsMatchIn(fullText)) {
            if (manual) showScanMessage("Não identifiquei uma oferta ativa nesta tela; não vou calcular usando a navegação do mapa.", OverlayManager.WARNING)
            return
        }
        val offerContext = extractOfferContext(fullText)
        if (offerContext == null) {
            if (manual) showScanMessage("Não consegui separar os dados da oferta nova do trajeto que já está em andamento. Deixe a oferta visível e tente de novo.", OverlayManager.WARNING)
            return
        }
        val screenText = offerContext.text
        val fare = offerContext.fare

        val kmBusca = offerContext.distancesKm[0]
        val kmViagem = offerContext.distancesKm[1]
        val tempoTotal = offerContext.durationsMin.sum()
        val pickup = ROUTE.findAll(screenText).toList().getOrNull(0)?.groupValues?.getOrNull(3).orEmpty().cleanRouteText()
        val dropoff = ROUTE.findAll(screenText).toList().getOrNull(1)?.groupValues?.getOrNull(3).orEmpty().cleanRouteText()
        val totalKm = kmBusca + kmViagem
        if (totalKm <= 0.0 || tempoTotal <= 0.0) return

        val gasPrice = settings.getFloat(Prefs.GAS, 6.20f).toDouble()
        val consumption = max(0.1, settings.getFloat(Prefs.CONSUMO, 12f).toDouble())
        val monthlyFixed = settings.getFloat(Prefs.MONTHLY_FIXED, 0f).toDouble()
        val monthlyOther = settings.getFloat(Prefs.MONTHLY_OTHER, 0f).toDouble()
        val monthlyDistance = settings.getFloat(Prefs.MONTHLY_KM, 0f).toDouble()
        if ((monthlyFixed + monthlyOther) > 0.0 && monthlyDistance <= 0.0) {
            if (manual) showScanMessage("Informe os km rodados por mês na Calculadora de Ganhos para incluir os custos mensais.", OverlayManager.WARNING)
            return
        }
        val targetPerHour = settings.getFloat(Prefs.BOA_HORA, 35f).toDouble()
        val targetPerKm = settings.getFloat(Prefs.MIN_KM, 2f).toDouble()
        val targetPerMinute = settings.getFloat(Prefs.MIN_MINUTO, 0.58f).toDouble()
        val floorPerKm = settings.getFloat(Prefs.FLOOR_KM, 1.50f).toDouble()
        val floorPerHour = settings.getFloat(Prefs.FLOOR_HOUR, 31f).toDouble()
        val floorPerMinute = settings.getFloat(Prefs.FLOOR_MINUTE, 0.49f).toDouble()
        val fuelCost = totalKm * gasPrice / consumption
        val monthlyCost = if (monthlyDistance > 0.0) totalKm * (monthlyFixed + monthlyOther) / monthlyDistance else 0.0
        val totalCosts = fuelCost + monthlyCost
        val profit = fare - totalCosts
        val grossHour = fare / (tempoTotal / 60.0)
        val grossMinute = fare / tempoTotal
        val grossKm = fare / totalKm
        val profitHour = profit / (tempoTotal / 60.0)
        val profitMinute = profit / tempoTotal
        val profitKm = profit / totalKm
        val kmStatus = when { grossKm >= targetPerKm -> OverlayManager.GOOD; grossKm >= floorPerKm -> OverlayManager.MAYBE; else -> OverlayManager.BAD }
        val hourStatus = when { grossHour >= targetPerHour -> OverlayManager.GOOD; grossHour >= floorPerHour -> OverlayManager.MAYBE; else -> OverlayManager.BAD }
        val minuteStatus = when { grossMinute >= targetPerMinute -> OverlayManager.GOOD; grossMinute >= floorPerMinute -> OverlayManager.MAYBE; else -> OverlayManager.BAD }
        val statuses = listOf(kmStatus, hourStatus, minuteStatus)
        val goodMetrics = statuses.count { it == OverlayManager.GOOD }
        val acceptableMetrics = statuses.count { it != OverlayManager.BAD }
        val status = when {
            profit <= 0.0 -> OverlayManager.BAD
            goodMetrics >= 2 && statuses.none { it == OverlayManager.BAD } -> OverlayManager.GOOD
            acceptableMetrics >= 2 -> OverlayManager.MAYBE
            else -> OverlayManager.BAD
        }
        val signature = listOf(pkg, fare, kmBusca, kmViagem, tempoTotal, pickup, dropoff).joinToString("|")
        val now = System.currentTimeMillis()
        val duplicate = signature == lastSignature && now - lastAnnouncedAt < 180_000
        if (duplicate && !manual) return
        if (!duplicate) {
            lastSignature = signature
            lastAnnouncedAt = now
            val record = RideRecord(
                now, pickup, dropoff, fare, kmBusca, kmViagem, tempoTotal, fuelCost, monthlyCost, profit,
                grossHour, grossKm, grossMinute, profitHour, profitKm, profitMinute, status
            )
            runCatching { history.save(record) }
        }
        OverlayManager.showOfferResult(
            this, fare.money(), fuelCost.money(), monthlyCost.money(), totalCosts.money(), profit.money(),
            totalKm.oneDecimal(), tempoTotal.oneDecimal(), grossKm.money(), grossHour.money(), grossMinute.money(),
            kmStatus, hourStatus, minuteStatus, status
        )
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
            val nodeText = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            if (nodeText.isNotBlank()) out.append(nodeText).append(' ')
            if (description.isNotBlank() && description != nodeText) out.append(description).append(' ')
            for (i in 0 until node.childCount) out.append(collectText(node.getChild(i))).append(' ')
        } catch (_: Exception) { }
        return out.toString()
    }

    override fun onInterrupt() { }
    override fun onDestroy() {
        handler.removeCallbacks(continuousOcrLoop)
        textRecognizer.close()
        OverlayManager.hide(this)
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    companion object {
        private var activeService: CorridaBoaAccessibilityService? = null

        fun setMonitoringFromActivity(context: Context, enabled: Boolean) {
            context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
                .putBoolean(Prefs.MONITORING_ENABLED, enabled).apply()
            activeService?.applyMonitoringState()
        }
        private const val MAX_OFFER_CONTEXT = 1_200
        private val OFFER_ACTION = Regex("""\b(aceitar|aceite|aceito|contraoferta|recusar|rejeitar)\b""", RegexOption.IGNORE_CASE)
        private val PRICE = Regex("""R\$\s*([0-9]{1,3}(?:\.[0-9]{3})*,[0-9]{2}|[0-9]+,[0-9]{2}|[0-9]+(?:\.[0-9]{2})?)""", RegexOption.IGNORE_CASE)
        private val UNIT_RATE_SUFFIX = Regex("""^\s*(?:/\s*(?:km|h|hr|min|hora)\b|por\s+(?:km|hora|minuto)\b)""", RegexOption.IGNORE_CASE)
        private val INCLUDED_BONUS_SUFFIX = Regex("""^\s*(?:inclu[ií]do|inclu[ií]da|b[oô]nus)\b""", RegexOption.IGNORE_CASE)
        private val KM = Regex("""([0-9]+(?:[.,][0-9]+)?)\s?km""", RegexOption.IGNORE_CASE)
        private val MINUTES = Regex("""([0-9]+(?:[.,][0-9]+)?)\s?min""", RegexOption.IGNORE_CASE)
        private val ROUTE_HEADER = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*min(?:uto|utos|s)?\s*\(\s*([0-9]+(?:[.,][0-9]+)?)\s*km\s*\)""", RegexOption.IGNORE_CASE)
        private val ROUTE = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*min(?:uto|utos|s)?\s*\(\s*([0-9]+(?:[.,][0-9]+)?)\s*km\s*\)\s*(.*?)(?=[0-9]+(?:[.,][0-9]+)?\s*min(?:uto|utos|s)?\s*\(\s*[0-9]+(?:[.,][0-9]+)?\s*km\s*\)|$)""", RegexOption.IGNORE_CASE)
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
