package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MfiTargetAvailabilityTest {
    @Test fun completeAssetsAreAvailableAndBothStreamsAreClosed() {
        var closed = 0
        val opened = mutableListOf<String>()
        assertTrue(MfiTargetAvailability.hasLocalAssets { path ->
            opened.add(path)
            object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { closed++; super.close() }
            }
        })
        assertEquals(listOf("offline-mfi/identity.pk8", "offline-mfi/certificate.p7b"), opened)
        assertEquals(2, closed)
    }

    @Test fun missingOrEmptyAssetsDisableLocal() {
        for (unavailable in listOf("identity.pk8", "certificate.p7b")) {
            assertFalse(MfiTargetAvailability.hasLocalAssets { path ->
                if (path.endsWith(unavailable)) throw FileNotFoundException(path)
                ByteArrayInputStream(byteArrayOf(1))
            })
            assertFalse(MfiTargetAvailability.hasLocalAssets { path ->
                ByteArrayInputStream(if (path.endsWith(unavailable)) byteArrayOf() else byteArrayOf(1))
            })
        }
    }

    @Test fun choicesAndFreshInstallDefaultsFollowBundledAssets() {
        assertEquals(MfiTarget.entries, MfiTargetAvailability.availableTargets(true))
        assertEquals(listOf(MfiTarget.USB_CH341, MfiTarget.I2C, MfiTarget.REMOTE),
            MfiTargetAvailability.availableTargets(false))
        assertEquals(MfiTarget.LOCAL, MfiTargetAvailability.resolve(null, true))
        assertEquals(MfiTarget.USB_CH341, MfiTargetAvailability.resolve(null, false))
        assertEquals(MfiTarget.USB_CH341, MfiTargetAvailability.resolve(MfiTarget.LOCAL, false))
    }

    @Test fun externalProvidersKeepTheirSavedChoiceWithOrWithoutAssets() {
        for (target in listOf(MfiTarget.USB_CH341, MfiTarget.I2C, MfiTarget.REMOTE)) {
            assertEquals(target, MfiTargetAvailability.resolve(target, true))
            assertEquals(target, MfiTargetAvailability.resolve(target, false))
        }
    }
}
