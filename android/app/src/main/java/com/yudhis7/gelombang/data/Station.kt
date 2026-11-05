package com.yudhis7.gelombang.data

import org.json.JSONArray
import org.json.JSONObject

/** One radio station. JSON field names match the desktop app's backups. */
data class Station(
    val uuid: String,
    val name: String,
    val url: String,
    val homepage: String = "",
    val favicon: String = "",
    val tags: List<String> = emptyList(),
    val country: String = "",
    val countryCode: String = "",
    val state: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Boolean = false,
    val votes: Int = 0,
    val clickCount: Int = 0,
    val clickTrend: Int = 0,
    val custom: Boolean = false,
) {
    val tech: String
        get() = listOfNotNull(codec.ifBlank { null }, if (bitrate > 0) "$bitrate kbps" else null).joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("uuid", uuid)
        put("name", name)
        put("url", url)
        put("homepage", homepage)
        put("favicon", favicon)
        put("tags", JSONArray(tags))
        put("country", country)
        put("countrycode", countryCode)
        put("state", state)
        put("codec", codec)
        put("bitrate", bitrate)
        put("hls", hls)
        put("votes", votes)
        put("clickcount", clickCount)
        put("clicktrend", clickTrend)
        put("custom", custom)
    }

    companion object {
        private val HLS_RE = Regex("""\.m3u8(\?|$)""", RegexOption.IGNORE_CASE)
        private val SPACES = Regex("""\s+""")

        fun isHlsUrl(url: String) = HLS_RE.containsMatchIn(url)

        fun newCustomId(): String = "custom-" + System.currentTimeMillis().toString(36)

        /** A station saved by this app, or exported by the desktop app. */
        fun fromJson(o: JSONObject): Station? {
            val url = o.str("url").trim()
            if (url.isEmpty()) return null
            val tags = when (val t = o.opt("tags")) {
                is JSONArray -> (0 until t.length()).map { t.optString(it) }
                is String -> t.split(',')
                else -> emptyList()
            }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val custom = o.optBoolean("custom", false)
            return Station(
                uuid = o.str("uuid").ifBlank { "custom-" + url.hashCode().toUInt().toString(36) },
                name = o.str("name").ifBlank { url },
                url = url,
                homepage = o.str("homepage"),
                favicon = o.str("favicon"),
                tags = tags,
                country = o.str("country"),
                countryCode = o.str("countrycode").uppercase(),
                state = o.str("state"),
                codec = o.str("codec"),
                bitrate = o.optInt("bitrate", 0),
                hls = o.optBoolean("hls", false) || isHlsUrl(url),
                votes = o.optInt("votes", 0),
                clickCount = o.optInt("clickcount", 0),
                clickTrend = o.optInt("clicktrend", 0),
                custom = custom,
            )
        }

        /** A station from the Radio Browser API. */
        fun fromApi(o: JSONObject): Station? {
            val uuid = o.str("stationuuid")
            val url = o.str("url_resolved").ifBlank { o.str("url") }.trim()
            if (uuid.isBlank() || url.isEmpty()) return null
            val tags = o.str("tags").split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && it.length <= 30 }
                .distinct()
                .take(8)
            val codec = o.str("codec").let { if (it.equals("UNKNOWN", ignoreCase = true)) "" else it }
            return Station(
                uuid = uuid,
                name = o.str("name").replace(SPACES, " ").trim().ifBlank { "Unnamed station" },
                url = url,
                homepage = o.str("homepage"),
                favicon = o.str("favicon"),
                tags = tags,
                country = o.str("country"),
                countryCode = o.str("countrycode").uppercase(),
                state = o.str("state"),
                codec = codec,
                bitrate = o.optInt("bitrate", 0),
                hls = o.optInt("hls", 0) == 1 || isHlsUrl(url),
                votes = o.optInt("votes", 0),
                clickCount = o.optInt("clickcount", 0),
                clickTrend = o.optInt("clicktrend", 0),
            )
        }
    }
}

/** optString() turns JSON null into the text "null"; this returns "" instead. */
internal fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
