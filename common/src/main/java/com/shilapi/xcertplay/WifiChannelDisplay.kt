package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WifiChannelPreference

/** A channel's operating frequency does not tell us the negotiated channel width. */
object WifiChannelDisplay {
    fun label(context: Context, channel: Int): String {
        val frequency = WifiChannelPreference.frequency(channel)
            ?: return context.getString(R.string.feature_auto)
        val band = if (frequency < 5000) "2.4 GHz" else "5 GHz"
        return context.getString(R.string.feature_wifi_channel_option, band, channel, frequency)
    }
}
