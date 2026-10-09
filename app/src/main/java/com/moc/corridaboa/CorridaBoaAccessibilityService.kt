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
    private var lastVisibleOfferFingerprint = ""
    private var noOfferResetPending = false
    private var screenGeneration = 0
    private var screenshotInFlight = false
    private var pendingCapture: PendingCapture? = null
    private var lastOcrRequestAt = 0L
    private data class PendingCapture(val manual: Boolean, val pkg: String)
    private val resetOfferStateTask = Runnable {
        lastSignature = ""
        lastAnnouncedAt = 0L
        lastVisibleOfferFingerprint = ""
        noOfferResetPending = false
    }
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
                val treeText = collectText(root).replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
                val is99 = is99Package(activePackage)
                val treeOffer = extractOfferContextFromNode(root, allowActionless99 = is99)
                    ?: extractOfferContext(treeText, allowActionless99 = is99)
                if (is99 && treeOffer != null) {
                    noteOfferVisible(activePackage, treeOffer)
                    analyzeScreen(activePackage, treeOffer.text, manual = false)
                } else if (treeOffer != null) requestOfferRead(activePackage, treeOffer)
                else {
                    if (!OFFER_ACTION.containsMatchIn(treeText) && !PRICE.containsMatchIn(treeText)) noteNoOfferVisible()
                    captureVisibleScreen(manual = false, sourcePackage = activePackage)
                }
            }
            handler.postDelayed(this, 1_400)
        }
    }
    private val ninetyNinePollLoop = object : Runnable {
        override fun run() {
            if (!monitoringEnabled()) return
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString()
            if (pkg != null && is99Package(pkg)) {
                val treeText = collectText(root).replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
                val offer = extractOfferContextFromNode(root, allowActionless99 = true)
                    ?: extractOfferContext(treeText, allowActionless99 = true)
                if (offer != null) {
                    noteOfferVisible(pkg, offer)
                    analyzeScreen(pkg, offer.text, manual = false)
                } else {
                    if (!OFFER_ACTION.containsMatchIn(treeText) && !PRICE.containsMatchIn(treeText)) noteNoOfferVisible()
                    captureVisibleScreen(manual = false, sourcePackage = pkg)
                }
            }
            handler.postDelayed(this, if (pkg != null && is99Package(pkg)) 1_200L else 2_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        applyMonitoringState()
    }

    private fun applyMonitoringState() {
        handler.removeCallbacks(continuousOcrLoop)
        handler.removeCallbacks(ninetyNinePollLoop)
        if (monitoringEnabled() && Settings.canDrawOverlays(this)) {
            OverlayManager.showFloatingButton(this, { rereadCurrentOffer() }, { toggleContinuousOcr() })
            val continuous = settings.getBoolean(Prefs.OCR_CONTINUOUS, false)
            OverlayManager.setOcrMode(this, continuous)
            handler.post(ninetyNinePollLoop)
            if (continuous && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) handler.post(continuousOcrLoop)
        } else {
            handler.removeCallbacks(resetOfferStateTask)
            lastSignature = ""
            lastAnnouncedAt = 0L
            lastVisibleOfferFingerprint = ""
            noOfferResetPending = false
            screenGeneration++
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
        val treeText = collectText(root).replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
        val is99 = is99Package(pkg)
        val treeOffer = extractOfferContextFromNode(root, allowActionless99 = is99)
            ?: extractOfferContext(treeText, allowActionless99 = is99)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && is99 && treeOffer != null) {
            // The 99 card has route/payment text in Accessibility but may omit an accept button;
            // use its isolated card subtree directly instead of requiring a screen capture.
            noteOfferVisible(pkg, treeOffer)
            analyzeScreen(pkg, treeOffer.text, manual = false)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && treeOffer != null) {
            requestOfferRead(pkg, treeOffer)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!OFFER_ACTION.containsMatchIn(treeText) && !PRICE.containsMatchIn(treeText)) noteNoOfferVisible()
            captureVisibleScreen(manual = false, sourcePackage = pkg)
        } else if (treeOffer != null) {
            noteOfferVisible(pkg, treeOffer)
            analyzeScreen(pkg, treeOffer.text, manual = false)
        } else if (!OFFER_ACTION.containsMatchIn(treeText) && !PRICE.containsMatchIn(treeText)) {
            noteNoOfferVisible()
        }
    }

    private fun is99Package(pkg: String): Boolean = pkg in NINETY_NINE_PACKAGES

    private fun hasActionless99CardMarker(text: String): Boolean =
        NINETY_NINE_PAYMENT_MARKER.containsMatchIn(text) || NINETY_NINE_SERVICE_TYPE_MARKER.containsMatchIn(text)

    private fun actionless99ContextStart(text: String, priceStart: Int): Int {
        val beforeFare = text.substring(0, priceStart.coerceIn(0, text.length))
        NINETY_NINE_PAYMENT_MARKER.findAll(beforeFare).lastOrNull()?.let { return it.range.first }
        return NINETY_NINE_SERVICE_TYPE_MARKER.findAll(beforeFare).lastOrNull()?.range?.first ?: priceStart
    }

    private fun requestOfferRead(pkg: String, offer: OfferContext) {
        if (noteOfferVisible(pkg, offer)) {
            lastOcrRequestAt = 0L
            val requestGeneration = screenGeneration
            handler.postDelayed({
                if (requestGeneration == screenGeneration) {
                    captureVisibleScreen(manual = false, sourcePackage = pkg, force = true)
                }
            }, 120)
        } else {
            captureVisibleScreen(manual = false, sourcePackage = pkg)
        }
    }

    private fun noteOfferVisible(pkg: String, offer: OfferContext): Boolean {
        // A single missed poll is not an actual gap: cancel the pending reset and keep
        // the existing result when the same offer returns on the next accessibility pass.
        handler.removeCallbacks(resetOfferStateTask)
        noOfferResetPending = false
        val fingerprint = offerFingerprint(pkg, offer)
        if (fingerprint == lastVisibleOfferFingerprint) return false
        lastSignature = ""
        lastAnnouncedAt = 0L
        lastVisibleOfferFingerprint = fingerprint
        screenGeneration++
        // Keep the previous card visible until the new result is ready; showOfferResult
        // replaces it in one UI update, avoiding a clear-then-reopen blink.
        return true
    }

    private fun noteNoOfferVisible() {
        handler.removeCallbacks(resetOfferStateTask)
        if (!noOfferResetPending) {
            noOfferResetPending = true
            screenGeneration++
        }
        handler.postDelayed(resetOfferStateTask, OFFER_ABSENCE_RESET_MS)
    }

    private fun rereadCurrentOffer() {
        if (!monitoringEnabled()) return
        val pkg = activeDriverPackage()
        if (pkg == null) {
            showScanMessage("Abra Uber Driver, 99 Motorista ou inDrive e deixe a oferta na tela.", OverlayManager.WARNING)
            return
        }
        val root = rootInActiveWindow
        val is99 = is99Package(pkg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && is99) {
            val treeText = collectText(root).replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
            val exactOffer = extractOfferContextFromNode(root, allowActionless99 = true)
                ?: extractOfferContext(treeText, allowActionless99 = true)
            if (exactOffer != null) {
                noteOfferVisible(pkg, exactOffer)
                analyzeScreen(pkg, exactOffer.text, manual = true)
            } else captureVisibleScreen(manual = true, sourcePackage = pkg)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureVisibleScreen(manual = true, sourcePackage = pkg)
        } else {
            val treeText = collectText(root).replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
            val exactOffer = extractOfferContextFromNode(root, allowActionless99 = is99) 
                ?: extractOfferContext(treeText, allowActionless99 = is99)
            if (exactOffer != null) {
                noteOfferVisible(pkg, exactOffer)
                analyzeScreen(pkg, exactOffer.text, manual = true)
            } else showScanMessage("Não consegui separar esta oferta com segurança. Não calculei para não misturar com a corrida atual.", OverlayManager.WARNING)
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

    private fun captureVisibleScreen(manual: Boolean, sourcePackage: String, force: Boolean = false) {
        if (!monitoringEnabled() || sourcePackage !in supportedPackages) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (manual) showScanMessage("Sem leitura visual neste Android, não consegui isolar a nova oferta com segurança.", OverlayManager.WARNING)
            return
        }
        val now = System.currentTimeMillis()
        if (screenshotInFlight) {
            if (force || manual) pendingCapture = PendingCapture(manual, sourcePackage)
            return
        }
        if (!manual && !force && now - lastOcrRequestAt < 1_100) return
        screenshotInFlight = true
        lastOcrRequestAt = now
        val requestGeneration = screenGeneration
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Exception) { null }
                    try { screenshot.hardwareBuffer.close() } catch (_: Exception) { }
                    if (bitmap == null) {
                        finishScreenshot()
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
                            if (requestGeneration == screenGeneration) {
                                // Parse only OCR lines visible outside our own overlays. Do not
                                // merge the flattened Accessibility tree back in: it can mix the
                                // active trip route with the incoming offer card.
                                val exactOffer = extractOfferContext(visibleText, allowActionless99 = is99Package(sourcePackage))
                                if (exactOffer != null) {
                                    noteOfferVisible(sourcePackage, exactOffer)
                                    analyzeScreen(sourcePackage, exactOffer.text, manual)
                                } else {
                                    if (!OFFER_ACTION.containsMatchIn(visibleText) && !PRICE.containsMatchIn(visibleText)) noteNoOfferVisible()
                                    if (manual && visibleText.isNotBlank()) {
                                        analyzeScreen(sourcePackage, visibleText, manual = true)
                                    } else if (manual) {
                                        showScanMessage("Não encontrei texto legível nesta tela. Tente enquanto o preço estiver aparecendo.", OverlayManager.WARNING)
                                    }
                                }
                            }
                            finishScreenshot()
                        }
                        .addOnFailureListener {
                            bitmap.recycle()
                            finishScreenshot()
                            if (manual) screenshotFailed()
                        }
                }

                override fun onFailure(errorCode: Int) {
                    finishScreenshot()
                    if (manual) screenshotFailed()
                }
            })
        } catch (_: Exception) {
            finishScreenshot()
            if (manual) screenshotFailed()
        }
    }

    private fun finishScreenshot() {
        screenshotInFlight = false
        val pending = pendingCapture ?: return
        pendingCapture = null
        handler.post { captureVisibleScreen(pending.manual, pending.pkg, force = true) }
    }

    private fun screenshotFailed() {
        showScanMessage("Não consegui confirmar os dados visíveis. Não calculei para evitar misturar a corrida atual com a nova oferta.", OverlayManager.WARNING)
    }

    private fun showScanMessage(message: String, status: Int) {
        OverlayManager.show(this, message, status)
    }

    private fun extractOfferContext(text: String, allowActionless99: Boolean = false): OfferContext? {
        val actions = OFFER_ACTION.findAll(text).toList()
        val candidates = linkedMapOf<String, OfferContext>()
        val prices = PRICE.findAll(text).filterNot { isExcludedPrice(text, it) }.toList()

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
                    distances = headers.mapNotNull { header ->
                        val value = header.groupValues[2].toBrazilianDouble() ?: return@mapNotNull null
                        if (header.groupValues[3].equals("m", ignoreCase = true)) value / 1_000.0 else value
                    }
                    if (durations.size != 2 || distances.size != 2) continue
                } else {
                    // Some versions expose time and distance as separate text nodes.
                    // Handle meters too and exclude the displayed unit rate (e.g. R$1,16/km).
                    val distanceMatches = DISTANCE.findAll(routeText).filterNot { distance ->
                        val prefix = routeText.substring(0, distance.range.first).takeLast(14).trimEnd()
                        prefix.endsWith("/") || prefix.endsWith("por", ignoreCase = true)
                    }.toList()
                    val minuteMatches = MINUTES.findAll(routeText).toList()
                    if (distanceMatches.size != 2 || minuteMatches.size != 2) continue
                    distances = distanceMatches.mapNotNull { match ->
                        val value = match.groupValues[1].toBrazilianDouble() ?: return@mapNotNull null
                        if (match.groupValues[2].equals("m", ignoreCase = true)) value / 1_000.0 else value
                    }
                    durations = minuteMatches.mapNotNull { it.groupValues[1].toBrazilianDouble() }
                    if (durations.size != 2 || distances.size != 2) continue
                }
                val contextText = text.substring(price.range.first, action.range.last + 1).trim()
                val key = "$fare:${distances.joinToString(",")}:${durations.joinToString(",")}"
                candidates[key] = OfferContext(contextText, fare, distances, durations)
            }
        }
        // If there is more than one plausible payout/route grouping, do not guess.
        candidates.values.singleOrNull()?.let { return it }
        if (!allowActionless99) return null
        return extractActionless99Offer(text, prices)
    }

    private fun isExcludedPrice(text: String, match: MatchResult): Boolean {
        val suffix = text.substring((match.range.last + 1).coerceAtMost(text.length))
        val prefix = text.substring(0, match.range.first).takeLast(12).trimEnd()
        return UNIT_RATE_SUFFIX.containsMatchIn(suffix) ||
            NON_FARE_EXTRA_SUFFIX.containsMatchIn(suffix) ||
            (prefix.endsWith("+") && INCLUDED_BONUS_SUFFIX.containsMatchIn(suffix))
    }

    private fun extractActionless99Offer(text: String, prices: List<MatchResult>): OfferContext? {
        if (!hasActionless99CardMarker(text)) return null
        val candidates = linkedMapOf<String, OfferContext>()
        for (price in prices) {
            val fare = price.groupValues.getOrNull(1)?.toBrazilianDouble() ?: continue
            val routeStart = price.range.last + 1
            val routeEnd = minOf(text.length, routeStart + MAX_OFFER_CONTEXT)
            val headers = ROUTE_HEADER.findAll(text.substring(routeStart, routeEnd)).toList()
            if (headers.size != 2) continue
            val durations = headers.mapNotNull { it.groupValues[1].toBrazilianDouble() }
            val distances = headers.mapNotNull { header ->
                val value = header.groupValues[2].toBrazilianDouble() ?: return@mapNotNull null
                if (header.groupValues[3].equals("m", ignoreCase = true)) value / 1_000.0 else value
            }
            if (durations.size != 2 || distances.size != 2) continue
            val contextStart = actionless99ContextStart(text, price.range.first)
            val contextText = text.substring(contextStart, routeEnd).trim()
            val key = "$fare:${distances.joinToString(",")}:${durations.joinToString(",")}"
            candidates[key] = OfferContext(contextText, fare, distances, durations)
        }
        return candidates.values.singleOrNull()
    }

    private fun extractOfferContextFromNode(root: AccessibilityNodeInfo?, allowActionless99: Boolean = false): OfferContext? {
        if (root == null) return null
        val contexts = linkedMapOf<String, Pair<Int, OfferContext>>()
        fun addContext(scope: String) {
            if (scope.length > MAX_OFFER_CONTEXT * 2) return
            val exact = extractOfferContext(scope, allowActionless99) ?: return
            val key = "${exact.fare}:${exact.distancesKm.joinToString(",")}:${exact.durationsMin.joinToString(",")}"
            val previous = contexts[key]
            if (previous == null || scope.length < previous.first) contexts[key] = scope.length to exact
        }
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
                        val exact = extractOfferContext(scope, allowActionless99)
                        if (exact != null) {
                            addContext(scope)
                            break
                        }
                        ancestor = ancestor.parent
                        depth++
                    }
                }
                if (allowActionless99 && NINETY_NINE_CARD_MARKER.containsMatchIn(ownText)) {
                    var ancestor: AccessibilityNodeInfo? = node
                    var depth = 0
                    while (ancestor != null && depth < 20) {
                        val scope = collectText(ancestor).replace(Regex("""\s+"""), " ").trim()
                        if (scope.length <= MAX_OFFER_CONTEXT * 2 && PRICE.containsMatchIn(scope)) {
                            val exact = extractOfferContext(scope, allowActionless99 = true)
                            if (exact != null) {
                                addContext(scope)
                                break
                            }
                        }
                        ancestor = ancestor.parent
                        depth++
                    }
                }
                for (i in 0 until node.childCount) visit(node.getChild(i))
            } catch (_: Exception) { }
        }
        visit(root)
        return contexts.values.singleOrNull()?.second
    }

    private fun offerFingerprint(pkg: String, offer: OfferContext): String =
        // Street labels and live ETA text can vary between Accessibility and OCR reads
        // of the same offer. Use its stable fare and two route distances for deduplication.
        listOf(pkg, offer.fare, offer.distancesKm.getOrNull(0), offer.distancesKm.getOrNull(1)).joinToString("|")

    private fun analyzeScreen(pkg: String, rawText: String, manual: Boolean) {
        if (!monitoringEnabled() || pkg !in supportedPackages) return
        val fullText = rawText.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
        val actionless99Card = is99Package(pkg) && hasActionless99CardMarker(fullText)
        if (!OFFER_ACTION.containsMatchIn(fullText) && !actionless99Card) {
            if (manual) showScanMessage("Não identifiquei uma oferta ativa nesta tela; não vou calcular usando a navegação do mapa.", OverlayManager.WARNING)
            return
        }
        val offerContext = extractOfferContext(fullText, allowActionless99 = is99Package(pkg))
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
        val signature = offerFingerprint(pkg, offerContext)
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
        handler.removeCallbacks(ninetyNinePollLoop)
        handler.removeCallbacks(resetOfferStateTask)
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
        private const val OFFER_ABSENCE_RESET_MS = 6_000L
        private val NINETY_NINE_PACKAGES = setOf("com.app99.driver", "com.d99.android.driver", "com.99Taxis.driver", "com.didi.driver")
        private val NINETY_NINE_PAYMENT_MARKER = Regex("""\b(dinheiro|pgto\.?\s*no\s*app|negocia)\b""", RegexOption.IGNORE_CASE)
        private val NINETY_NINE_SERVICE_TYPE_MARKER = Regex("""\b(pop|expresso|expressa|negocia)\b""", RegexOption.IGNORE_CASE)
        private val NINETY_NINE_CARD_MARKER = Regex("""\b(dinheiro|pgto\.?\s*no\s*app|negocia|pop|expresso|expressa|priorit[aá]ria)\b""", RegexOption.IGNORE_CASE)
        private val OFFER_ACTION = Regex("""\b(aceitar|aceite|aceito|selecionar|selecione|contraoferta|recusar|rejeitar)\b""", RegexOption.IGNORE_CASE)
        private val PRICE = Regex("""R\$\s*([0-9]{1,3}(?:\.[0-9]{3})*,[0-9]{2}|[0-9]+,[0-9]{2}|[0-9]+(?:\.[0-9]{2})?)""", RegexOption.IGNORE_CASE)
        private val UNIT_RATE_SUFFIX = Regex("""^\s*(?:/\s*(?:km|h|hr|min|hora)\b|por\s+(?:km|hora|minuto)\b)""", RegexOption.IGNORE_CASE)
        private val INCLUDED_BONUS_SUFFIX = Regex("""^\s*(?:inclu[ií]do|inclu[ií]da|b[oô]nus)\b""", RegexOption.IGNORE_CASE)
        private val NON_FARE_EXTRA_SUFFIX = Regex("""^\s*(?:por\s+espera(?:\s+longa)?|a\s+mais\s+por\s+corrida|por\s+corrida|inclu[ií]d[oa]|tarifa\s+expresso\s+inclus[oa]|tarifa\s+base\s+din[aâ]mica\s+incl\.?)(?:\b|$)""", RegexOption.IGNORE_CASE)
        private val DISTANCE = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*(km|m)\b""", RegexOption.IGNORE_CASE)
        private val MINUTES = Regex("""([0-9]+(?:[.,][0-9]+)?)\s?min""", RegexOption.IGNORE_CASE)
        private val ROUTE_HEADER = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*min(?:uto|utos|s)?\s*\(\s*([0-9]+(?:[.,][0-9]+)?)\s*(km|m)\s*\)""", RegexOption.IGNORE_CASE)
        private val ROUTE = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*min(?:uto|utos|s)?\s*\(\s*([0-9]+(?:[.,][0-9]+)?)\s*(?:km|m)\s*\)\s*(.*?)(?=[0-9]+(?:[.,][0-9]+)?\s*min(?:uto|utos|s)?\s*\(\s*[0-9]+(?:[.,][0-9]+)?\s*(?:km|m)\s*\)|$)""", RegexOption.IGNORE_CASE)
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
