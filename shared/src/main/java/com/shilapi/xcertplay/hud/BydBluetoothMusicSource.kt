package com.shilapi.xcertplay.hud

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

/** Read-only source guard; never connects, selects an active phone, or sends AVRCP controls. */
internal class BydBluetoothMusicSource(context: Context, private val expectedAddress: String?) : MusicTextSource {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    @Volatile private var proxy: BluetoothProfile? = null
    @Volatile private var closed = false
    private var acceptedAddress = expectedAddress
    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
            synchronized(this@BydBluetoothMusicSource) {
                if (closed) runCatching { adapter?.closeProfileProxy(AVRCP_CONTROLLER, service) } else proxy = service
            }
        }
        override fun onServiceDisconnected(profile: Int) { proxy = null }
    }

    init {
        // AVRCP_CONTROLLER is hidden in the public SDK, but getProfileProxy() and the
        // BluetoothProfile read interface are public. Profile 12 exists in the inspected API 29 firmware.
        check(adapter?.getProfileProxy(context, listener, AVRCP_CONTROLLER) == true)
    }

    override fun accepts(): Boolean {
        if (closed) return false
        val devices = proxy?.connectedDevices ?: return false
        // TRACK_EVENT has no device extra. Multiple sources cannot be attributed safely.
        val device = devices.singleOrNull() ?: return false
        // Wired sessions have no CarPlay Bluetooth identity; pin the sole source for this
        // connection rather than silently accepting a different phone after a device switch.
        if (acceptedAddress == null) acceptedAddress = device.address
        return device.address.equals(acceptedAddress, ignoreCase = true)
    }

    override fun close() = synchronized(this) {
        closed = true
        proxy?.let { adapter?.closeProfileProxy(AVRCP_CONTROLLER, it) }
        proxy = null
    }

    companion object { private const val AVRCP_CONTROLLER = 12 }
}
