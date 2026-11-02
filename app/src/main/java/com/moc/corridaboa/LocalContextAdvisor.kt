package com.moc.corridaboa

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/** Montes Claros profile seeded from the driver's local notes; heuristics are not measured demand. */
internal object LocalContextAdvisor {
    enum class Level { HIGH, MEDIUM, LOW, UNKNOWN }
    data class Result(val area: String, val holiday: String?, val level: Level, val reason: String?)

    fun areaFromAddress(address: String): String {
        val value = normalize(address)
        val anchors = listOf(
            "Montes Claros Shopping" to "Montes Claros Shopping",
            "Ibituruna Center" to "Ibituruna Center",
            "Aroldo Tourinho" to "Hospitais",
            "Dilson Godinho" to "Hospitais",
            "Santa Casa" to "Hospitais",
            "Rodoviaria" to "Rodoviária/Terminal",
            "terminal rodoviario" to "Rodoviária/Terminal",
            "cemiterio" to "Cemitérios",
            "igreja" to "Igrejas",
            "hospital" to "Hospitais",
            "rodoviaria" to "Rodoviária/Terminal",
            "terminal" to "Rodoviária/Terminal",
            "unimontes" to "Unimontes",
            "campus" to "Unimontes",
            "barcelona park" to "Barcelona Park",
            "rua benedito teixeira" to "Planalto",
            "villetfort" to "Jaraguá",
            "magalhaes pinto" to "Jaraguá"
        )
        anchors.firstOrNull { value.contains(normalize(it.first)) }?.let { return it.second }
        val areas = listOf(
            "Independência", "Major Prates", "Centro", "Ibituruna", "São José", "Unimontes",
            "Delfino Magalhães", "Jardim São Luiz", "Planalto", "Canelas", "Alcides Rabelo",
            "Vila Guilhermina", "São Judas Tadeu", "Edgar Pereira", "Renascença", "Maracanã",
            "Morrinhos", "Jardim São Geraldo", "Jardim Olímpico", "Nova Esperança", "Santos Reis",
            "Jaraguá", "Barcelona Park", "Cemitérios", "Hospitais", "Igrejas", "Rodoviária/Terminal",
            "Montes Claros Shopping", "Ibituruna Center"
        )
        return areas.firstOrNull { normalize(it).let(value::contains) } ?: "Destino não identificado"
    }

    fun level(area: String, hour: Int, minute: Int, weekday: Int, holiday: Boolean): Level {
        val a = normalize(area)
        val t = hour * 60 + minute
        fun inRange(start: Int, end: Int) = t >= start && t < end
        val sunday = weekday == Calendar.SUNDAY
        if (a == normalize("Unimontes")) {
            if (holiday || sunday || weekday == Calendar.SATURDAY) return Level.LOW
            return if (inRange(390, 480) || inRange(690, 810) || inRange(1020, 1170)) Level.HIGH else Level.LOW
        }
        if (a == normalize("Hospitais")) return if (inRange(360, 540) || inRange(1020, 1200) || holiday) Level.HIGH else Level.MEDIUM
        if (a == normalize("Rodoviária/Terminal")) return if (inRange(300, 480) || inRange(1080, 1320)) Level.HIGH else Level.MEDIUM
        if (a == normalize("Cemitérios")) return if (holiday || inRange(600, 1080)) Level.MEDIUM else Level.UNKNOWN
        if (a == normalize("Igrejas")) return if (holiday || sunday) Level.MEDIUM else Level.UNKNOWN
        if (a == normalize("Montes Claros Shopping") || a == normalize("Ibituruna Center")) {
            return when {
                holiday -> Level.LOW
                inRange(1020, 1320) -> Level.HIGH
                inRange(660, 960) -> Level.MEDIUM
                else -> Level.LOW
            }
        }
        if (a == normalize("Ibituruna")) return when {
            inRange(420, 540) || inRange(1020, 1320) -> Level.HIGH
            inRange(720, 1020) -> Level.MEDIUM
            else -> Level.LOW
        }
        if (a == normalize("Independência")) return when {
            inRange(300, 540) -> Level.HIGH
            inRange(1020, 1140) -> Level.MEDIUM
            inRange(840, 960) || t >= 1320 -> Level.LOW
            else -> Level.MEDIUM
        }
        if (a == normalize("Centro")) return when {
            sunday -> Level.LOW
            holiday -> if (inRange(600, 1080)) Level.MEDIUM else Level.LOW
            inRange(300, 420) || t >= 1320 -> Level.LOW
            inRange(480, 720) || inRange(780, 1080) -> Level.HIGH
            inRange(1140, 1260) -> Level.MEDIUM
            else -> Level.LOW
        }
        if (a == normalize("Major Prates") || a == normalize("São José")) return if (inRange(360, 540) || inRange(1020, 1200)) Level.HIGH else Level.MEDIUM
        if (a == normalize("Delfino Magalhães")) return when { inRange(840, 960) -> Level.LOW; inRange(420, 540) || inRange(1020, 1140) -> Level.MEDIUM; else -> Level.MEDIUM }
        if (a == normalize("Jardim São Luiz") || a == normalize("Planalto")) return when { inRange(840, 960) -> Level.LOW; inRange(360, 540) || inRange(1020, 1140) -> Level.MEDIUM; else -> Level.LOW }
        if (a == normalize("Canelas")) return if (inRange(420, 480) || inRange(1020, 1080)) Level.MEDIUM else Level.LOW
        if (a == normalize("Alcides Rabelo")) return if (inRange(420, 540)) Level.MEDIUM else Level.LOW
        if (a == normalize("Vila Guilhermina")) return if (inRange(360, 540) || inRange(1020, 1140)) Level.MEDIUM else Level.LOW
        if (a == normalize("São Judas Tadeu") || a == normalize("Edgar Pereira")) return if (inRange(420, 540)) Level.MEDIUM else Level.LOW
        if (a == normalize("Renascença") || a == normalize("Maracanã")) return if (inRange(420, 540) || inRange(1020, 1200)) Level.MEDIUM else Level.LOW
        if (a == normalize("Morrinhos")) return if (inRange(1020, 1200)) Level.MEDIUM else Level.LOW
        if (a == normalize("Jardim São Geraldo") || a == normalize("Jardim Olímpico")) return if (inRange(1020, 1140)) Level.MEDIUM else Level.LOW
        if (a == normalize("Nova Esperança") || a == normalize("Santos Reis")) return if (inRange(360, 540)) Level.MEDIUM else Level.LOW
        if (a == normalize("Jaraguá") || a == normalize("Barcelona Park")) return if (inRange(360, 540) || inRange(1020, 1200)) Level.MEDIUM else Level.LOW
        return Level.UNKNOWN
    }

