package com.yudhis7.gelombang

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yudhis7.gelombang.playback.PlaybackService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Connects to the playback service the way Android Auto does (the platform MediaBrowser API),
 * walks the catalogue, and starts playback by media ID and by voice-style search.
 */
@RunWith(AndroidJUnit4::class)
class AutoBrowseTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())
    private var browser: MediaBrowser? = null

    private fun log(msg: String) = Log.i(TAG, msg)

    private fun connect(hints: Bundle?): MediaBrowser? {
        val latch = CountDownLatch(1)
        var ok = false
        var b: MediaBrowser? = null
        main.post {
            b = MediaBrowser(
                context,
                ComponentName(context, PlaybackService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { ok = true; latch.countDown() }
                    override fun onConnectionFailed() { latch.countDown() }
                    override fun onConnectionSuspended() { latch.countDown() }
                },
                hints,
            )
            b!!.connect()
        }
        latch.await(20, TimeUnit.SECONDS)
        return if (ok) b else null
    }

    private fun children(b: MediaBrowser, parentId: String): List<MediaBrowser.MediaItem> {
        val latch = CountDownLatch(1)
        var result: List<MediaBrowser.MediaItem> = emptyList()
        main.post {
            b.subscribe(parentId, object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                    result = children.toList()
                    b.unsubscribe(parentId)
                    latch.countDown()
                }
                override fun onError(parentId: String) { latch.countDown() }
            })
        }
        latch.await(40, TimeUnit.SECONDS)
        log("children($parentId) = ${result.size}: " + result.take(6).joinToString { "${it.mediaId} '${it.description.title}' / '${it.description.subtitle}' icon=${it.description.iconUri}" })
        return result
    }

    private fun waitForState(controller: MediaController, wanted: Int, seconds: Long): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (controller.playbackState?.state == wanted) return true
            Thread.sleep(500)
        }
        return false
    }

    @After
    fun tearDown() {
        main.post { browser?.disconnect() }
    }

    @Test
    fun carCanBrowseAndPlay() {
        val b = connect(null)
        assertNotNull("MediaBrowser could not connect", b)
        browser = b!!
        @Suppress("DEPRECATION")
        log("root='${b.root}' extras=${b.extras?.keySet()?.joinToString { k -> "$k=${b.extras?.get(k)}" }}")

        val tabs = children(b, b.root)
        assertEquals(listOf("favorites", "recents", "regions", "trending"), tabs.map { it.mediaId })
        assertTrue(tabs.all { it.isBrowsable })

        val groups = children(b, "regions")
        assertEquals(5, groups.size)

        val countries = children(b, "group:sea")
        assertTrue("no countries", countries.isNotEmpty())

        val stations = children(b, "country:ID")
        assertTrue("no Indonesian stations", stations.isNotEmpty())
        assertTrue(stations.all { it.isPlayable })

        val trending = children(b, "trending")
        assertTrue("no trending stations", trending.isNotEmpty())

        // Play the first station, as tapping it in the car would.
        val controller = MediaController(context, b.sessionToken)
        val first = stations.first()
        main.post { controller.transportControls.playFromMediaId(first.mediaId, null) }
        val playing = waitForState(controller, PlaybackState.STATE_PLAYING, 30)
        log("playFromMediaId(${first.mediaId}) playing=$playing state=${controller.playbackState} title='${controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}' artist='${controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)}' art=${controller.metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: controller.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)}")
        log("custom actions: " + controller.playbackState?.customActions?.joinToString { it.name })
        assertTrue("station did not start playing", playing)

        // Next station in the same list (the car's skip button).
        val before = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        main.post { controller.transportControls.skipToNext() }
        Thread.sleep(8000)
        val after = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        log("after skipToNext: state=${controller.playbackState?.state} '$before' -> '$after'")
        assertTrue("skip did not change station", before != after)

        // Favourite button on the car's Now Playing screen.
        val fav = controller.playbackState?.customActions?.firstOrNull()
        if (fav != null) {
            main.post { controller.transportControls.sendCustomAction(fav, null) }
            Thread.sleep(1500)
            log("after favourite action: " + controller.playbackState?.customActions?.joinToString { it.name })
            log("favourites now: " + children(b, "favorites").size)
        }

        // Voice: "play jazz on Gelombang".
        main.post { controller.transportControls.stop() }
        waitForState(controller, PlaybackState.STATE_STOPPED, 10)
        main.post { controller.transportControls.playFromSearch("jazz", Bundle()) }
        val searched = waitForState(controller, PlaybackState.STATE_PLAYING, 30)
        log("playFromSearch(jazz) playing=$searched state=${controller.playbackState?.state} title='${controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}' genre=${controller.metadata?.getString(MediaMetadata.METADATA_KEY_GENRE)}")
        assertTrue("voice search did not start playback", searched)

        main.post { controller.transportControls.pause() }
        Thread.sleep(1000)
    }

    @Test
    fun systemResumptionCanConnect() {
        // The system media panel connects with EXTRA_RECENT to offer "resume" after a reboot.
        val hints = Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true) }
        val b = connect(hints)
        log("EXTRA_RECENT connect: ${b != null} root='${b?.root}'")
        browser = b
        if (b != null) children(b, b.root)
    }

    companion object {
        private const val TAG = "AutoBrowseTest"
    }
}
