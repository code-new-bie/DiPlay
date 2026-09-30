package com.shilapi.xcertplay.hud

import android.content.Context

/** Opt-in forwarding of the stock Bluetooth music text, including lyrics supplied as titles. */
object BydMusicTextSettings {
    internal const val PREFS = "diplay_byd_music_text"
    internal const val KEY_ENABLED = "forward_music_text"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    internal fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