    fun evaluate(destination: String, hour: Int, minute: Int, weekday: Int, now: Calendar): Result {
        val area = areaFromAddress(destination)
        val holiday = holidayName(now)
        val profile = level(area, hour, minute, weekday, holiday != null)
        val reason = when (profile) {
            Level.HIGH -> "Perfil local informado: faixa forte"
            Level.MEDIUM -> "Perfil local informado: faixa média"
            Level.LOW -> "Perfil local informado: faixa fraca"
            Level.UNKNOWN -> null
        }
        return Result(area, holiday, profile, reason)
    }

    fun holidayName(date: Calendar): String? {
        val month = date.get(Calendar.MONTH) + 1
        val day = date.get(Calendar.DAY_OF_MONTH)
        val local = mapOf((7 to 3) to "Aniversário de Montes Claros", (12 to 8) to "Imaculada Conceição (Montes Claros)")
        local[month to day]?.let { return it }
        val fixed = mapOf((1 to 1) to "Confraternização Universal", (4 to 21) to "Tiradentes", (5 to 1) to "Dia do Trabalho", (9 to 7) to "Independência do Brasil", (10 to 12) to "Nossa Senhora Aparecida", (11 to 2) to "Finados", (11 to 15) to "Proclamação da República", (11 to 20) to "Consciência Negra", (12 to 25) to "Natal")
        fixed[month to day]?.let { return it }
        val easter = easterSunday(date.get(Calendar.YEAR))
        fun shifted(days: Int) = Calendar.getInstance(date.timeZone, Locale("pt", "BR")).apply { timeInMillis = easter.timeInMillis; add(Calendar.DAY_OF_YEAR, days) }
        fun same(c: Calendar) = month == c.get(Calendar.MONTH) + 1 && day == c.get(Calendar.DAY_OF_MONTH)
        return when {
            same(shifted(-2)) -> "Sexta-feira Santa"
            same(shifted(-48)) || same(shifted(-47)) -> "Carnaval (data móvel)"
            same(shifted(60)) -> "Corpus Christi (data móvel)"
            else -> null
        }
    }

    private fun easterSunday(year: Int): Calendar {
        val a=year%19; val b=year/100; val c=year%100; val d=b/4; val e=b%4; val f=(b+8)/25; val g=(b-f+1)/3
        val h=(19*a+b-d-g+15)%30; val i=c/4; val k=c%4; val l=(32+2*e+2*i-h-k)%7; val m=(a+11*h+22*l)/451
        val month=(h+l-7*m+114)/31; val day=(h+l-7*m+114)%31+1
        return Calendar.getInstance(Locale("pt", "BR")).apply { clear(); set(year,month-1,day,12,0,0) }
    }
    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
}
