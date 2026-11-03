package com.moc.corridaboa

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/** Montes Claros local reference supplied by the driver; it is not measured demand. */
internal object LocalContextAdvisor {
    enum class Level { HIGH, MEDIUM, LOW, UNKNOWN }
    data class Result(val area: String, val holiday: String?, val level: Level, val reason: String?)

    private fun norm(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val group1Names = "Independência|Major Prates|São José|São Judas Tadeu|Edgar Pereira|Vila Guilhermina|Renascença|Maracanã|Alto São João|Alice Maia|Vila Brasília|Vila Regina|Vila Toncheff|Vila Exposição|Todos os Santos|Melo|João Gordo|Santos Reis|Grande Santos Reis|Santo Antônio|Santo Expedito|Santa Rita|Santa Rita II|Santo Amaro|São João|São Miguel|São Pedro|São Lucas|São Geraldo|Universitário|Funcionários|Lourdes|Junco|Kennedy|Monte Carmelo|Morada do Parque".split('|')
    private val group2Names = "Centro|Todos os Santos|Vila Brasília|Funcionários|Melo|Edgar Pereira|São José|Santo Antônio|Morada do Parque|Ibituruna|Jardim São Luiz|Jardim São Geraldo|Jardim Olímpico|Jardim Primavera|Jardim Brasil|Jardim Alegre|Jardim Eldorado|Jardim Liberdade".split('|')
    private val group3Names = "Ibituruna|Major Prates|São José|Centro|Jardim São Luiz|Jardim São Geraldo|Morada do Parque|Planalto|Delfino Magalhães|Jaraguá|Vila Oliveira|Vila Sion|Vila Ipiranga|Vila Mauricéia|Vila Áurea|Interlagos|Belvedere|Bela Vista|Bela Paisagem|Boa Vista|Acácias|Alcides Rabelo|Alterosas|Alto da Boa Vista|Amazonas|Antônia Pereira|Augusta Mota|Brasilândia|Camilo Prates|Cidade Nova|Cintra|Clarice Ataíde|Eldorado|Esplanada|Floresta|Guarujá|Nova Esperança|Novo Horizonte|Panorama|Parque Pampulha|Progreso|Residencial dos Araçás|Residencial Sul|Roxo Verde|Sagrada Família|Santa Lúcia|Sumaré|Tancredo Neves|Vera Cruz|Vista Alegre|Vargem Grande|Village do Lago|Conjunto JK|Conjunto Residencial JK|Conjunto Cristo Rei|José Carlos de Lima|João XXIII|Doutor João Alves|Chácara Ceres|Cidade Industrial|Distrito Industrial|Caic|Cândida Câmara|Carmelo".split('|')
    private val group1 = group1Names.map(::norm).toSet()
    private val group2 = group2Names.map(::norm).toSet()
    private val group3 = group3Names.map(::norm).toSet()
    private val group5 = listOf("Canelas", "Distrito Industrial", "Cidade Industrial", "Vargem Grande", "Chácara Ceres", "Village do Lago").map(::norm).toSet()
    private val canonicalNames = (group1Names + group2Names + group3Names + listOf("Unimontes", "Universitário", "Rodoviária", "Hospitais", "Shoppings")).associateBy(::norm)

    /** Uber/99: use the first meaningful address segment after the street; skip street number/city suffix. */
    fun areaFromAddress(address: String): String {
        val parts = address.split(',').map(String::trim).filter(String::isNotBlank)
        fun isNumber(value: String): Boolean = norm(value).matches(Regex("[0-9]+(?: [a-z0-9 ]*)?")) || norm(value) in setOf("sn", "s n", "no")
        val candidates = parts.drop(1).filterNot { part ->
            val n = norm(part)
            n in setOf("montes claros", "mg", "minas gerais", "brasil", "brazil") || n.startsWith("montes claros ") || isNumber(part)
        }
        val candidate = candidates.firstOrNull() ?: parts.firstOrNull().orEmpty()
        val n = norm(candidate)
        val aliases = listOf(
            "conjunto residencial jk" to "Conjunto Residencial JK", "conjunto habitacional jk" to "Conjunto Residencial JK",
            "conjunto jk" to "Conjunto JK", "conjunto cristo rei" to "Conjunto Cristo Rei",
            "unimontes" to "Unimontes", "universitario" to "Universitário", "universidade estadual" to "Unimontes",
            "rodoviaria" to "Rodoviária", "terminal rodoviario" to "Rodoviária", "terminal" to "Rodoviária",
            "santa casa" to "Hospitais", "aroldo tourinho" to "Hospitais", "dilson godinho" to "Hospitais", "hospital" to "Hospitais",
            "montes claros shopping" to "Shoppings", "ibituruna center" to "Shoppings", "shopping" to "Shoppings"
        )
        aliases.firstOrNull { n == norm(it.first) || n.contains(norm(it.first)) }?.let { return it.second }
        val allNames = (group1Names + group2Names + group3Names + listOf("Canelas", "Distrito Industrial", "Cidade Industrial", "Vargem Grande", "Chácara Ceres", "Village do Lago", "Unimontes", "Universitário", "Rodoviária", "Hospitais", "Shoppings")).distinct()
        allNames.firstOrNull { norm(it) == n }?.let { return canonicalNames[norm(it)] ?: it }
        allNames.sortedByDescending { norm(it).length }.firstOrNull { n.contains(norm(it)) }?.let { return canonicalNames[norm(it)] ?: it }
        return candidate.replace(Regex("\\s+"), " ").trim().ifBlank { "Destino não identificado" }
    }

    fun centerMinimumRate(hour: Int): Double = if (hour < 10) 2.50 else 2.00
    fun centerBlocked(destinationArea: String, hour: Int, minute: Int, grossPerKm: Double): Boolean =
        norm(destinationArea) == "centro" && grossPerKm + 1e-9 < centerMinimumRate(hour)
    /** Compatibility helper for earlier tests; actual recommendation uses rate threshold. */
    fun centerBlocked(destinationArea: String, hour: Int, minute: Int): Boolean =
        norm(destinationArea) == "centro" && (hour < 10 || (hour == 10 && minute < 30))

    fun level(area: String, hour: Int, minute: Int, weekday: Int, holiday: Boolean): Level {
        val a = norm(area); val t = hour * 60 + minute
        fun between(start: Int, end: Int) = t >= start && t < end
        val weekend = weekday == Calendar.SATURDAY || weekday == Calendar.SUNDAY
        val dead = between(14 * 60, 16 * 60) || t >= 22 * 60 + 30 || t < 4 * 60 + 30
        if (a == "unimontes" || a == "universitario") {
            if (holiday || weekend) return Level.LOW
            return if (between(390, 480) || between(690, 810) || between(1020, 1170)) Level.HIGH else Level.LOW
        }
        if (a == "rodoviaria") return if (!holiday && (between(300, 480) || between(1080, 1320))) Level.HIGH else if (holiday) Level.MEDIUM else Level.LOW
        if (a == "hospitais") return if (!holiday && (between(360, 540) || between(1020, 1200))) Level.HIGH else if (holiday) Level.MEDIUM else Level.LOW
        if (a == "shoppings") return if (!holiday && between(1020, 1320)) Level.HIGH else if (holiday) Level.MEDIUM else if (between(660, 960)) Level.MEDIUM else Level.LOW
        if (dead) return Level.LOW
        if (holiday) {
            if (a == "centro") return Level.LOW
            return if (a in group1 || a in group2 || a in group3) Level.MEDIUM else Level.LOW
        }
        if (a in group5) return Level.LOW
        if (a == "centro") return when {
            hour < 10 -> Level.LOW
            between(630, 720) || between(780, 1080) -> Level.HIGH
            else -> Level.MEDIUM
        }
        if (a == "independencia" && between(300, 540)) return Level.HIGH
        if (a == "delfino magalhaes") return when {
            between(420, 540) -> Level.HIGH
            between(840, 960) -> Level.LOW
            between(1020, 1140) -> Level.HIGH
            a in group3 -> Level.MEDIUM
            else -> Level.UNKNOWN
        }
        if (a == "jaragua" || a == "planalto") return when {
            between(420, 540) || between(1020, 1140) -> Level.HIGH
            a in group3 -> Level.MEDIUM
            else -> Level.UNKNOWN
        }
        if (a in group1 && between(300, 540)) return Level.HIGH
        if (a in group2 && (between(630, 720) || between(780, 1080))) return Level.HIGH
        if (a in group3 && between(1020, 1320)) return Level.HIGH
        if (a in group1 || a in group2 || a in group3) return Level.LOW
        return Level.UNKNOWN
    }

    fun evaluate(destination: String, hour: Int, minute: Int, weekday: Int, now: Calendar): Result {
        val area = areaFromAddress(destination); val holiday = holidayName(now)
        val profile = level(area, hour, minute, weekday, holiday != null)
        val reason = when (profile) {
            Level.HIGH -> "Faixa forte do perfil local informado"
            Level.MEDIUM -> "Faixa média no dia fraco (perfil local informado)"
            Level.LOW -> "Faixa fraca do perfil local informado"
            Level.UNKNOWN -> null
        }
        return Result(area, holiday, profile, reason)
    }

    fun holidayName(date: Calendar): String? {
        val month = date.get(Calendar.MONTH) + 1; val day = date.get(Calendar.DAY_OF_MONTH)
        mapOf((7 to 3) to "Aniversário de Montes Claros", (12 to 8) to "Imaculada Conceição (Montes Claros)")[month to day]?.let { return it }
        mapOf((1 to 1) to "Confraternização Universal", (4 to 21) to "Tiradentes", (5 to 1) to "Dia do Trabalho", (9 to 7) to "Independência do Brasil", (10 to 12) to "Nossa Senhora Aparecida", (11 to 2) to "Finados", (11 to 15) to "Proclamação da República", (11 to 20) to "Consciência Negra", (12 to 25) to "Natal")[month to day]?.let { return it }
        val easter = easterSunday(date.get(Calendar.YEAR))
        fun shifted(days: Int) = Calendar.getInstance(date.timeZone, Locale("pt", "BR")).apply { timeInMillis = easter.timeInMillis; add(Calendar.DAY_OF_YEAR, days) }
        fun same(c: Calendar) = month == c.get(Calendar.MONTH) + 1 && day == c.get(Calendar.DAY_OF_MONTH)
        return when { same(shifted(-2)) -> "Sexta-feira Santa"; same(shifted(-48)) || same(shifted(-47)) -> "Carnaval (data móvel)"; same(shifted(60)) -> "Corpus Christi (data móvel)"; else -> null }
    }

    private fun easterSunday(year: Int): Calendar {
        val a=year%19; val b=year/100; val c=year%100; val d=b/4; val e=b%4; val f=(b+8)/25; val g=(b-f+1)/3
        val h=(19*a+b-d-g+15)%30; val i=c/4; val k=c%4; val l=(32+2*e+2*i-h-k)%7; val m=(a+11*h+22*l)/451
        val month=(h+l-7*m+114)/31; val day=(h+l-7*m+114)%31+1
        return Calendar.getInstance(Locale("pt", "BR")).apply { clear(); set(year,month-1,day,12,0,0) }
    }
}
