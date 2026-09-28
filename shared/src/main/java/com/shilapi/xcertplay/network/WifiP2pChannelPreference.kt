package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiAvailableChannel
import android.net.wifi.WifiManager
import android.os.Build

/** Optional Wi-Fi Direct channel to try first when the car is not connected to Wi-Fi. */
object WifiP2pChannelPreference {
    private const val PREFERENCES = "carplay_wifi_p2p_success"
    private const val KEY = "user_preferred_frequency_mhz"
    private val commonNonDfs = listOf(5180, 5200, 5220, 5240,
        5745, 5765, 5785, 5805, 5825) + (1..11).map { 2407 + it * 5 }
    private val fiveGhzChannels = (36..64 step 4).toList() +
        (100..144 step 4).toList() + (149..177 step 4).toList()

    internal fun supportsFrequency(frequencyMHz: Int): Boolean =
        (frequencyMHz in 2412..2472 && (frequencyMHz - 2412) % 5 == 0) ||
            (frequencyMHz >= 5000 && (frequencyMHz - 5000) / 5 in fiveGhzChannels &&
                (frequencyMHz - 5000) % 5 == 0)

    /** On older Android versions the P2P GO regulatory channel query is unavailable. */
    fun availableFrequencies(context: Context): List<Int> {
        if (Build.VERSION.SDK_INT >= 34) {
            val reported = runCatching {
                context.getSystemService(WifiManager::class.java)
                    ?.getAllowedChannels(0, WifiAvailableChannel.OP_MODE_WIFI_DIRECT_GO)
                    ?.map { it.frequencyMhz }
            }.getOrNull()
            if (reported != null) return reported.filter(::supportsFrequency).distinct().sortedWith(
                compareByDescending<Int> { it >= 5000 }.thenBy { it },
            )
        }
        return commonNonDfs
    }

    fun load(context: Context): Int? = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getInt(KEY, 0).takeIf(::supportsFrequency)

    fun save(context: Context, frequencyMHz: Int?) {
        require(frequencyMHz == null || supportsFrequency(frequencyMHz))
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putInt(KEY, frequencyMHz ?: 0).apply()
    }
}
