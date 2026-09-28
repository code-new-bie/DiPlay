package com.shilapi.xcertplay

/** Keeps repetitive Now Playing protocol updates useful without logging every frame. */
internal class DiagnosticMessageReducer(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var nextSummaryMillis = Long.MIN_VALUE
    private var pendingNowPlaying = 0

    @Synchronized fun reduce(message: String): String? {
        if (message != NOW_PLAYING_UPDATE) return message
        pendingNowPlaying++
        val now = nowMillis()
        if (now < nextSummaryMillis) return null
        val count = pendingNowPlaying
        pendingNowPlaying = 0
        nextSummaryMillis = now + SUMMARY_INTERVAL_MILLIS
        return "iAP tunnel now-playing updates=$count"
    }

    private companion object {
        const val NOW_PLAYING_UPDATE = "iAP tunnel iap2 rx=0x5001"
        const val SUMMARY_INTERVAL_MILLIS = 10_000L
    }
}
