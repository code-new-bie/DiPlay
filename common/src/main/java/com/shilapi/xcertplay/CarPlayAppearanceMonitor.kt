package com.shilapi.xcertplay

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/** Follows the head unit's actual appearance, including switches that do not update Android uiMode. */
internal class CarPlayAppearanceMonitor(
    context: Context,
    private val mainHandler: Handler,
    initialUiMode: Int,
    private val onDarkModeChanged: (Boolean) -> Unit,
) {
    private val resolver = context.contentResolver
    private val workerThread = HandlerThread("carplay-appearance")
    private val uri = Uri.parse("content://carsettings/global")
    @Volatile private var uiMode = initialUiMode
    @Volatile private var running = false
    private lateinit var worker: Handler
    private var observer: ContentObserver? = null
    private var lastMode: Boolean? = null
    private var providerFailureLogged = false
    private val check = object : Runnable {
        override fun run() {
            if (!running) return
            val mode = try {
                val screenMode = readValue("sys_screen_mode")
                val dayOrNight = if (screenMode == "0") readValue("sys_day_or_night") else null
                providerFailureLogged = false
                if (screenMode == null) null else resolveCarPlayDarkMode(uiMode, screenMode, dayOrNight)
            } catch (error: RuntimeException) {
                if (!providerFailureLogged) {
                    Log.w(TAG, "Vehicle appearance unavailable; using Android uiMode", error)
                    providerFailureLogged = true
                }
                null
            }
            val resolved = mode ?: isDarkMode(uiMode)
            if (resolved != lastMode) {
                lastMode = resolved
                mainHandler.post { if (running) onDarkModeChanged(resolved) }
            }
            worker.postDelayed(this, if (mode == null) RETRY_INTERVAL_MS else CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        workerThread.start()
        worker = Handler(workerThread.looper)
        observer = object : ContentObserver(worker) {
            override fun onChange(selfChange: Boolean) = refresh()

            override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
        }
        try {
            resolver.registerContentObserver(uri, true, observer!!)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Vehicle appearance observer unavailable; polling remains active", error)
            observer = null
        }
        refresh()
    }

    fun updateUiMode(nextUiMode: Int) {
        uiMode = nextUiMode
        refresh()
    }

    fun stop() {
        if (!running) return
        running = false
        observer?.let(resolver::unregisterContentObserver)
        observer = null
        worker.removeCallbacks(check)
        workerThread.quitSafely()
    }

    private fun refresh() {
        if (!running) return
        worker.removeCallbacks(check)
        worker.post(check)
    }

    private fun readValue(key: String): String? = resolver.query(
        uri,
        arrayOf("value"),
        "key=?",
        arrayOf(key),
        null,
    )?.use { cursor ->
        if (cursor.count == 1 && cursor.moveToFirst()) cursor.getString(0) else null
    }

    private companion object {
        const val TAG = "CarPlayAppearance"
        const val CHECK_INTERVAL_MS = 1_000L
        const val RETRY_INTERVAL_MS = 5_000L
    }
}
