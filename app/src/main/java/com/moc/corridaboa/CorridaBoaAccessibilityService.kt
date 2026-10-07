package com.moc.corridaboa

import android.accessibilityservice.AccessibilityService
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import kotlin.math.max

class CorridaBoaAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSignature = ""
    private var lastAnnouncedAt = 0L
    private var lastRidePackage: String? = null
    private var lastRideScreenText: String? = null
    private val supportedPackages = setOf(
        "com.ubercab.driver", "com.d99.android.driver", "com.99Taxis.driver", "com.didi.driver"
    )
    private val settings by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }
    private val history by lazy { RideHistory(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
        if (Settings.canDrawOverlays(this)) {
            OverlayManager.showFloatingButton(this) { rereadCurrentOffer() }
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale("pt", "BR")
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
            OverlayManager.showFloatingButton(this) { rereadCurrentOffer() }
        }
        val root = rootInActiveWindow ?: return
        val screenText = collectText(root).replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
        lastRidePackage = pkg
        lastRideScreenText = screenText
        analyzeScreen(pkg, screenText, manual = false)
    }

    private fun rereadCurrentOffer() {
        val pkg = lastRidePackage
        val text = lastRideScreenText
        if (pkg == null || text == null) {
            val warning = "Abra o app de motorista e aguarde uma oferta para ler."
            OverlayManager.show(this, warning, OverlayManager.WARNING)
            speak(warning)
            return
        }
        analyzeScreen(pkg, text, manual = true)
    }

    private fun analyzeScreen(pkg: String, rawText: String, manual: Boolean) {
        val screenText = rawText.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
        val fare = PRICE.find(screenText)?.groupValues?.getOrNull(1)?.toBrazilianDouble() ?: return

        // Em telas que mostram “10 min (3,9 km)” e “21 min (8,5 km)”, usa cada trecho e endereço.
        val routeParts = ROUTE.findAll(screenText).toList()
        val allDistances = KM.findAll(screenText)
            .mapNotNull { it.groupValues.getOrNull(1)?.toBrazilianDouble() }.toList()
        val allMinutes = MINUTES.findAll(screenText)
            .mapNotNull { it.groupValues.getOrNull(1)?.toBrazilianDouble() }.take(2).toList()
        val kmBusca = routeParts.getOrNull(0)?.groupValues?.getOrNull(2)?.toBrazilianDouble()
            ?: if (allDistances.size >= 2) allDistances[0] else 0.0
        val kmViagem = routeParts.getOrNull(1)?.groupValues?.getOrNull(2)?.toBrazilianDouble()
            ?: if (allDistances.size >= 2) allDistances[1] else allDistances.firstOrNull() ?: 0.0
        val tempoTotal = if (routeParts.size >= 2) {
            (routeParts[0].groupValues[1].toBrazilianDouble() ?: 0.0) +
                (routeParts[1].groupValues[1].toBrazilianDouble() ?: 0.0)
        } else allMinutes.sum()
        val pickup = routeParts.getOrNull(0)?.groupValues?.getOrNull(3).orEmpty().cleanRouteText()
        val dropoff = routeParts.getOrNull(1)?.groupValues?.getOrNull(3).orEmpty().cleanRouteText()
        val totalKm = kmBusca + kmViagem
        if (totalKm <= 0.0 || tempoTotal <= 0.0) {
            val signature = "incompleto:$fare:${screenText.hashCode()}"
            if (shouldAnnounce(signature)) {
                val warning = "Oferta detectada, mas não consegui ler todos os quilômetros ou o tempo. Confira os dados antes de decidir."
                OverlayManager.show(this, warning, OverlayManager.WARNING)
                speak(warning)
            }
            return
        }

        val gasPrice = settings.getFloat(Prefs.GAS, 6.20f).toDouble()
        val consumption = max(0.1, settings.getFloat(Prefs.CONSUMO, 12f).toDouble())
        val minimumHourly = settings.getFloat(Prefs.MIN_HORA, 25f).toDouble()
        val goodHourly = settings.getFloat(Prefs.BOA_HORA, 35f).toDouble()
        val goodPerKm = settings.getFloat(Prefs.MIN_KM, 2f).toDouble()
        val fuelCost = totalKm * gasPrice / consumption
        val net = fare - fuelCost
        val grossHour = fare / (tempoTotal / 60.0)
        val grossMinute = fare / tempoTotal
        val grossKm = fare / totalKm
        val netHour = net / (tempoTotal / 60.0)
        val netMinute = net / tempoTotal
        val netKm = net / totalKm

        // A recomendação prioriza o que sobra após combustível; também mostra os indicadores brutos.
        val status = when {
            net <= 0.0 || netHour < minimumHourly -> OverlayManager.BAD
            netHour >= goodHourly && netKm >= goodPerKm -> OverlayManager.GOOD
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
${fare.money()}  •  Buscar ${kmBusca.oneDecimal()} km + viagem ${kmViagem.oneDecimal()} km
Total ${totalKm.oneDecimal()} km  •  ${tempoTotal.oneDecimal()} min
Lucro após combustível: ${net.money()}
Bruto: ${grossHour.money()}/h • ${grossMinute.money()}/min • ${grossKm.money()}/km
Líquido: ${netHour.money()}/h • ${netMinute.money()}/min • ${netKm.money()}/km"""
        OverlayManager.show(this, details, status)
        val spoken = when (status) {
            OverlayManager.GOOD -> "Corrida boa. Aceitar."
            OverlayManager.MAYBE -> "Corrida média. Avaliar."
            else -> "Corrida ruim. Não aceitar."
        }
        speak(spoken)
    }

    private fun shouldAnnounce(signature: String): Boolean {
        val now = System.currentTimeMillis()
        if (signature == lastSignature && now - lastAnnouncedAt < 180_000) return false
        lastSignature = signature
        lastAnnouncedAt = now
        return true
    }

    private fun speak(message: String) {
        if (ttsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "corrida-$lastAnnouncedAt")
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

    override fun onInterrupt() { tts?.stop() }
    override fun onDestroy() {
        OverlayManager.hide(this)
        tts?.stop()
        tts?.shutdown()
        tts = null
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
