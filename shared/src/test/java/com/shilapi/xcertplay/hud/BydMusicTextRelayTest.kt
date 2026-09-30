package com.shilapi.xcertplay.hud

import android.Manifest
import android.app.Application
import android.content.Intent
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydMusicTextRelayTest {
    private lateinit var app: Application
    private val relays = mutableListOf<BydMusicTextRelay>()
    private val writes = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private var workers = 0
    private var stoppedWorkers = 0
    private var closedSources = 0
    private var accepted = true
    private var result = 0
    private var throwOnWrite = false

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        BydMusicTextSettings.prefs(app).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH)
    }

    @After
    fun tearDown() {
        relays.forEach { it.close() }
        idle()
    }

    private fun create(supported: Boolean = true, failSdk: Boolean = false): BydMusicTextRelay =
        BydMusicTextRelay(app, supported,
            workerFactory = {
                workers++
                object : MusicTextWorker {
                    override val handler = Handler(Looper.getMainLooper())
                    override fun close() { stoppedWorkers++; handler.removeCallbacksAndMessages(null) }
                }
            },
            sourceFactory = {
                object : MusicTextSource {
                    override fun accepts() = accepted
                    override fun close() { closedSources++ }
                }
            },
            writerFactory = {
                if (failSdk) throw SecurityException("SDK unavailable")
                MusicTextWriter { text ->
                    if (throwOnWrite) throw IllegalStateException("SDK write failed")
                    writes += text
                    result
                }
            },
            diagnostic = logs::add,
        ).also { relays += it }

    private fun start(): BydMusicTextRelay {
        BydMusicTextSettings.setEnabled(app, true)
        return create().also { it.updateUsage(connected = true, playing = true, focusHeld = true); idle() }
    }

    private fun title(text: String?) {
        val metadata = MediaMetadata.Builder().apply {
            if (text != null) putString(MediaMetadata.METADATA_KEY_TITLE, text)
        }.build()
        app.sendBroadcast(Intent(BydMusicTextRelay.TRACK_EVENT).putExtra(BydMusicTextRelay.EXTRA_METADATA, metadata))
        idle()
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun disabledOrUnsupportedStartsNoWorkerAndWritesNothing() {
        val relay = create()
        assertFalse(BydMusicTextSettings.enabled(app))
        relay.updateUsage(true, true, true)
        title("song")
        idle(1000)
        assertEquals(0, workers)
        relay.close()
        BydMusicTextSettings.setEnabled(app, true)
        create(supported = false).updateUsage(true, true, true)
        idle()
        assertEquals(0, workers)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun writesLatestTitleOnceAndDoesNotLogItsContents() {
        start()
        title("first private line")
        idle(50)
        title("second private line")
        idle(100)
        assertEquals(listOf("second private line"), writes)
        title("second private line")
        idle(1000)
        assertEquals(1, writes.size)
        assertTrue(logs.any { "received=" in it && "changed=" in it })
        assertFalse(logs.any { "private line" in it })
    }

    @Test
    fun continuousUpdatesCannotStarveTheFirstSend() {
        start()
        repeat(5) { index -> title("line $index"); idle(40) }
        assertEquals(listOf("line 3"), writes)
        idle(150)
        assertEquals(listOf("line 3", "line 4"), writes)
    }

    @Test
    fun lostFocusCancelsPendingWriteAndGainUsesLatestText() {
        val relay = start()
        title("old line")
        relay.updateUsage(true, true, false)
        idle(500)
        assertTrue(writes.isEmpty())
        title("latest line")
        idle(500)
        assertTrue(writes.isEmpty())
        relay.updateUsage(true, true, true)
        idle(150)
        assertEquals(listOf("latest line"), writes)
    }

    @Test
    fun regainedFocusResendsUnchangedTitleAfterAnotherAppMayHaveOverwrittenIt() {
        val relay = start()
        title("song")
        idle(150)
        relay.updateUsage(true, true, false)
        idle()
        relay.updateUsage(true, true, true)
        idle(150)
        assertEquals(listOf("song", "song"), writes)
    }

    @Test
    fun pauseClearsQueuedAndCachedLyricsEvenIfImmediatelyResumed() {
        val relay = start()
        title("stale lyric")
        relay.updateUsage(true, false, true)
        relay.updateUsage(true, true, true)
        idle(1000)
        assertTrue(writes.isEmpty())
        title("fresh lyric")
        idle(150)
        assertEquals(listOf("fresh lyric"), writes)
    }

    @Test
    fun unknownOrMultipleBluetoothSourcesDiscardText() {
        val relay = start()
        accepted = false
        title("wrong phone")
        idle(500)
        accepted = true
        relay.updateUsage(true, true, true)
        idle(500)
        assertTrue(writes.isEmpty())
        title("current phone")
        idle(150)
        assertEquals(listOf("current phone"), writes)
    }

    @Test
    fun sourceMustStillMatchAtSendTime() {
        start()
        title("old source")
        accepted = false
        idle(150)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun emptyOrMalformedMetadataCannotReplayPreviousLyrics() {
        start()
        title("stale")
        title(" \n\u0000 ")
        idle(150)
        assertTrue(writes.isEmpty())
        app.sendBroadcast(Intent(BydMusicTextRelay.TRACK_EVENT).putExtra(BydMusicTextRelay.EXTRA_METADATA, "bad"))
        idle()
        title("valid")
        idle(150)
        assertEquals(listOf("valid"), writes)
    }

    @Test
    fun disablingLiveUnregistersAndCancelsPendingSendsWithoutPolling() {
        start()
        title("pending")
        BydMusicTextSettings.setEnabled(app, false)
        idle(1000)
        title("ignored")
        idle(60000)
        assertTrue(writes.isEmpty())
        assertEquals(1, stoppedWorkers)
        assertEquals(1, closedSources)
        BydMusicTextSettings.setEnabled(app, true)
        idle()
        title("after enabling")
        idle(150)
        assertEquals(listOf("after enabling"), writes)
    }

    @Test
    fun disconnectAndReplacementCannotSendOldControllerText() {
        val old = start()
        title("old pending")
        old.updateUsage(false, false, false)
        old.close()
        val next = create()
        next.updateUsage(true, true, true)
        idle(1000)
        assertTrue(writes.isEmpty())
        title("new controller")
        idle(150)
        assertEquals(listOf("new controller"), writes)
        assertEquals(1, stoppedWorkers)
    }

    @Test
    fun missingSdkOrPermissionIsIsolatedAndNotRetried() {
        BydMusicTextSettings.setEnabled(app, true)
        val relay = create(failSdk = true)
        relay.updateUsage(true, true, true)
        idle()
        repeat(3) { title("ignored"); relay.updateUsage(true, true, true) }
        idle(60000)
        assertTrue(writes.isEmpty())
        assertEquals(1, logs.count { "forwarding disabled" in it })
        assertEquals(1, workers)
    }

    @Test
    fun sdkErrorStopsOnlyTextForwardingUntilReenabled() {
        start()
        result = -1
        title("rejected")
        idle(150)
        result = 0
        title("must not retry")
        idle(60000)
        assertEquals(listOf("rejected"), writes)
        assertEquals(1, closedSources)
        BydMusicTextSettings.setEnabled(app, false)
        idle()
        BydMusicTextSettings.setEnabled(app, true)
        idle()
        title("new attempt")
        idle(150)
        assertEquals(listOf("rejected", "new attempt"), writes)
    }

    @Test
    fun sdkExceptionDoesNotEscapeBroadcastOrRetry() {
        start()
        throwOnWrite = true
        title("fails")
        idle(150)
        throwOnWrite = false
        title("ignored")
        idle(1000)
        assertTrue(writes.isEmpty())
        assertEquals(1, logs.count { "forwarding disabled" in it })
    }

    @Test
    fun utf16BoundPreservesSurrogatePairsAndStripsControls() {
        assertEquals("歌词 😀 next", BydMusicTextRelay.instrumentText(" 歌词\n😀\t next\u0000 "))
        val fits = "中".repeat(46) + "😀"
        assertEquals(fits, BydMusicTextRelay.instrumentText(fits + "尾"))
        val wouldSplit = "中".repeat(47) + "😀"
        val safe = BydMusicTextRelay.instrumentText(wouldSplit)!!
        assertEquals("中".repeat(47), safe)
        assertTrue(safe.toByteArray(Charsets.UTF_16LE).size <= 96)
        assertEquals("ab", BydMusicTextRelay.instrumentText("a\uD800b"))
    }
}
