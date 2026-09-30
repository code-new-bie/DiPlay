package com.shilapi.xcertplay.hud

import android.content.Context

/** Device identification for the settings entry; navigation keeps its own capability checks. */
object BydSettingsAvailability {
    fun available(context: Context): Boolean =
        BydOutputSettings.available(context) || runCatching {
            context.packageManager.getPackageInfo("com.byd.bydlogtool", 0)
        }.isSuccess
}
