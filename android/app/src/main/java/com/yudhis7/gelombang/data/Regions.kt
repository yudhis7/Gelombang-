package com.yudhis7.gelombang.data

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Country(val code: String, val name: String, val tz: String, val groupId: String)

data class RegionGroup(val id: String, val name: String, val countries: List<Country>)

/** The countries shown in the app, grouped by region (same file as the desktop app). */
class Regions(val groups: List<RegionGroup>) {
    val countries: Map<String, Country> = groups.flatMap { it.countries }.associateBy { it.code }

    fun name(code: String): String = countries[code]?.name ?: code

    fun group(id: String): RegionGroup? = groups.firstOrNull { it.id == id }

    companion object {
        /** Current wall-clock time ("08:22") in a time zone such as "Asia/Jakarta". */
        fun localTime(tz: String): String = try {
            SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone(tz) }.format(Date())
        } catch (e: Exception) {
            ""
        }

        fun load(context: Context): Regions {
            val text = context.assets.open("regions.json").bufferedReader().use { it.readText() }
            val groups = JSONObject(text).getJSONArray("groups").objects().map { g ->
                val id = g.str("id")
                RegionGroup(
                    id = id,
                    name = g.str("name"),
                    countries = g.getJSONArray("countries").objects().map { c ->
                        Country(c.str("code"), c.str("name"), c.str("tz"), id)
                    },
                )
            }
            return Regions(groups)
        }
    }
}
