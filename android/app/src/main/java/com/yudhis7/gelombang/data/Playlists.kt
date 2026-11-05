package com.yudhis7.gelombang.data

import org.json.JSONArray
import org.json.JSONObject

/** Favourites import/export: JSON backup (same format as the desktop app), M3U and PLS. */
object Playlists {

    fun toJsonBackup(stations: List<Station>): String = JSONObject().apply {
        put("app", "Gelombang Radio")
        put("exported", System.currentTimeMillis())
        put("favorites", JSONArray(stations.map { it.toJson() }))
    }.toString(2)

    fun toM3u(stations: List<Station>): String = buildString {
        append("#EXTM3U\n")
        for (s in stations) {
            append("#EXTINF:-1 tvg-logo=\"").append(s.favicon.replace("\"", ""))
            append("\" group-title=\"").append(s.countryCode).append("\",").append(s.name).append('\n')
            append(s.url).append('\n')
        }
    }

    fun parse(raw: String): List<Station> {
        val t = raw.removePrefix("﻿").trim()
        val isPls = t.startsWith("[playlist]", ignoreCase = true)
        if (!isPls && (t.startsWith("{") || t.startsWith("["))) return parseJson(t)
        if (isPls) return parsePls(t)
        return parseM3u(t)
    }

    private fun parseJson(t: String): List<Station> {
        val arr = if (t.startsWith("[")) JSONArray(t) else {
            val o = JSONObject(t)
            o.optJSONArray("favorites") ?: o.optJSONArray("stations") ?: JSONArray()
        }
        return arr.objects().mapNotNull { Station.fromJson(it) }
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
    }

    private fun parsePls(t: String): List<Station> {
        val files = sortedMapOf<Int, String>()
        val titles = HashMap<Int, String>()
        val re = Regex("""^(File|Title)(\d+)=(.*)$""", RegexOption.IGNORE_CASE)
        for (line in t.lines()) {
            val m = re.find(line.trim()) ?: continue
            val n = m.groupValues[2].toInt()
            if (m.groupValues[1].equals("file", true)) files[n] = m.groupValues[3].trim() else titles[n] = m.groupValues[3].trim()
        }
        return files.map { (n, url) -> custom(titles[n] ?: url, url) }.filter { it.url.startsWith("http") }
    }

    private fun parseM3u(t: String): List<Station> {
        val out = ArrayList<Station>()
        var name: String? = null
        var logo = ""
        var cc = ""
        for (raw in t.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                name = extinfTitle(line)
                logo = Regex("""tvg-logo="([^"]*)"""").find(line)?.groupValues?.get(1).orEmpty()
                cc = Regex("""group-title="([A-Za-z]{2})"""").find(line)?.groupValues?.get(1).orEmpty().uppercase()
            } else if (!line.startsWith("#") && (line.startsWith("http://", true) || line.startsWith("https://", true))) {
                out += custom(name?.ifBlank { null } ?: line, line).copy(favicon = logo, countryCode = cc)
                name = null; logo = ""; cc = ""
            }
        }
        return out
    }

    private fun custom(name: String, url: String) = Station(
        uuid = "custom-" + url.hashCode().toUInt().toString(36),
        name = name,
        url = url,
        hls = Station.isHlsUrl(url),
        custom = true,
    )

    /** Title is everything after the first comma that is not inside a quoted attribute. */
    private fun extinfTitle(line: String): String {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return line.substring(i + 1).trim()
            }
        }
        return ""
    }
}
