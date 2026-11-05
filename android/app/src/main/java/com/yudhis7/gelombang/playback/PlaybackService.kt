package com.yudhis7.gelombang.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yudhis7.gelombang.R
import com.yudhis7.gelombang.data.AppGraph
import com.yudhis7.gelombang.data.RadioBrowser
import com.yudhis7.gelombang.data.SleepTimer
import com.yudhis7.gelombang.data.Station
import com.yudhis7.gelombang.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays the radio in the background and publishes the station catalogue to
 * Android Auto, the media notification, and the app's own screens.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private lateinit var exo: ExoPlayer
    private lateinit var player: LivePlayer
    private var session: MediaLibrarySession? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val searchResults = ConcurrentHashMap<String, List<Station>>()
    private val browsedLists = LinkedHashMap<String, List<Station>>()

    private var retries = 0
    private var playedCurrent = false
    private var retryJob: Job? = null
    private var sleepJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(RadioBrowser.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(playerListener)
        player = LivePlayer(exo)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_radio) },
        )

        scope.launch { AppGraph.library.favorites.collect { updateFavoriteButton() } }
        scope.launch { SleepTimer.endsAt.collect { scheduleSleep(it) } }
        scope.launch { AppGraph.loadCounts() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- playback

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retries = 0
            playedCurrent = false
            retryJob?.cancel()
            val uuid = mediaItem?.mediaId?.let { MediaIds.stationUuid(it) } ?: return
            val st = AppGraph.stations.get(uuid) ?: return
            AppGraph.library.addRecent(st)
            AppGraph.library.lastStation = st
            if (!st.custom) scope.launch { AppGraph.api.click(st.uuid) }
            updateFavoriteButton()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                playedCurrent = true
                retries = 0
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                exo.seekToDefaultPosition()
                exo.prepare()
                return
            }
            // Live streams drop now and then: reconnect a few times with back-off.
            val max = if (playedCurrent) 6 else 1
            if (retries >= max || !exo.playWhenReady) return
            retries++
            val wait = if (playedCurrent) minOf(2000L shl (retries - 1), 30_000L) else 1500L
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(wait)
                if (exo.playerError != null) {
                    exo.prepare()
                    exo.play()
                }
            }
        }
    }

    private fun scheduleSleep(endsAt: Long?) {
        sleepJob?.cancel()
        exo.volume = 1f
        if (endsAt == null) return
        sleepJob = scope.launch {
            val fadeMs = 20_000L
            val wait = endsAt - fadeMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            while (true) {
                val left = endsAt - System.currentTimeMillis()
                if (left <= 0) break
                exo.volume = (left.toFloat() / fadeMs).coerceIn(0f, 1f)
                delay(250)
            }
            player.pause()
            exo.volume = 1f
            SleepTimer.clear()
        }
    }

    private fun currentStation(): Station? =
        exo.currentMediaItem?.mediaId?.let { MediaIds.stationUuid(it) }?.let { AppGraph.stations.get(it) }

    private fun favoriteButton(): CommandButton {
        val fav = AppGraph.library.isFavorite(currentStation()?.uuid)
        @Suppress("DEPRECATION")
        return CommandButton.Builder()
            .setDisplayName(if (fav) "Remove from favourites" else "Add to favourites")
            .setIconResId(if (fav) R.drawable.ic_star_filled else R.drawable.ic_star)
            .setSessionCommand(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
            .build()
    }

    private fun updateFavoriteButton() {
        session?.setCustomLayout(ImmutableList.of(favoriteButton()))
    }

    // ---------------------------------------------------------------- browse tree

    private suspend fun children(parentId: String): List<MediaItem> {
        val ctx = this
        return when (parentId) {
            MediaIds.ROOT -> listOf(
                folderItem(MediaIds.FAVORITES, "Favourites", artwork = resourceUri(ctx, TabIcons.favorites)),
                folderItem(MediaIds.RECENTS, "Recent", artwork = resourceUri(ctx, TabIcons.recents)),
                folderItem(
                    MediaIds.REGIONS, "Regions",
                    artwork = resourceUri(ctx, TabIcons.regions),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                ),
                folderItem(MediaIds.TRENDING, "Trending", artwork = resourceUri(ctx, TabIcons.trending)),
            )
            MediaIds.FAVORITES -> stationItems(parentId, AppGraph.library.favorites.value)
            MediaIds.RECENTS -> stationItems(parentId, AppGraph.library.recents.value)
            MediaIds.TRENDING -> stationItems(parentId, AppGraph.api.topAcross(AppGraph.trendingCodes(), 10))
            MediaIds.REGIONS -> {
                val counts = AppGraph.loadCounts()
                AppGraph.regions.groups.map { g ->
                    val n = counts?.let { c -> g.countries.sumOf { c[it.code] ?: 0 } }
                    folderItem(
                        MediaIds.group(g.id), g.name,
                        subtitle = n?.let { "$it stations" } ?: "${g.countries.size} countries",
                        artwork = ArtworkProvider.labelUri(ctx, GROUP_LABELS[g.id] ?: ArtworkProvider.initials(g.name), g.id),
                        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                    )
                }
            }
            else -> {
                val groupId = MediaIds.groupId(parentId)
                val code = MediaIds.countryCode(parentId)
                when {
                    groupId != null -> {
                        val counts = AppGraph.loadCounts()
                        val group = AppGraph.regions.group(groupId) ?: return emptyList()
                        group.countries
                            .filter { counts == null || (counts[it.code] ?: 0) > 0 }
                            .map { c ->
                                folderItem(
                                    MediaIds.country(c.code), c.name,
                                    subtitle = counts?.get(c.code)?.let { "$it stations" },
                                    artwork = ArtworkProvider.labelUri(ctx, c.code, c.code),
                                )
                            }
                    }
                    code != null -> stationItems(parentId, AppGraph.api.stationsByCountry(code, limit = 100))
                    else -> emptyList()
                }
            }
        }
    }

    private fun stationItems(parentId: String, list: List<Station>): List<MediaItem> {
        AppGraph.stations.remember(list)
        synchronized(browsedLists) {
            browsedLists.remove(parentId)
            browsedLists[parentId] = list
            while (browsedLists.size > 12) browsedLists.remove(browsedLists.keys.first())
        }
        return list.map { it.toMediaItem(this) }
    }

    /** The most recently browsed list containing this station, so next/previous work in the car. */
    private fun siblingsOf(uuid: String): List<Station>? = synchronized(browsedLists) {
        browsedLists.values.reversed().firstOrNull { list -> list.any { it.uuid == uuid } }
    }

    private suspend fun resolveItem(item: MediaItem): MediaItem? {
        if (item.mediaId.isEmpty()) {
            // Voice request ("play jazz on Gelombang") or a plain "play".
            val query = item.requestMetadata.searchQuery?.trim().orEmpty()
            val hit = if (query.isEmpty()) {
                AppGraph.library.lastStation ?: AppGraph.library.favorites.value.firstOrNull()
            } else {
                AppGraph.library.favorites.value.firstOrNull { it.name.contains(query, ignoreCase = true) }
                    ?: runCatching { AppGraph.api.search(query) }.getOrNull()?.firstOrNull()
            }
            return hit?.also { AppGraph.stations.remember(it) }?.toMediaItem(this)
        }
        val uuid = MediaIds.stationUuid(item.mediaId) ?: return null
        return AppGraph.stations.resolve(uuid)?.toMediaItem(this)
    }

    private fun paged(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE || page < 0) return items
        val from = page.toLong() * pageSize
        if (from >= items.size) return emptyList()
        return items.subList(from.toInt(), minOf(items.size.toLong(), from + pageSize).toInt())
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(ImmutableList.of(favoriteButton()))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_FAVORITE) {
                currentStation()?.let { AppGraph.library.toggleFavorite(it) }
                updateFavoriteButton()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putBoolean(SEARCH_SUPPORTED, true)
            }
            val root = folderItem(MediaIds.ROOT, getString(R.string.app_name), mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            return Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                LibraryResult.ofItemList(paged(children(parentId), page, pageSize), params)
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                LibraryResult.ofError<ImmutableList<MediaItem>>(LibraryResult.RESULT_ERROR_IO)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val st = MediaIds.stationUuid(mediaId)?.let { AppGraph.stations.resolve(it) }
            if (st != null) {
                LibraryResult.ofItem(st.toMediaItem(this@PlaybackService), null)
            } else {
                @Suppress("DEPRECATION")
                LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val results = runCatching { AppGraph.api.search(query) }.getOrDefault(emptyList())
                searchResults[query] = results
                AppGraph.stations.remember(results)
                session.notifySearchResultChanged(browser, query, results.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val results = searchResults[query]
                ?: runCatching { AppGraph.api.search(query) }.getOrDefault(emptyList()).also { searchResults[query] = it }
            LibraryResult.ofItemList(paged(stationItems("search:$query", results), page, pageSize), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.mapNotNull { resolveItem(it) }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            // A single station picked in the car: queue its whole list so next/previous work.
            if (mediaItems.size == 1 && controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) {
                val resolved = resolveItem(mediaItems[0])
                if (resolved != null) {
                    val uuid = MediaIds.stationUuid(resolved.mediaId)
                    val siblings = uuid?.let { siblingsOf(it) }
                    if (siblings != null && siblings.size > 1) {
                        val idx = siblings.indexOfFirst { it.uuid == uuid }.coerceAtLeast(0)
                        return@future MediaSession.MediaItemsWithStartPosition(
                            siblings.map { it.toMediaItem(this@PlaybackService) }, idx, C.TIME_UNSET,
                        )
                    }
                    return@future MediaSession.MediaItemsWithStartPosition(listOf(resolved), 0, C.TIME_UNSET)
                }
            }
            val resolved = mediaItems.mapNotNull { resolveItem(it) }
            val index = when {
                resolved.isEmpty() -> C.INDEX_UNSET
                startIndex in resolved.indices -> startIndex
                else -> 0
            }
            MediaSession.MediaItemsWithStartPosition(resolved, index, startPositionMs)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val st = AppGraph.library.lastStation ?: AppGraph.library.favorites.value.firstOrNull()
            if (st == null) {
                MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
            } else {
                AppGraph.stations.remember(st)
                MediaSession.MediaItemsWithStartPosition(listOf(st.toMediaItem(this@PlaybackService)), 0, C.TIME_UNSET)
            }
        }
    }

    companion object {
        const val CMD_FAVORITE = "com.yudhis7.gelombang.FAVORITE"
        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        private val GROUP_LABELS = mapOf("anz" to "ANZ", "pacific" to "PAC", "sea" to "SEA", "east" to "EA", "south" to "SA")
    }
}
