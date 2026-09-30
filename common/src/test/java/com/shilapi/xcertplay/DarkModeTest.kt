package com.shilapi.xcertplay

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkModeTest {
    @Test
    fun darkModeIsDetectedWithUnrelatedConfigurationBits() {
        assertTrue(isDarkMode(Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_CAR))
    }

    @Test
    fun lightAndUndefinedModesAreNotDark() {
        assertFalse(isDarkMode(Configuration.UI_MODE_NIGHT_NO))
        assertFalse(isDarkMode(Configuration.UI_MODE_NIGHT_UNDEFINED))
    }

    @Test
    fun liveAndroidModeWinsOverTheVehicleKeys() {
        // The head unit's own UI follows the system uiMode, and it is the source the iPhone
        // accepted at connection time; the BYD keys must not override it.
        assertTrue(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_YES, "1", "0"))
        assertFalse(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_NO, "2", "1"))
    }

    @Test
    fun vehicleKeysDriveTheModeWhenAndroidDoesNotDefineOne() {
        val undefined = Configuration.UI_MODE_NIGHT_UNDEFINED
        assertFalse(resolveCarPlayDarkMode(undefined, "1", null))
        assertTrue(resolveCarPlayDarkMode(undefined, "2", null))
        assertTrue(resolveCarPlayDarkMode(undefined, "0", "0"))
        assertFalse(resolveCarPlayDarkMode(undefined, "0", "1"))
    }

    @Test
    fun dayNightSignalAloneIsEnoughWhenTheModeKeyIsMissing() {
        val undefined = Configuration.UI_MODE_NIGHT_UNDEFINED
        assertTrue(resolveCarPlayDarkMode(undefined, null, "0"))
        assertFalse(resolveCarPlayDarkMode(undefined, null, "1"))
    }

    @Test
    fun unknownOrMissingVehicleStateStaysLight() {
        val undefined = Configuration.UI_MODE_NIGHT_UNDEFINED
        assertFalse(resolveCarPlayDarkMode(undefined, null, null))
        assertFalse(resolveCarPlayDarkMode(undefined, "unexpected", "1"))
    }
}
