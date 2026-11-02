package com.moc.corridaboa

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/** Transparent, offline local heuristics; never presents priors as measured demand or probabilities. */
internal object LocalContextAdvisor {
    data class Result(val area: String, val holiday: String?, val caution: String?, val favorable: String?)

    /** User-provided local prior: a positive, borderline offer leaving/starting in Independência in the morning may be preferable to remaining idle. */
    fun favorableMorningPickup(pickup: String, currentArea: String, hour: Int): Boolean {
        if (hour !in 6..10) return false
        return listOf(pickup, currentArea).any {
            normalize(it).contains("independencia")
        }
    }

    /** Only a positive, borderline offer can be tipped by this explicitly supplied local prior. */
    fun shouldLeanTakeBorderline(profit: Double, borderline: Boolean, pickup: String,
                                 currentArea: String, hour: Int, favorableDestination: Boolean): Boolean =
        profit > 0.0 && borderline && (favorableDestination || favorableMorningPickup(pickup, currentArea, hour))

    fun pickupPriorReason(pickup: String, currentArea: String, hour: Int): String? =
        if (favorableMorningPickup(pickup, currentArea, hour)) "Independência de manhã (prior local informado)" else null

    fun destinationPriorReason(favorableDestination: Boolean): String? =
        if (favorableDestination) "Destino Independência de manhã (prior local informado)" else null

    fun areaFromAddress(address: String): String {
        val value = normalize(address)
        // Street-level aliases are deliberately limited to the user's supplied examples.
        if (value.contains("rua benedito teixeira")) return "Planalto"
        if (value.contains("villetfort")) return "Jaraguá"
        if (value.contains("magalhaes pinto") && Regex("\\b3789\\b").containsMatchIn(value)) return "Jaraguá"
        val neighborhoods = listOf(
            "Independência", "Planalto", "Jaraguá", "Centro", "Major Prates", "Ibituruna",
            "São José", "Unimontes", "Delfino Magalhães", "Canelas", "Jardim São Luiz",
            "Vila Oliveira", "Todos os Santos", "Maracanã", "Santos Reis", "Renascença",
            "Esplanada", "Monte Alegre", "Alice Maia", "Morrinhos", "Vera Cruz", "Santa Rita",
            "Barcelona Park"
        )
        return neighborhoods.firstOrNull { normalize(it).let(value::contains) } ?: "Destino não identificado"
    }

    fun evaluate(destination: String, currentArea: String, idleMinutes: Long?, kmRate: Double, hour: Int, weekday: Int, now: Calendar): Result {
        val area = areaFromAddress(destination)
        val holiday = brazilianHoliday(now)
        val cautions = mutableListOf<String>()
        val favorable = mutableListOf<String>()
        if (holiday != null) cautions += "feriado: prior local cauteloso"
        if (weekday == Calendar.TUESDAY) cautions += "terça: prior local fraco"
        if (weekday == Calendar.WEDNESDAY) cautions += "quarta: prior local muito fraco"
        if (area == "Centro" && hour < 9) cautions += "Centro antes das 9h: prior local desfavorável"
        if (area == "Independência" && hour in 6..10) favorable += "Independência de manhã: prior local favorável"
        // Idle duration and GPS are descriptive context only, never treated as demand evidence.
        val where = currentArea.takeIf { it.isNotBlank() && it != "Área não identificada" }
            ?.let { "GPS $it" + (idleMinutes?.let { m -> "; parado ${m} min" } ?: "") }
        val concern = when {
            kmRate < 1.50 -> "R$${String.format(Locale("pt", "BR"), "%.2f", kmRate)}/km abaixo do piso"
            else -> null
        }
        val caution = buildList {
            if (concern != null) add(concern)
            addAll(cautions)
            if (where != null) add(where)
        }.take(2).joinToString(" • ").ifBlank { null }
        return Result(area, holiday, caution, favorable.firstOrNull())
    }

    /** Brazilian national fixed holidays and Good Friday; no server/device calendar dependency. */
    fun brazilianHoliday(date: Calendar): String? {
        val month = date.get(Calendar.MONTH) + 1
        val day = date.get(Calendar.DAY_OF_MONTH)
        val fixed = mapOf(
            (1 to 1) to "Confraternização Universal", (4 to 21) to "Tiradentes",
            (5 to 1) to "Dia do Trabalho", (9 to 7) to "Independência do Brasil",
            (10 to 12) to "Nossa Senhora Aparecida", (11 to 2) to "Finados",
            (11 to 15) to "Proclamação da República", (11 to 20) to "Consciência Negra",
            (12 to 25) to "Natal"
        )
        fixed[month to day]?.let { return it }
        val easter = easterSunday(date.get(Calendar.YEAR))
        val goodFriday = Calendar.getInstance(date.timeZone, Locale("pt", "BR")).apply {
            timeInMillis = easter.timeInMillis
            add(Calendar.DAY_OF_YEAR, -2)
        }
        val carnivalTuesday = Calendar.getInstance(date.timeZone, Locale("pt", "BR")).apply {
            timeInMillis = easter.timeInMillis
            add(Calendar.DAY_OF_YEAR, -47)
        }
        val carnivalMonday = Calendar.getInstance(date.timeZone, Locale("pt", "BR")).apply {
            timeInMillis = easter.timeInMillis
            add(Calendar.DAY_OF_YEAR, -48)
        }
        val corpusChristi = Calendar.getInstance(date.timeZone, Locale("pt", "BR")).apply {
            timeInMillis = easter.timeInMillis
            add(Calendar.DAY_OF_YEAR, 60)
        }
        fun sameDay(other: Calendar) = month == other.get(Calendar.MONTH) + 1 && day == other.get(Calendar.DAY_OF_MONTH)
        return when {
            sameDay(goodFriday) -> "Sexta-feira Santa"
            sameDay(carnivalMonday) || sameDay(carnivalTuesday) -> "Carnaval (data móvel)"
            sameDay(corpusChristi) -> "Corpus Christi (data móvel)"
            else -> null
        }
    }

    private fun easterSunday(year: Int): Calendar {
        val a = year % 19; val b = year / 100; val c = year % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return Calendar.getInstance(Locale("pt", "BR")).apply {
            clear(); set(year, month - 1, day, 12, 0, 0)
        }
    }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
}
