package com.shilapi.xcertplay

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayUsbAttachTest {
    @Test
    fun ch341AuthenticationDeviceDoesNotSelectWiredCarPlay() {
        assertFalse(CarPlayUsbAttach.isIphone(attachIntent(0x1a86, 0x5512)))
    }

    @Test
    fun otherUsbDevicesDoNotSelectWiredCarPlay() {
        assertFalse(CarPlayUsbAttach.isIphone(attachIntent(0x1234, 0x12a8)))
    }

    @Test
    fun iphoneAttachmentStillSelectsWiredCarPlay() {
        assertTrue(CarPlayUsbAttach.isIphone(attachIntent(IphoneUsbMatcher.APPLE_VENDOR_ID, 0x12a8)))
    }

    @Test
    fun appleProductChangeDuringReenumerationStillMatches() {
        assertTrue(CarPlayUsbAttach.isIphone(attachIntent(IphoneUsbMatcher.APPLE_VENDOR_ID, 0x12a9)))
    }

    @Test
    fun attachmentWithoutDeviceDoesNotChangeTransport() {
        assertFalse(CarPlayUsbAttach.isIphone(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)))
    }

    @Test
    fun wrongParcelableTypeDoesNotChangeTransport() {
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .putExtra(UsbManager.EXTRA_DEVICE, Bundle())
        assertFalse(CarPlayUsbAttach.isIphone(intent))
    }

    @Test
    fun iphoneDetachmentDoesNotSelectWiredCarPlay() {
        val intent = attachIntent(IphoneUsbMatcher.APPLE_VENDOR_ID, 0x12a8)
            .setAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        assertFalse(CarPlayUsbAttach.isIphone(intent))
    }

    @Test
    fun normalLaunchDoesNotSelectWiredCarPlay() {
        assertFalse(CarPlayUsbAttach.isIphone(Intent()))
    }

    private fun attachIntent(vendorId: Int, productId: Int): Intent {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mVendorId", vendorId)
        ReflectionHelpers.setField(device, "mProductId", productId)
        return Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
    }
}
