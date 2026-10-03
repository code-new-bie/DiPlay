package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings

internal object DiPlayBluetooth {
    fun localAddress(context: Context): String? {
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.address }.getOrNull()
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }

    /**
     * The head unit's own Bluetooth name, which is what the driver sees in the iPhone's Bluetooth
     * list. Used as the CarPlay vehicle name until the user saves one.
     */
    fun localName(context: Context): String? {
        val adapter = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.name
        }.getOrNull()
        val setting = runCatching {
            Settings.Secure.getString(context.contentResolver, "device_name")
        }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull { it.isNotBlank() }
    }
}
