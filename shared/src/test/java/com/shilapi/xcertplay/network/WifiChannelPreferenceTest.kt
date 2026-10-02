package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.*
import org.junit.Test

class WifiChannelPreferenceTest {
    @Test fun explicitChannelPrecedesRememberedAndStationChannels() {
        val remembered = P2pStartupRecovery.rememberedFrequency(2437)
        for (channel in WifiChannelPreference.channels.filter { it != 0 }) {
            val plan = P2pStartupRecovery.plan(5180, remembered, channel)
            assertEquals(WifiChannelPreference.frequency(channel), plan.first().frequencyMHz)
            assertEquals(plan.size, plan.distinctBy { it.frequencyMHz }.size)
        }
    }

    @Test fun autoAndInvalidSelectionsRetainOriginalPlan() {
        val original = P2pStartupRecovery.plan(2437)
        for (channel in listOf(0, -1, 15, 35, 200)) {
            assertEquals(original, P2pStartupRecovery.plan(2437, requestedChannel = channel))
        }
        assertEquals(2484, WifiChannelPreference.frequency(14))
        assertEquals(5500, WifiChannelPreference.frequency(100))
        assertEquals(5885, WifiChannelPreference.frequency(177))
    }

    @Test fun rejectedUserChannelUsesExistingRecovery() {
        val attempted = mutableListOf<Int?>()
        val result = P2pStartupRecovery.create(null, {}, requestedChannel = 100) {
            attempted += it.frequencyMHz
            if (it.frequencyMHz == 5500) throw P2pCreateRejected(WifiP2pManager.ERROR, "unsupported")
        }
        assertEquals(listOf(5500, 5180), attempted)
        assertEquals(5180, result.frequencyMHz)
    }
}
