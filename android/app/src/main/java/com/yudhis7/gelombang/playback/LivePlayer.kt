package com.yudhis7.gelombang.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Wraps ExoPlayer for live radio:
 * - "now playing" shows the song title from the stream (ICY) with the station underneath,
 *   and ignores empty or placeholder titles;
 * - resuming after a long pause rejoins the live broadcast instead of playing stale audio.
 */
@OptIn(UnstableApi::class)
class LivePlayer(player: Player) : ForwardingPlayer(player) {

    private var pausedAt = 0L

    override fun getMediaMetadata(): MediaMetadata {
        val merged = super.getMediaMetadata()
        val base = currentMediaItem?.mediaMetadata ?: return merged
        val stationName = base.title?.toString().orEmpty()
        val streamTitle = merged.title?.toString()?.trim().orEmpty()
        // The stream's own name (icy-name) is not a song title.
        val icyName = merged.station?.toString()?.trim().orEmpty()
        val usable = streamTitle.isNotEmpty() &&
            streamTitle != stationName &&
            !(icyName.isNotEmpty() && icyName != stationName && streamTitle == icyName) &&
            !JUNK.matches(streamTitle)
        return if (usable) {
            base.buildUpon()
                .setTitle(streamTitle)
                .setDisplayTitle(streamTitle)
                .setArtist(stationName)
                .setSubtitle(stationName)
                .build()
        } else {
            base
        }
    }

    override fun play() {
        rejoinIfStale()
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) rejoinIfStale() else pausedAt = SystemClock.elapsedRealtime()
        super.setPlayWhenReady(playWhenReady)
    }

    override fun pause() {
        pausedAt = SystemClock.elapsedRealtime()
        super.pause()
    }

    /** Current song title from the stream, or null if the station doesn't send one. */
    fun streamTitle(): String? {
        val base = currentMediaItem?.mediaMetadata?.title?.toString()
        val t = getMediaMetadata().title?.toString()
        return t?.takeIf { it != base }
    }

    private fun rejoinIfStale() {
        val wrapped = wrappedPlayer
        if (wrapped.playbackState == Player.STATE_IDLE) {
            wrapped.prepare()
        } else if (pausedAt > 0 && SystemClock.elapsedRealtime() - pausedAt > STALE_MS) {
            wrapped.seekToDefaultPosition()
        }
        pausedAt = 0
    }

    companion object {
        private const val STALE_MS = 20_000L
        private val JUNK = Regex("""^[\s\-–_.|]*$|^(unknown|n/a|null)$""", RegexOption.IGNORE_CASE)
    }
}
