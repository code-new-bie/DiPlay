package com.shilapi.xcertplay

import android.content.res.Configuration

internal fun isDarkMode(uiMode: Int): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/**
 * The CarPlay appearance for one sample.
 *
 * The BYD keys decide whenever the head unit publishes them: `sys_screen_mode` 1 = light,
 * 2 = dark, 0 = follow the vehicle signal, in which case `sys_day_or_night` (0 = dark, 1 = light)
 * answers. They flip the moment the car switches, while Android's `uiMode` follows about a second
 * later, so treating `uiMode` as authoritative would send the previous appearance first. `uiMode`
 * is the fallback for head units that never publish the keys.
 *
 * This is the resolution order the appearance patch that retested as working used; keep it.
 */
internal fun resolveCarPlayDarkMode(
    uiMode: Int,
    screenMode: String?,
    vehicleDayOrNight: String?,
): Boolean = when (screenMode) {
    "1" -> false
    "2" -> true
    "0" -> when (vehicleDayOrNight) {
        "0" -> true
        "1" -> false
        else -> isDarkMode(uiMode)
    }
    else -> isDarkMode(uiMode)
}
