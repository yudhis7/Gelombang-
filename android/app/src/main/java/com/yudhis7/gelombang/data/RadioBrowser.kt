package com.yudhis7.gelombang.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Client for the Radio Browser community directory (https://www.radio-browser.info).
 * Picks a mirror, fails over between mirrors, and caches responses for a while.
 */
class RadioBrowser(regionCodes: Collection<String>) {

    private val region = regionCodes.map { it.uppercase() }.toSet()
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    @Volatile private var servers: List<String>? = null
    @Volatile private var current = 0

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Radio directory answered $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun discover(): List<String> {
        val hosts = try {
            JSONArray(httpGet("https://all.api.radio-browser.info/json/servers")).objects()
                .map { it.str("name") }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
        return hosts.ifEmpty { FALLBACK_HOSTS }.distinct().shuffled().map { "https://$it" }
    }

    private suspend fun request(path: String, params: Map<String, Any?>, ttlMs: Long = TTL_LIST): String =
        withContext(Dispatchers.IO) {
            val qs = params.filterValues { it != null && it.toString().isNotEmpty() }
                .map { (k, v) -> "$k=" + URLEncoder.encode(v.toString(), "UTF-8") }
                .joinToString("&")
            val rel = if (qs.isEmpty()) path else "$path?$qs"
            val hit = cache[rel]
            if (hit != null && System.currentTimeMillis() - hit.first < ttlMs) return@withContext hit.second

            val list = servers ?: discover().also { servers = it }
            var lastError: Exception? = null
            for (i in list.indices) {
                val idx = (current + i) % list.size
                try {
                    val body = httpGet(list[idx] + rel)
                    current = idx
                    cache[rel] = System.currentTimeMillis() to body
                    return@withContext body
                } catch (e: Exception) {
                    lastError = e
                }
            }
            servers = null
            hit?.second ?: throw (lastError ?: IOException("Radio directory unreachable"))
        }

    private fun parseStations(body: String): List<Station> =
        JSONArray(body).objects().mapNotNull { Station.fromApi(it) }

    /** Station counts per country in the region. */
    suspend fun countryCounts(): Map<String, Int> {
        val out = HashMap<String, Int>()
        try {
            for (o in JSONArray(request("/json/countrycodes", mapOf("hidebroken" to true), TTL_COUNTS)).objects()) {
                val code = o.str("name").uppercase()
                if (code in region) out[code] = (out[code] ?: 0) + o.optInt("stationcount", 0)
            }
        } catch (e: Exception) {
            for (o in JSONArray(request("/json/countries", mapOf("hidebroken" to true), TTL_COUNTS)).objects()) {
                val code = o.str("iso_3166_1").uppercase()
                if (code in region) out[code] = (out[code] ?: 0) + o.optInt("stationcount", 0)
            }
        }
        return out
    }

    suspend fun stationsByCountry(
        code: String,
        offset: Int = 0,
        limit: Int = 100,
        sort: Sort = Sort.POPULAR,
        tag: String? = null,
    ): List<Station> = parseStations(
        request(
            "/json/stations/search",
            mapOf(
                "countrycode" to code,
                "hidebroken" to true,
                "order" to sort.order,
                "reverse" to sort.reverse,
                "offset" to offset,
                "limit" to limit,
                "tag" to tag,
            ),
        ),
    )

    /** Most popular stations across several countries, merged. */
    suspend fun topAcross(codes: List<String>, perCountry: Int = 15): List<Station> = coroutineScope {
        val results = codes.chunked(5).flatMap { chunk ->
            chunk.map { code -> async { runCatching { stationsByCountry(code, limit = perCountry) } } }.awaitAll()
        }
        if (results.isNotEmpty() && results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        results.flatMap { it.getOrNull().orEmpty() }
            .distinctBy { it.uuid }
            .sortedByDescending { it.clickCount }
    }

    /** Search by name and by genre tag, within the region or within one country. */
    suspend fun search(query: String, countryCode: String? = null): List<Station> = coroutineScope {
        val q = query.trim()
        if (q.length < 2) return@coroutineScope emptyList()
        val base = mutableMapOf<String, Any?>("hidebroken" to true, "order" to "clickcount", "reverse" to true)
        if (countryCode != null) {
            base["countrycode"] = countryCode
            base["limit"] = 300
        } else {
            base["limit"] = 1000
        }
        val byName = async { runCatching { parseStations(request("/json/stations/search", base + ("name" to q))) } }
        val byTag = async { runCatching { parseStations(request("/json/stations/search", base + ("tag" to q.lowercase()))) } }
        val a = byName.await()
        val b = byTag.await()
        if (a.isFailure && b.isFailure) throw a.exceptionOrNull()!!
        (a.getOrNull().orEmpty() + b.getOrNull().orEmpty())
            .distinctBy { it.uuid }
            .filter { countryCode != null || it.countryCode in region }
            .sortedByDescending { it.clickCount }
            .take(300)
    }

    suspend fun byUuids(uuids: List<String>): List<Station> =
        uuids.filter { it.isNotBlank() }.chunked(50).flatMap { chunk ->
            parseStations(request("/json/stations/byuuid", mapOf("uuids" to chunk.joinToString(",")), 60_000))
        }

    /** Tells the directory a station was played (helps its popularity ranking). Best effort. */
    suspend fun click(uuid: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val list = servers ?: discover().also { servers = it }
                httpGet(list[current % list.size] + "/json/url/" + URLEncoder.encode(uuid, "UTF-8"))
            }
        }
    }

    enum class Sort(val label: String, val order: String, val reverse: Boolean) {
        POPULAR("Most popular", "clickcount", true),
        TRENDING("Trending now", "clicktrend", true),
        VOTES("Most liked", "votes", true),
        NAME("Name A–Z", "name", false),
        BITRATE("Best quality", "bitrate", true);

        fun comparator(): Comparator<Station> = when (this) {
            POPULAR -> compareByDescending { it.clickCount }
            TRENDING -> compareByDescending { it.clickTrend }
            VOTES -> compareByDescending { it.votes }
            NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            BITRATE -> compareByDescending { it.bitrate }
        }
    }

    companion object {
        const val USER_AGENT = "GelombangRadio-Android/1.0"
        private const val TTL_LIST = 15 * 60 * 1000L
        private const val TTL_COUNTS = 6 * 60 * 60 * 1000L
        private val FALLBACK_HOSTS = listOf(
            "de1.api.radio-browser.info",
            "de2.api.radio-browser.info",
            "fi1.api.radio-browser.info",
            "nl1.api.radio-browser.info",
            "at1.api.radio-browser.info",
        )
    }
}
