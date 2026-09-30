package com.shilapi.xcertplay

import android.content.res.Configuration

internal fun isDarkMode(uiMode: Int): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/**
 * The CarPlay appearance for one sample.
 *
 * The live Android `uiMode` wins whenever the system defines it: the head unit's own UI follows
 * that value, and it is the source the iPhone accepted at connection time before the locale
 * override started pinning the Activity configuration. The BYD keys (`sys_screen_mode` is
 * 0 = follow the light sensor, 1 = light, 2 = dark, `sys_day_or_night` 0 = dark, 1 = light) stay
 * as the fallback for head units that never publish a defined uiMode.
 */
internal fun resolveCarPlayDarkMode(
    uiMode: Int,
    screenMode: String?,
    vehicleDayOrNight: String?,
): Boolean {
    when (uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> return true
        Configuration.UI_MODE_NIGHT_NO -> return false
    }
    return when (screenMode) {
        "1" -> false
        "2" -> true
        "0", null -> when (vehicleDayOrNight) {
            "0" -> true
            "1" -> false
            else -> isDarkMode(uiMode)
        }
        else -> isDarkMode(uiMode)
    }
}
