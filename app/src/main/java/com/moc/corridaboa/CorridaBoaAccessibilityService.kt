package com.moc.corridaboa

import android.accessibilityservice.AccessibilityService
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import kotlin.math.max

class CorridaBoaAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSignature = ""
    private var lastAnnouncedAt = 0L
    private val supportedPackages = setOf(
        "com.ubercab.driver", "com.d99.android.driver", "com.99Taxis.driver", "com.didi.driver"
    )
    private val settings by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale("pt", "BR")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in supportedPackages) {
            lastSignature = ""
            return
        }
        val root = rootInActiveWindow ?: return
        val screenText = collectText(root).replace('
', ' ').replace(Regex("\s+"), " ").trim()
        val price = PRICE.find(screenText)?.groupValues?.getOrNull(1)?.toBrazilianDouble() ?: return
        val distances = KM.findAll(screenText).mapNotNull { it.groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull() }.toList()
        val minutes = MINUTES.findAll(screenText).mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull() }.take(2).toList()
        if (distances.isEmpty() || minutes.isEmpty()) {
            val sig = "incompleto:$price:${screenText.hashCode()}"
            if (shouldAnnounce(sig)) {
                val warning = "Oferta detectada, mas não consegui ler todos os quilômetros ou o tempo. Confira os dados antes de decidir."
                OverlayManager.show(this, warning, OverlayManager.WARNING)
                speak(warning)
            }
            return
        }

        // Quando só aparece uma distância, ela é tratada como total; não inventamos km de busca.
        val kmBusca = if (distances.size >= 2) distances.first() else 0.0
        val kmViagem = if (distances.size >= 2) distances[1] else distances.first()
        val kmTotal = kmBusca + kmViagem
        val tempoTotal = if (minutes.size >= 2) minutes.sum() else minutes.first()
        if (kmTotal <= 0.0 || tempoTotal <= 0.0) return

        val gasPrice = settings.getFloat(Prefs.GAS, 6.20f).toDouble()
        val consumption = max(0.1, settings.getFloat(Prefs.CONSUMO, 12f).toDouble())
        val minimumHourly = settings.getFloat(Prefs.MIN_HORA, 25f).toDouble()
        val goodHourly = settings.getFloat(Prefs.BOA_HORA, 35f).toDouble()
        val goodPerKm = settings.getFloat(Prefs.MIN_KM, 2f).toDouble()
        val fuelCost = kmTotal * gasPrice / consumption
        val net = price - fuelCost
        val hourly = net / (tempoTotal / 60.0)
        val perMinute = net / tempoTotal
        val perKm = net / kmTotal
        val status = when {
            net <= 0.0 || hourly < minimumHourly -> OverlayManager.BAD
            hourly >= goodHourly && perKm >= goodPerKm -> OverlayManager.GOOD
            else -> OverlayManager.MAYBE
        }
        val signature = listOf(price, kmBusca, kmViagem, tempoTotal).joinToString("|")
        if (!shouldAnnounce(signature)) return

        val title = when (status) {
            OverlayManager.GOOD -> "CORRIDA BOA — PEGAR"
            OverlayManager.MAYBE -> "COMPENSA — AVALIE"
            else -> "CORRIDA RUIM — NÃO ACEITAR"
        }
        val details = "$title
Oferta: ${price.money()}
Buscar: ${kmBusca.oneDecimal()} km  •  Viagem: ${kmViagem.oneDecimal()} km
Total: ${kmTotal.oneDecimal()} km  •  Tempo: ${tempoTotal.oneDecimal()} min
Combustível estimado: ${fuelCost.money()}
Líquido estimado: ${net.money()}
${hourly.money()}/h  •  ${perMinute.money()}/min  •  ${perKm.money()}/km"
        OverlayManager.show(this, details, status)
        val spoken = when (status) {
            OverlayManager.GOOD -> "Corrida boa. Pegar corrida."
            OverlayManager.MAYBE -> "Corrida pode compensar. Avalie."
            else -> "Corrida ruim. Não aceitar."
        }
        speak(spoken)
    }

    private fun shouldAnnounce(signature: String): Boolean {
        val now = System.currentTimeMillis()
        if (signature == lastSignature && now - lastAnnouncedAt < 45_000) return false
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
        private val PRICE = Regex("R\$\s*([0-9]{1,3}(?:\.[0-9]{3})*,[0-9]{2}|[0-9]+,[0-9]{2}|[0-9]+(?:\.[0-9]{2})?)", RegexOption.IGNORE_CASE)
        private val KM = Regex("([0-9]+(?:[.,][0-9]+)?)\s?km", RegexOption.IGNORE_CASE)
        private val MINUTES = Regex("([0-9]+(?:[.,][0-9]+)?)\s?min", RegexOption.IGNORE_CASE)
    }
}

private fun String.toBrazilianDouble(): Double? = replace(".", "").replace(',', '.').toDoubleOrNull()
private fun Double.money(): String = "R$ " + String.format(Locale("pt", "BR"), "%.2f", this)
private fun Double.oneDecimal(): String = String.format(Locale("pt", "BR"), "%.1f", this)
