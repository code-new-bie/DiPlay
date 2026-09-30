package com.shilapi.xcertplay.media

/**
 * Jitter buffer for streams mapped to the media channel. Wireless CarPlay delivers audio over the same Wi-Fi link as
 * video; radio gaps of several hundred milliseconds are normal, so music needs a buffer that
 * outlasts them. Calls, Siri and navigation prompts keep the small low-latency buffer.
 */
object MediaAudioBuffer {
    const val DEFAULT_MILLIS = 300
    val presets = listOf(DEFAULT_MILLIS, 500, 1000)

    /** Room above the start level: bursts after a gap and the grown resume level must fit. */
    private const val HEADROOM_MILLIS = 500
    /** Each starvation raises the media resume level by this much audio. */
    const val REBUFFER_STEP_MILLIS = 200
    private const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
    private const val MIN_START_BUFFER_BYTES = 4 * 1024

    fun sanitize(millis: Int): Int = millis.takeIf { it in presets } ?: DEFAULT_MILLIS

    data class Plan(val trackBufferBytes: Int, val startBytes: Int)

    /** AudioTrack capacity and the amount to queue before play() for one output stream. */
    fun plan(isMedia: Boolean, sampleRate: Int, channels: Int, minBufferBytes: Int, mediaMillis: Int): Plan {
        val lowLatency = Plan(
            trackBufferBytes = maxOf(minBufferBytes * 4, MIN_TRACK_BUFFER_BYTES),
            startBytes = maxOf(minBufferBytes, MIN_START_BUFFER_BYTES),
        )
        if (!isMedia) return lowLatency
        val bytesPerSecond = sampleRate.toLong() * channels.coerceIn(1, 2) * 2
        val start = (bytesPerSecond * sanitize(mediaMillis) / 1000).toInt()
        val capacity = (bytesPerSecond * (sanitize(mediaMillis) + HEADROOM_MILLIS) / 1000).toInt()
        return Plan(
            trackBufferBytes = maxOf(capacity, lowLatency.trackBufferBytes),
            startBytes = maxOf(start, lowLatency.startBytes),
        )
    }

    /**
     * The device may grant a smaller AudioTrack than requested. Writes block while the track is
     * paused and full, so the start level must stay below the real capacity or play() never runs.
     */
    fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int {
        if (actualCapacityBytes <= 0) return plannedStartBytes
        return minOf(plannedStartBytes, actualCapacityBytes - writeChunkBytes).coerceAtLeast(writeChunkBytes)
    }

    /**
     * Resume level for a media stream that has starved [starvations] times: the configured level
     * plus one [REBUFFER_STEP_MILLIS] step per starvation, capped by the largest preset and by what
     * the track can actually hold. Holding more audio before resuming rides out the radio gaps that
     * caused the starvation instead of starving again a few hundred milliseconds later.
     */
    fun resumeStartBytes(
        configuredStartBytes: Int,
        capacityBytes: Int,
        bytesPerSecond: Int,
        starvations: Int,
        writeChunkBytes: Int,
    ): Int {
        if (bytesPerSecond <= 0 || starvations <= 0) return configuredStartBytes
        val capBytes = bytesPerSecond.toLong() * presets.last() / 1000
        val grown = (configuredStartBytes.toLong() +
            bytesPerSecond.toLong() * REBUFFER_STEP_MILLIS * starvations / 1000)
            .coerceAtMost(capBytes)
            .toInt()
        return maxOf(configuredStartBytes, startBytesFor(grown, capacityBytes, writeChunkBytes))
    }
}
