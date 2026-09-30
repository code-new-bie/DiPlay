package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MfiTargetPreferenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val localDirectory: File get() = File(context.noBackupFilesDir, "offline-mfi")

    @Before
    fun setUp() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        localDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        localDirectory.deleteRecursively()
    }

    @Test
    fun sourceOnlyInstallDefaultsToUsbChip() {
        DiPlayBootstrap.ensure(context)
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(context))
    }

    @Test
    fun installedOfflineIdentityRemainsTheDefaultForExistingUsers() {
        check(localDirectory.mkdirs())
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(context))
    }

    @Test
    fun explicitSelectionTakesPriorityOverInstalledOfflineIdentity() {
        check(localDirectory.mkdirs())
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.REMOTE)
        assertEquals(MfiTarget.REMOTE, AirPlayPersistence.loadMfiTarget(context))
    }
}
