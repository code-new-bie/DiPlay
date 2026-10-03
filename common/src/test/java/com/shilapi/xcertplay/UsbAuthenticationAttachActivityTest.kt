package com.shilapi.xcertplay

import android.content.Intent
import android.hardware.usb.UsbManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class UsbAuthenticationAttachActivityTest {
    @After
    fun clearSession() {
        CarPlayBackgroundSession.clear()
    }

    @Test
    fun idleAttachmentOpensHomeInsteadOfProjection() {
        val activity = Robolectric.buildActivity(UsbAuthenticationAttachActivity::class.java,
            Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)).create().get()
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, next.component?.className)
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, next.action)
        assertNull(next.getStringExtra("page"))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun waitingOrActiveSessionIsNotRelaunched() {
        val stop: (() -> Unit) -> Unit = { it() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        val activity = Robolectric.buildActivity(UsbAuthenticationAttachActivity::class.java,
            Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)).create().get()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun unrelatedLaunchDoesNotConnect() {
        val activity = Robolectric.buildActivity(UsbAuthenticationAttachActivity::class.java,
            Intent()).create().get()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }
}
