package com.shilapi.xcertplay.network

/** Offered channels are requests; Android still enforces radio and country restrictions. */
object WifiChannelPreference {
    val channels: List<Int> = listOf(0) + (1..14) +
        (36..64 step 4) + (100..144 step 4) + (149..177 step 4)

    fun sanitize(channel: Int): Int = channel.takeIf { it in channels } ?: 0

    fun frequency(channel: Int): Int? = when (sanitize(channel)) {
        0 -> null
        14 -> 2484
        in 1..13 -> 2407 + channel * 5
        else -> 5000 + channel * 5
    }
}
