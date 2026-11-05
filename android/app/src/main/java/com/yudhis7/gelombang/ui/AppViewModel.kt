package com.yudhis7.gelombang.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.yudhis7.gelombang.data.AppGraph
import com.yudhis7.gelombang.data.Playlists
import com.yudhis7.gelombang.data.SleepTimer
import com.yudhis7.gelombang.data.Station
import com.yudhis7.gelombang.playback.MediaIds
import com.yudhis7.gelombang.playback.PlaybackService
import com.yudhis7.gelombang.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screens. The first entry of the back stack is always one of the four tabs. */
sealed interface Dest {
    data object Home : Dest
    data object Explore : Dest
    data object Search : Dest
    data object Library : Dest
    data object Settings : Dest
    data object Trending : Dest
    data class Group(val id: String) : Dest
    data class Country(val code: String) : Dest
}

data class PlayerUi(
    val station: Station? = null,
    val song: String? = null,
    val isPlaying: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val canSkip: Boolean = false,
)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {

    val favorites: StateFlow<List<Station>>
    val recents: StateFlow<List<Station>>
    val custom: StateFlow<List<Station>>
    val counts: StateFlow<Map<String, Int>?>
    val sleepEndsAt: StateFlow<Long?> = SleepTimer.endsAt

    private val _player = MutableStateFlow(PlayerUi())
    val player: StateFlow<PlayerUi> = _player.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val stack = mutableStateListOf<Dest>(Dest.Home)
    var showPlayer by mutableStateOf(false)
    var editing by mutableStateOf<Station?>(null)
    var addingStation by mutableStateOf(false)

    var searchQuery by mutableStateOf("")
    var searchScope by mutableStateOf<String?>(null)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }

    init {
        AppGraph.init(app)
        favorites = AppGraph.library.favorites
        recents = AppGraph.library.recents
        custom = AppGraph.library.custom
        counts = AppGraph.counts
        connect()
        viewModelScope.launch { AppGraph.loadCounts() }
        viewModelScope.launch { refreshFavorites() }
        viewModelScope.launch { favorites.collect { update() } }
    }

    private fun connect() {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(listener)
            update()
        }, ContextCompat.getMainExecutor(app))
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }

    private fun update() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val uuid = item?.mediaId?.let { MediaIds.stationUuid(it) }
        val station = uuid?.let { AppGraph.stations.get(it) }
            ?: item?.let {
                Station(
                    uuid = uuid ?: it.mediaId,
                    name = it.mediaMetadata.station?.toString() ?: it.mediaMetadata.title?.toString().orEmpty(),
                    url = "",
                )
            }
        val title = c.mediaMetadata.title?.toString()
        val song = title?.takeIf { station != null && it.isNotBlank() && it != station.name }
        _player.value = PlayerUi(
            station = station,
            song = song,
            isPlaying = c.isPlaying,
            busy = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING,
            error = c.playerError?.let { "Stream unavailable right now" },
            canSkip = c.mediaItemCount > 1,
        )
    }

    // ------------------------------------------------------------ navigation

    val tab: Dest get() = stack.first()
    val current: Dest get() = stack.last()

    fun selectTab(d: Dest) {
        if (d == Dest.Search) {
            // Searching from a country page searches inside that country (the chip can widen it).
            val from = stack.lastOrNull()
            searchScope = if (from is Dest.Country) from.code else if (stack.firstOrNull() == Dest.Search) searchScope else null
        }
        stack.clear()
        stack.add(d)
    }

    fun open(d: Dest) {
        if (stack.lastOrNull() != d) stack.add(d)
    }

    fun back(): Boolean {
        if (showPlayer) {
            showPlayer = false
            return true
        }
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        return false
    }

    // ------------------------------------------------------------ playback

    fun play(station: Station, queue: List<Station> = emptyList()) {
        val c = controller
        if (c == null) {
            say("The player is still starting. Try again in a moment.")
            return
        }
        val cur = _player.value
        if (cur.station?.uuid == station.uuid && cur.error == null) {
            toggle()
            return
        }
        val list = (if (queue.any { it.uuid == station.uuid }) queue else listOf(station)).take(300)
        AppGraph.stations.remember(list)
        val index = list.indexOfFirst { it.uuid == station.uuid }.coerceAtLeast(0)
        c.setMediaItems(list.map { it.toMediaItem(app) }, index, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            val last = AppGraph.library.lastStation ?: favorites.value.firstOrNull() ?: return
            play(last, favorites.value)
            return
        }
        if (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
        showPlayer = false
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun setSleep(minutes: Int) {
        SleepTimer.set(minutes)
        say(if (minutes > 0) "Sleep timer set for ${sleepLabel(minutes)}" else "Sleep timer off")
    }

    // ------------------------------------------------------------ library

    fun isFavorite(uuid: String?) = AppGraph.library.isFavorite(uuid)

    fun toggleFavorite(station: Station) {
        val now = AppGraph.library.toggleFavorite(station)
        say(if (now) "Added ${station.name} to favourites" else "Removed ${station.name} from favourites")
    }

    fun removeRecent(station: Station) = AppGraph.library.removeRecent(station.uuid)

    fun clearRecents() = AppGraph.library.clearRecents()

    fun saveCustom(station: Station) {
        AppGraph.library.saveCustom(station)
        AppGraph.stations.remember(station)
        say("Saved ${station.name}")
    }

    fun deleteCustom(station: Station) {
        AppGraph.library.deleteCustom(station.uuid)
        say("Deleted ${station.name}")
    }

    private suspend fun refreshFavorites() {
        val ids = favorites.value.filter { !it.custom }.map { it.uuid }
        if (ids.isEmpty()) return
        runCatching { AppGraph.api.byUuids(ids) }.getOrNull()?.let { AppGraph.library.refreshFavorites(it) }
    }

    fun exportFavorites(uri: Uri, m3u: Boolean) {
        val list = favorites.value
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri)?.use {
                        it.write((if (m3u) Playlists.toM3u(list) else Playlists.toJsonBackup(list)).toByteArray())
                    } ?: error("no stream")
                }.isSuccess
            }
            say(if (ok) "Exported ${list.size} favourites" else "Could not save the file")
        }
    }

    fun importStations(uri: Uri) {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { s -> Playlists.parse(s.bufferedReader().readText()) }.orEmpty()
                }.getOrNull()
            }
            if (parsed == null) {
                say("Could not read that file")
                return@launch
            }
            val added = AppGraph.library.import(parsed)
            AppGraph.stations.remember(parsed)
            say(if (added > 0) "Imported $added station${if (added == 1) "" else "s"}" else "Nothing new to import")
        }
    }

    fun say(message: String) {
        _messages.tryEmit(message)
    }

    companion object {
        fun sleepLabel(minutes: Int) = when {
            minutes < 60 -> "$minutes minutes"
            minutes == 60 -> "1 hour"
            minutes % 60 == 0 -> "${minutes / 60} hours"
            else -> "${minutes / 60.0} hours"
        }
    }
}
