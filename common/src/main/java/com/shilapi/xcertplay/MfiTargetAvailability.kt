package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.InputStream

/** Availability is determined by this APK's assets, never by credentials left in app storage. */
internal object MfiTargetAvailability {
    private val LOCAL_ASSET_PATHS = listOf("offline-mfi/identity.pk8", "offline-mfi/certificate.p7b")

    fun hasLocalAssets(context: Context): Boolean = hasLocalAssets { context.assets.open(it) }

    internal fun hasLocalAssets(openAsset: (String) -> InputStream): Boolean = runCatching {
        LOCAL_ASSET_PATHS.all { path -> openAsset(path).use { it.read() != -1 } }
    }.getOrDefault(false)

    fun availableTargets(context: Context): List<MfiTarget> = availableTargets(hasLocalAssets(context))

    internal fun availableTargets(localAssetsAvailable: Boolean): List<MfiTarget> =
        MfiTarget.entries.filter { it != MfiTarget.LOCAL || localAssetsAvailable }

    internal fun resolve(savedTarget: MfiTarget?, localAssetsAvailable: Boolean): MfiTarget = when {
        savedTarget != null && savedTarget != MfiTarget.LOCAL -> savedTarget
        localAssetsAvailable -> MfiTarget.LOCAL
        else -> MfiTarget.USB_CH341
    }
}
