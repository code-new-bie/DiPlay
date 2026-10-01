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
    fun vehicleKeysWinOverTheAndroidMode() {
        // The keys flip with the car while uiMode trails by about a second, so uiMode must not
        // decide the appearance the head unit reports to the iPhone.
        assertFalse(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_YES, "1", "0"))
        assertTrue(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_NO, "2", "1"))
    }

    @Test
    fun vehicleKeysDriveTheModeForEverySignal() {
        val undefined = Configuration.UI_MODE_NIGHT_UNDEFINED
        assertFalse(resolveCarPlayDarkMode(undefined, "1", null))
        assertTrue(resolveCarPlayDarkMode(undefined, "2", null))
        assertTrue(resolveCarPlayDarkMode(undefined, "0", "0"))
        assertFalse(resolveCarPlayDarkMode(undefined, "0", "1"))
    }

    @Test
    fun androidModeIsTheFallbackWhenTheModeKeyIsMissing() {
        assertTrue(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_YES, null, "1"))
        assertFalse(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_NO, null, "0"))
        assertTrue(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_YES, "0", null))
    }

    @Test
    fun unknownOrMissingVehicleStateStaysLight() {
        val undefined = Configuration.UI_MODE_NIGHT_UNDEFINED
        assertFalse(resolveCarPlayDarkMode(undefined, null, null))
        assertFalse(resolveCarPlayDarkMode(undefined, "unexpected", "1"))
    }
}
