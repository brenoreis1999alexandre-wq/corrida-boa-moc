package com.moc.corridaboa

import java.util.Locale

/** Parses only the visible Uber Driver text; never uses external driver data or inferred heat. */
internal object UberDemandSignal {
    fun summarize(text: String): String? {
        val normalized = text.lowercase(Locale("pt", "BR"))
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return null
        val hasHeatMarker = listOf("tendências de ganhos", "tendencia de ganhos", "tempo de espera", "ganhos dinâmicos", "ganhos dinamicos")
            .any { normalized.contains(it) }
        if (!hasHeatMarker) return null
        val arrows = Regex("[↑^]{1,4}").findAll(text).map { it.value.length }.maxOrNull()
        val zone = Regex("(?:área|area|região|regiao)\\s+([\\p{L}][\\p{L} -]{2,40})", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.trim()
        val level = when {
            arrows != null && arrows >= 3 -> "sinal de tendência mais forte no mapa"
            arrows != null && arrows == 2 -> "sinal de tendência intermediário no mapa"
            arrows == 1 -> "sinal de tendência leve no mapa"
            else -> "tendência visível no mapa da Uber"
        }
        return buildString {
            append("Uber: ")
            if (!zone.isNullOrBlank()) append(zone).append(" — ")
            append(level).append(". É uma previsão do próprio mapa, não garantia de chamada ou ganho.")
        }
    }
}
