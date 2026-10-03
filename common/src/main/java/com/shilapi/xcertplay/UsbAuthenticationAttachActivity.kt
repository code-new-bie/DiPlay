package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle

/** Authentication hardware must enter through the same connection policy as the launcher. */
class UsbAuthenticationAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A controller waiting for CH341 already polls for it. Do not replace that session
        // or bring the home page over an active projection.
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            !CarPlayBackgroundSession.hasSession()) {
            startActivity(Intent(this, DiPlayActivity::class.java).apply {
                action = UsbManager.ACTION_USB_DEVICE_ATTACHED
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        finish()
    }
}
