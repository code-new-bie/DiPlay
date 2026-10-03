package com.shilapi.xcertplay

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.IntentCompat
import com.shilapi.xcertplay.transport.IphoneUsbMatcher

/** USB authentication hardware does not determine the iPhone's CarPlay transport. */
internal object CarPlayUsbAttach {
    private val iphoneMatcher = IphoneUsbMatcher.appleVendor()

    fun isIphone(intent: Intent): Boolean {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            ?: return false
        return iphoneMatcher.matches(device.vendorId, device.productId)
    }
}
