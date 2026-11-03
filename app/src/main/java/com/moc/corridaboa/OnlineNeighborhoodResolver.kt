package com.moc.corridaboa

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.security.MessageDigest

/** Best-effort online address lookup via Android's geocoder; cached locally after successful lookup. */
internal object OnlineNeighborhoodResolver {
    private const val CACHE_PREFIX = "street_neighborhood_v2_"
    private const val FAILURE_PREFIX = "street_neighborhood_fail_v2_"
    private const val FAILURE_COOLDOWN_MS = 6L * 60L * 60L * 1000L
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun cachedArea(context: Context, streetAddress: String): String? {
        manualStreetArea(streetAddress)?.let { return it }
        val key = key(streetAddress)
        return context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .getString(CACHE_PREFIX + key, null)?.takeIf(String::isNotBlank)
    }

    fun resolve(context: Context, streetAddress: String, onResult: (String?) -> Unit) {
        val query = streetAddress.trim()
        if (query.isBlank()) { onResult(null); return }
        manualStreetArea(query)?.let { onResult(it); return }
        val key = key(query)
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        prefs.getString(CACHE_PREFIX + key, null)?.takeIf(String::isNotBlank)?.let { onResult(it); return }
        if (System.currentTimeMillis() - prefs.getLong(FAILURE_PREFIX + key, 0L) < FAILURE_COOLDOWN_MS) { onResult(null); return }
        if (!inFlight.add(key)) return
        val appContext = context.applicationContext
        executor.execute {
            var resolved: String? = null
            try {
                if (Geocoder.isPresent()) {
                    // Uses the device's network-backed geocoding service where available; no Maps SDK or location permission needed.
                    val geocoder = Geocoder(appContext, Locale("pt", "BR"))
                    val results = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        geocoder.getFromLocationName("$query, Montes Claros, Minas Gerais, Brasil", 5)?.toList().orEmpty()
                    } else {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocationName("$query, Montes Claros, Minas Gerais, Brasil", 5).orEmpty()
                    }
                    resolved = results.asSequence()
                        .filter { "montes claros" in normalize(it.locality.orEmpty()) || "montes claros" in normalize(it.subAdminArea.orEmpty()) }
                        .mapNotNull { it.subLocality?.trim()?.takeIf(String::isNotBlank) }
                        .map(::canonicalKnownArea)
                        .firstOrNull()
                }
                if (resolved != null) {
                    prefs.edit().putString(CACHE_PREFIX + key, resolved).remove(FAILURE_PREFIX + key).apply()
                } else {
                    prefs.edit().putLong(FAILURE_PREFIX + key, System.currentTimeMillis()).apply()
                }
            } catch (_: Exception) {
                prefs.edit().putLong(FAILURE_PREFIX + key, System.currentTimeMillis()).apply()
            } finally {
                inFlight.remove(key)
            }
            val result = resolved
            if (result != null) mainHandler.post { onResult(result) }
        }
    }

    /** Two street-to-neighborhood examples explicitly supplied by Breno; not an external map claim. */
    internal fun manualStreetAreaForTest(address: String): String? = manualStreetArea(address)

    private fun manualStreetArea(address: String): String? {
        val first = address.substringBefore(',').trim()
        val street = first.replace(Regex("\\s+[0-9]+.*$"), "")
        return when (normalize(street)) {
            "rua engenheiro veloso" -> "Delfino Magalhães"
            "rua sessenta" -> "Novo Delfino"
            else -> null
        }
    }

    private fun canonicalKnownArea(value: String): String {
        val normalized = normalize(value)
        val known = listOf(
            "Independência", "Major Prates", "São José", "São Judas Tadeu", "Edgar Pereira", "Vila Guilhermina", "Renascença", "Maracanã", "Alto São João", "Alice Maia", "Vila Brasília", "Vila Regina", "Vila Toncheff", "Vila Exposição", "Todos os Santos", "Melo", "João Gordo", "Santos Reis", "Grande Santos Reis", "Santo Antônio", "Santo Expedito", "Santa Rita", "Santa Rita II", "Santo Amaro", "São João", "São Miguel", "São Pedro", "São Lucas", "São Geraldo", "Universitário", "Funcionários", "Lourdes", "Junco", "Kennedy", "Monte Carmelo", "Morada do Parque", "Centro", "Ibituruna", "Jardim São Luiz", "Jardim São Geraldo", "Jardim Olímpico", "Jardim Primavera", "Jardim Brasil", "Jardim Alegre", "Jardim Eldorado", "Jardim Liberdade", "Planalto", "Delfino Magalhães", "Jaraguá", "Vila Oliveira", "Vila Sion", "Vila Ipiranga", "Vila Mauricéia", "Vila Áurea", "Interlagos", "Belvedere", "Bela Vista", "Bela Paisagem", "Boa Vista", "Acácias", "Alcides Rabelo", "Alterosas", "Alto da Boa Vista", "Amazonas", "Antônia Pereira", "Augusta Mota", "Brasilândia", "Camilo Prates", "Cidade Nova", "Cintra", "Clarice Ataíde", "Eldorado", "Esplanada", "Floresta", "Guarujá", "Nova Esperança", "Novo Horizonte", "Panorama", "Parque Pampulha", "Progreso", "Residencial dos Araçás", "Residencial Sul", "Roxo Verde", "Sagrada Família", "Santa Lúcia", "Sumaré", "Tancredo Neves", "Vera Cruz", "Vista Alegre", "Vargem Grande", "Village do Lago", "Conjunto JK", "Conjunto Cristo Rei", "José Carlos de Lima", "João XXIII", "Doutor João Alves", "Chácara Ceres", "Cidade Industrial", "Distrito Industrial", "Canelas", "Carmelo", "Unimontes", "Novo Delfino"
        )
        return known.firstOrNull { normalize(it) == normalized } ?: value.trim()
    }

    private fun key(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(normalize(value).toByteArray(Charsets.UTF_8))
        return bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
