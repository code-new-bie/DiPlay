package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAudioBufferTest {
    @Test
    fun `music buffers the chosen delay plus headroom`() {
        // 48 kHz stereo 16-bit = 192 000 bytes/s.
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
        assertEquals(192_000, plan.trackBufferBytes)
    }

    @Test
    fun `all streams mapped to media receive the music buffer`() {
        val selection = AudioChannelMapper.map(
            "compatibility", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS,
        )
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
    }

    @Test
    fun `calls and prompts keep the low-latency buffer`() {
        val plan = MediaAudioBuffer.plan(false, 16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000)
        assertEquals(4 * 1024, plan.startBytes)
        assertEquals(16 * 1024, plan.trackBufferBytes)
        val guidance = AudioChannelMapper.map("default", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS)
        assertEquals(plan, MediaAudioBuffer.plan(guidance.channel == AudioChannel.MEDIA,
            16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000))
    }

    @Test
    fun `unknown delay falls back to the default`() {
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.sanitize(250))
        assertEquals(57_600, MediaAudioBuffer.plan(true, 48_000, 2, 7_680, mediaMillis = 42).startBytes)
    }

    @Test
    fun `start level stays below a smaller granted buffer`() {
        assertEquals(98_000, MediaAudioBuffer.startBytesFor(192_000, 100_000, 2_048 - 48))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 134_400, 2_048))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 0, 2_048))
    }

    @Test
    fun `each starvation raises the resume level up to the track`() {
        // 300 ms preset: 57 600 bytes to start, an 800 ms track to hold the grown level.
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 7_680, mediaMillis = 300)
        val configured = MediaAudioBuffer.startBytesFor(plan.startBytes, plan.trackBufferBytes, 2_048)
        assertEquals(57_600, configured)
        // 300 -> 500 -> 700 ms of audio.
        assertEquals(96_000,
            MediaAudioBuffer.resumeStartBytes(configured, plan.trackBufferBytes, 192_000, 1, 2_048))
        assertEquals(134_400,
            MediaAudioBuffer.resumeStartBytes(configured, plan.trackBufferBytes, 192_000, 2, 2_048))
        // Never past what the track can actually hold.
        assertEquals(plan.trackBufferBytes - 2_048,
            MediaAudioBuffer.resumeStartBytes(configured, plan.trackBufferBytes, 192_000, 9, 2_048))
        // The largest preset stays the ceiling even on a roomier track.
        assertEquals(192_000,
            MediaAudioBuffer.resumeStartBytes(configured, 4_000_000, 192_000, 9, 2_048))
    }

    @Test
    fun `resume level never drops below the configured one`() {
        assertEquals(57_600, MediaAudioBuffer.resumeStartBytes(57_600, 0, 0, 3, 2_048))
        assertEquals(57_600, MediaAudioBuffer.resumeStartBytes(57_600, 134_400, 192_000, 0, 2_048))
    }
}
