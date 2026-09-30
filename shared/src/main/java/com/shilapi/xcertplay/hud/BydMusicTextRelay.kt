package com.shilapi.xcertplay.hud

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.Closeable

/**
 * Relays the stock AVRCP title through the instrument SDK while DiPlay owns music focus.
 * Does not request focus, change the media source, send Bluetooth commands, or obtain lyrics.
 * One worker is created only for enabled, connected sessions; SDK calls never use the audio thread.
 */
class BydMusicTextRelay internal constructor(
    context: Context,
    private val supported: Boolean,
    private val workerFactory: () -> MusicTextWorker,
    private val sourceFactory: (Handler) -> MusicTextSource,
    private val writerFactory: () -> MusicTextWriter,
    private val diagnostic: (String) -> Unit,
) : Closeable {
    private val app = context.applicationContext
    private val prefs = BydMusicTextSettings.prefs(app)
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var usage = Usage(false, false, false)
    @Volatile private var run: Run? = null
    private var unsupportedReported = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BydMusicTextSettings.KEY_ENABLED) synchronized(lock) { reconcile() }
    }

    init { prefs.registerOnSharedPreferenceChangeListener(preferenceListener) }

    fun updateUsage(connected: Boolean, playing: Boolean, focusHeld: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        val previous = usage
        val next = Usage(connected, playing, focusHeld)
        usage = next
        reconcile()
        val active = run ?: return@synchronized
        // Invalidate queued text immediately, before the worker observes pause or focus loss.
        if (previous != usage) active.invalidate()
        active.worker.handler.post {
            if (!active.current()) return@post
            if (!next.focusHeld) active.resetSent()
            if (!next.playing) active.clearText() else active.schedule()
        }
    }

    private fun reconcile() {
        val enabled = prefs.getBoolean(BydMusicTextSettings.KEY_ENABLED, false)
        val shouldRun = !closed && supported && enabled && usage.connected
        if (!shouldRun) {
            run?.let { active ->
                run = null
                active.invalidate()
                active.worker.handler.post { active.stop() }
            }
            if (!supported && enabled && usage.connected && !unsupportedReported) {
                unsupportedReported = true
                report("unsupported firmware; forwarding inactive")
            }
            if (!enabled || !usage.connected) unsupportedReported = false
            return
        }
        if (run != null) return
        try {
            val active = Run(workerFactory())
            run = active
            active.worker.handler.post { active.start() }
        } catch (error: Exception) {
            report("worker unavailable: ${error.javaClass.simpleName}")
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        usage = Usage(false, false, false)
        reconcile()
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        // Diagnostics are observational; a closed log must not affect music or connection.
        runCatching { diagnostic("BYD music text: $message") }
    }

    private data class Usage(val connected: Boolean, val playing: Boolean, val focusHeld: Boolean)

    private inner class Run(val worker: MusicTextWorker) {
        private var source: MusicTextSource? = null
        private var writer: MusicTextWriter? = null
        private var registered = false
        private var stopped = false
        private var failed = false
        private var title: String? = null
        private var lastSent: String? = null
        private var events = 0
        private var changes = 0
        private var writes = 0
        private var sourceRejected = false
        @Volatile private var revision = 0
        private var pendingRevision = 0
        private var scheduled = false
        private val send = Runnable { scheduled = false; forward() }
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != TRACK_EVENT || !current() || failed) return
                try {
                    events++
                    if (source?.accepts() != true) {
                        clearText()
                        if (!sourceRejected) report("Bluetooth source unavailable or ambiguous; waiting")
                        sourceRejected = true
                        return
                    }
                    if (sourceRejected) report("Bluetooth source accepted")
                    sourceRejected = false
                    @Suppress("DEPRECATION")
                    val metadata = intent.getParcelableExtra<MediaMetadata>(EXTRA_METADATA) ?: return
                    val next = instrumentText(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
                    if (next.isNullOrEmpty()) {
                        clearText()
                        return
                    }
                    if (next != title) { title = next; changes++ }
                    if (events == 1 || events % 25 == 0) {
                        report("received=$events changed=$changes titleUnits=${next.length}")
                    }
                    // Paused music does not retain a lyric to replay on resume.
                    if (!usage.playing) clearText() else schedule()
                } catch (error: Exception) {
                    report("metadata rejected: ${error.javaClass.simpleName}")
                }
            }
        }

        fun current(): Boolean = !closed && run === this && usage.connected

        fun invalidate() { revision++ }

        fun start() {
            if (!current()) { stop(); return }
            try {
                writer = writerFactory()
                source = sourceFactory(worker.handler)
                @Suppress("DEPRECATION")
                app.registerReceiver(receiver, IntentFilter(TRACK_EVENT), Manifest.permission.BLUETOOTH, worker.handler)
                registered = true
                report("receiver and instrument SDK ready; output is not an instrument acknowledgement")
            } catch (error: Exception) {
                fail(error)
            } catch (error: LinkageError) {
                fail(error)
            }
        }

        fun clearText() {
            title = null
            lastSent = null
            worker.handler.removeCallbacks(send)
            scheduled = false
        }

        fun resetSent() { lastSent = null }

        fun schedule() {
            if (!current() || failed || !usage.playing || !usage.focusHeld || title == null) {
                worker.handler.removeCallbacks(send)
                scheduled = false
                return
            }
            pendingRevision = revision
            if (!scheduled) {
                scheduled = true
                worker.handler.postDelayed(send, COALESCE_MS)
            }
        }

        private fun forward() {
            if (!current() || failed || pendingRevision != revision || !usage.playing || !usage.focusHeld) return
            try {
                if (source?.accepts() != true) { clearText(); return }
                val next = title ?: return
                if (next == lastSent) return
                // Recheck after the Bluetooth binder call. Focus/connection can change on another thread.
                if (!current() || pendingRevision != revision || !usage.playing || !usage.focusHeld) return
                val result = writer?.send(next) ?: return
                if (result != 0) {
                    fail(IllegalStateException("instrument result=$result"), "instrument rejected result=$result")
                    return
                }
                lastSent = next
                writes++
                if (writes == 1 || writes % 25 == 0) report("calls=$writes received=$events changed=$changes result=$result")
            } catch (error: Exception) {
                fail(error)
            } catch (error: LinkageError) {
                fail(error)
            }
        }

        private fun fail(error: Throwable, message: String = "forwarding disabled for this connection: ${error.javaClass.simpleName}") {
            failed = true
            clearText()
            releaseReceiver()
            runCatching { source?.close() }
            source = null
            writer = null
            report(message)
            // No repeating retries after SDK/permission failure. Reconnect or toggle to try again.
        }

        private fun releaseReceiver() {
            if (registered) runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }

        fun stop() {
            if (stopped) return
            stopped = true
            clearText()
            releaseReceiver()
            runCatching { source?.close() }
            source = null
            writer = null
            report("stopped received=$events changed=$changes calls=$writes")
            worker.close()
        }
    }

    companion object {
        private const val TAG = "DiPlay-BYD-MusicText"
        internal const val TRACK_EVENT = "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT"
        internal const val EXTRA_METADATA = "android.bluetooth.avrcp-controller.profile.extra.METADATA"
        internal const val INSTRUMENT_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_COMMON"
        internal const val COALESCE_MS = 150L

        fun create(context: Context, expectedBluetoothAddress: String?, diagnostic: (String) -> Unit): BydMusicTextRelay {
            val app = context.applicationContext
            // First supported firmware is DiLink 4.0 / Android 10. Its legacy broadcast and SDK
            // have been inspected; other versions need their own permission/protocol verification.
            val supported = Build.VERSION.SDK_INT == 29 && BydSettingsAvailability.available(app)
            return BydMusicTextRelay(app, supported,
                workerFactory = {
                    val thread = HandlerThread("diplay-byd-music-text").apply { start() }
                    object : MusicTextWorker {
                        override val handler = Handler(thread.looper)
                        override fun close() { thread.quitSafely() }
                    }
                },
                sourceFactory = { BydBluetoothMusicSource(app, expectedBluetoothAddress) },
                writerFactory = {
                    check(app.checkSelfPermission(INSTRUMENT_PERMISSION) == PackageManager.PERMISSION_GRANTED)
                    val sdk = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
                    val device = sdk.getMethod("getInstance", Context::class.java).invoke(null, app)
                    val method = sdk.getMethod("sendMusicName", String::class.java)
                    MusicTextWriter { text -> (method.invoke(device, text) as Number).toInt() }
                },
                diagnostic = diagnostic,
            )
        }

        /** Matches the stock 96-byte UTF-16LE bound without splitting a surrogate pair. */
        internal fun instrumentText(raw: String?): String? {
            if (raw == null) return null
            val text = StringBuilder(48)
            var offset = 0
            while (offset < raw.length && offset < 512 && text.length < 48) {
                val codePoint = raw.codePointAt(offset)
                offset += Character.charCount(codePoint)
                // Reject unpaired surrogates and control characters; line breaks become spaces.
                if (codePoint in 0xD800..0xDFFF) continue
                if (Character.isWhitespace(codePoint)) {
                    if (text.isNotEmpty() && text.last() != ' ') text.append(' ')
                    continue
                }
                if (Character.isISOControl(codePoint)) continue
                val units = Character.charCount(codePoint)
                if (text.length + units > 48) break
                text.appendCodePoint(codePoint)
            }
            return text.toString().trim().takeIf { it.isNotEmpty() }
        }
    }
}

internal interface MusicTextWorker : Closeable { val handler: Handler }
internal fun interface MusicTextWriter { fun send(text: String): Int }
internal interface MusicTextSource : Closeable { fun accepts(): Boolean }
