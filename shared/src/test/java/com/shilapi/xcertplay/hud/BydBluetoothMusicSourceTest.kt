package com.shilapi.xcertplay.hud

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydBluetoothMusicSourceTest {
    private lateinit var app: Application
    private lateinit var adapter: BluetoothAdapter
    private lateinit var phone: BluetoothDevice
    private lateinit var other: BluetoothDevice
    private var devices = listOf<BluetoothDevice>()
    private val profile = object : BluetoothProfile {
        override fun getConnectedDevices(): List<BluetoothDevice> = devices
        override fun getDevicesMatchingConnectionStates(states: IntArray): List<BluetoothDevice> = devices
        override fun getConnectionState(device: BluetoothDevice): Int =
            if (device in devices) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED
    }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH)
        adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setProfileProxy(12, profile)
        phone = adapter.getRemoteDevice("11:22:33:44:55:66")
        other = adapter.getRemoteDevice("11:22:33:44:55:77")
    }

    private fun source(expected: String?): BydBluetoothMusicSource = BydBluetoothMusicSource(app, expected).also {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun wirelessRequiresExactlyTheSelectedPhone() {
        val source = source(phone.address)
        devices = listOf(other)
        assertFalse(source.accepts())
        devices = listOf(phone)
        assertTrue(source.accepts())
        source.close()
    }

    @Test
    fun missingOrMultipleSourcesCannotBeAttributedToCarPlay() {
        val source = source(phone.address)
        assertFalse(source.accepts())
        devices = listOf(phone, other)
        assertFalse(source.accepts())
        devices = listOf(phone)
        assertTrue(source.accepts())
        source.close()
    }

    @Test
    fun wiredPinsTheFirstSoleSourceForThisConnection() {
        val source = source(null)
        devices = listOf(phone)
        assertTrue(source.accepts())
        devices = listOf(other)
        assertFalse(source.accepts())
        source.close()
    }

    @Test
    fun closingCannotLeaveAnAcceptedProxy() {
        val source = source(phone.address)
        devices = listOf(phone)
        assertTrue(source.accepts())
        source.close()
        assertFalse(source.accepts())
        assertFalse(shadowOf(adapter).hasActiveProfileProxy(12))
    }
}
