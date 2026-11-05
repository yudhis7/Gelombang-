package com.yudhis7.gelombang.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Favourites, history, custom stations and small settings, stored on the device. */
class Library(context: Context) {

    private val prefs = context.getSharedPreferences("gelombang", Context.MODE_PRIVATE)

    private val _favorites = MutableStateFlow(load(KEY_FAVORITES))
    private val _recents = MutableStateFlow(load(KEY_RECENTS))
    private val _custom = MutableStateFlow(load(KEY_CUSTOM))

    val favorites: StateFlow<List<Station>> = _favorites.asStateFlow()
    val recents: StateFlow<List<Station>> = _recents.asStateFlow()
    val custom: StateFlow<List<Station>> = _custom.asStateFlow()

    var sort: RadioBrowser.Sort
        get() = runCatching { RadioBrowser.Sort.valueOf(prefs.getString("sort", null) ?: "") }.getOrDefault(RadioBrowser.Sort.POPULAR)
        set(value) = prefs.edit().putString("sort", value.name).apply()

    var lastStation: Station?
        get() = prefs.getString("lastStation", null)?.let { runCatching { Station.fromJson(JSONObject(it)) }.getOrNull() }
        set(value) = prefs.edit().putString("lastStation", value?.toJson()?.toString()).apply()

    fun isFavorite(uuid: String?): Boolean = uuid != null && _favorites.value.any { it.uuid == uuid }

    fun find(uuid: String): Station? =
        _favorites.value.firstOrNull { it.uuid == uuid }
            ?: _custom.value.firstOrNull { it.uuid == uuid }
            ?: _recents.value.firstOrNull { it.uuid == uuid }

    /** Returns true if the station is now a favourite. */
    fun toggleFavorite(station: Station): Boolean {
        val now = if (isFavorite(station.uuid)) {
            _favorites.value.filterNot { it.uuid == station.uuid }
        } else {
            _favorites.value + station
        }
        _favorites.value = now
        save(KEY_FAVORITES, now)
        return isFavorite(station.uuid)
    }

    fun moveFavorite(from: Int, to: Int) {
        val list = _favorites.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _favorites.value = list
        save(KEY_FAVORITES, list)
    }

    fun addRecent(station: Station) {
        val now = (listOf(station) + _recents.value.filterNot { it.uuid == station.uuid }).take(MAX_RECENTS)
        _recents.value = now
        save(KEY_RECENTS, now)
    }

    fun removeRecent(uuid: String) {
        val now = _recents.value.filterNot { it.uuid == uuid }
        _recents.value = now
        save(KEY_RECENTS, now)
    }

    fun clearRecents() {
        _recents.value = emptyList()
        save(KEY_RECENTS, emptyList())
    }

    /** Adds or updates a custom station; new ones are also added to favourites. */
    fun saveCustom(station: Station) {
        val st = station.copy(custom = true)
        val exists = _custom.value.any { it.uuid == st.uuid }
        val list = if (exists) _custom.value.map { if (it.uuid == st.uuid) st else it } else _custom.value + st
        _custom.value = list
        save(KEY_CUSTOM, list)
        val favs = if (exists) _favorites.value.map { if (it.uuid == st.uuid) st else it } else _favorites.value + st
        _favorites.value = favs
        save(KEY_FAVORITES, favs)
    }

    fun deleteCustom(uuid: String) {
        _custom.value = _custom.value.filterNot { it.uuid == uuid }
        _favorites.value = _favorites.value.filterNot { it.uuid == uuid }
        save(KEY_CUSTOM, _custom.value)
        save(KEY_FAVORITES, _favorites.value)
    }

    /** Replaces saved copies with fresh directory data (stream links and logos change). */
    fun refreshFavorites(fresh: List<Station>) {
        val byId = fresh.associateBy { it.uuid }
        var changed = false
        val now = _favorites.value.map { old ->
            val f = byId[old.uuid]
            if (f == null || old.custom) old else {
                if (f.url != old.url || f.favicon != old.favicon || f.bitrate != old.bitrate) changed = true
                f
            }
        }
        if (changed) {
            _favorites.value = now
            save(KEY_FAVORITES, now)
        }
    }

    /** Adds imported stations to favourites; returns how many were new. */
    fun import(stations: List<Station>): Int {
        var added = 0
        val favs = _favorites.value.toMutableList()
        val custom = _custom.value.toMutableList()
        for (s in stations) {
            if (favs.any { it.url == s.url || it.uuid == s.uuid }) continue
            if (s.custom && custom.none { it.url == s.url }) custom += s
            favs += s
            added++
        }
        _favorites.value = favs
        _custom.value = custom
        save(KEY_FAVORITES, favs)
        save(KEY_CUSTOM, custom)
        return added
    }

    private fun load(key: String): List<Station> = try {
        val text = prefs.getString(key, null) ?: return emptyList()
        JSONArray(text).objects().mapNotNull { Station.fromJson(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(key: String, list: List<Station>) {
        prefs.edit().putString(key, JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RECENTS = "recents"
        private const val KEY_CUSTOM = "custom"
        private const val MAX_RECENTS = 40
    }
}
