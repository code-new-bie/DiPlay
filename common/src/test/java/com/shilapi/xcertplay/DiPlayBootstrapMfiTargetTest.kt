package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DiPlayBootstrapMfiTargetTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var directory: File
    private lateinit var context: Context
    private var assetReads = 0

    @Before
    fun setUp() {
        resetProcess()
        RuntimeEnvironment.getApplication().getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        directory = temporary.newFolder()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = directory
            override fun getAssets(): AssetManager {
                assetReads++
                throw IllegalStateException("No offline credentials in this synthetic test app")
            }
        }
    }

    @After
    fun tearDown() { resetProcess() }

    private fun resetProcess() = ReflectionHelpers.setStaticField(DiPlayBootstrap::class.java, "ready", false)

    @Test
    fun usbSelectionSurvivesFreshProcessInitialization() {
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        DiPlayBootstrap.ensure(context)
        resetProcess()
        DiPlayBootstrap.ensure(context)
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(context))
        assertEquals(0, assetReads)
        assertFalse(File(directory, "offline-mfi").exists())
    }

    @Test
    fun i2cSelectionSurvivesInitialization() {
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.I2C)
        DiPlayBootstrap.ensure(context)
        assertEquals(MfiTarget.I2C, AirPlayPersistence.loadMfiTarget(context))
        assertEquals(0, assetReads)
    }

    @Test
    fun remoteSelectionSurvivesInitialization() {
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.REMOTE)
        DiPlayBootstrap.ensure(context)
        assertEquals(MfiTarget.REMOTE, AirPlayPersistence.loadMfiTarget(context))
        assertEquals(0, assetReads)
    }

    @Test
    fun brokenOfflineFilesDoNotBlockUsbAuthentication() {
        File(directory, "offline-mfi").mkdir()
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        DiPlayBootstrap.ensure(context)
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(context))
        assertEquals(0, assetReads)
    }

    @Test
    fun unavailableSavedLocalChoiceMigratesToUsbWithoutPreparingCredentials() {
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        DiPlayBootstrap.ensure(context)
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        DiPlayBootstrap.ensure(context)
        assertEquals(1, assetReads)
        assertEquals(MfiTarget.USB_CH341.name,
            context.getSharedPreferences("xcertplay_airplay", 0).getString("mfi_target", null))
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(context))
        assertFalse(File(directory, "offline-mfi-staging").exists())
    }

    @Test
    fun explicitPreparationDoesNotOverwriteTheSavedProvider() {
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        assertThrows(IllegalStateException::class.java) { DiPlayBootstrap.ensure(context, MfiTarget.LOCAL) }
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(context))
    }

    @Test
    fun freshInstallWithoutBundledAssetsDefaultsToUsb() {
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(context))
        // The resolved default is also written, so every reader agrees with the target in use.
        assertEquals(MfiTarget.USB_CH341.name,
            context.getSharedPreferences("xcertplay_airplay", 0).getString("mfi_target", null))
        assertFalse(File(directory, "offline-mfi").exists())
    }

    @Test
    fun previouslyExtractedCredentialsDoNotMakeLocalAvailable() {
        val offline = File(directory, "offline-mfi").apply { mkdir() }
        File(offline, "identity.pk8").writeText("synthetic key")
        File(offline, "certificate.p7b").writeText("synthetic certificate")
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(context))
        assertThrows(IllegalStateException::class.java) { DiPlayBootstrap.ensure(context, MfiTarget.LOCAL) }
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(context))
    }
}
