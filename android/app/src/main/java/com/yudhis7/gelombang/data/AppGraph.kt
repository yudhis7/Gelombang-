package com.yudhis7.gelombang.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** App-wide singletons shared by the interface and the playback service (same process). */
object AppGraph {
    lateinit var regions: Regions
        private set
    lateinit var api: RadioBrowser
        private set
    lateinit var library: Library
        private set

    val stations = StationCache()

    private val _counts = MutableStateFlow<Map<String, Int>?>(null)
    val counts: StateFlow<Map<String, Int>?> = _counts.asStateFlow()

    @Volatile private var ready = false

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        val app = context.applicationContext
        regions = Regions.load(app)
        api = RadioBrowser(regions.countries.keys)
        library = Library(app)
        ready = true
    }

    /** Loads station counts once; later calls return the cached map. */
    suspend fun loadCounts(): Map<String, Int>? {
        _counts.value?.let { return it }
        return runCatching { api.countryCounts() }.getOrNull()?.also { _counts.value = it }
    }

    /** Countries with the most stations, used for "Trending in APAC". */
    fun trendingCodes(): List<String> {
        val c = _counts.value
        if (c.isNullOrEmpty()) return listOf("AU", "NZ", "ID", "MY", "SG", "TH", "PH", "VN", "JP", "KR", "CN", "HK", "TW", "IN", "PK", "BD")
        return c.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(16).map { it.key }
    }
}

/** Remembers every station the app has seen, so a media ID can be turned back into a stream. */
class StationCache {
    private val map = ConcurrentHashMap<String, Station>()

    fun remember(list: Collection<Station>) {
        for (s in list) map[s.uuid] = s
    }

    fun remember(station: Station) {
        map[station.uuid] = station
    }

    fun get(uuid: String): Station? = map[uuid] ?: AppGraph.library.find(uuid)

    suspend fun resolve(uuid: String): Station? =
        get(uuid) ?: runCatching { AppGraph.api.byUuids(listOf(uuid)).firstOrNull() }.getOrNull()?.also { remember(it) }
}

/** Sleep timer shared by the interface (sets it) and the playback service (acts on it). */
object SleepTimer {
    private val _endsAt = MutableStateFlow<Long?>(null)
    val endsAt: StateFlow<Long?> = _endsAt.asStateFlow()

    fun set(minutes: Int) {
        _endsAt.value = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else null
    }

    fun clear() {
        _endsAt.value = null
    }
}
