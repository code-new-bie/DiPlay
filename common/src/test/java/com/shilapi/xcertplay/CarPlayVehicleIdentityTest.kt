package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayVehicleIdentityTest {
    @Before
    fun clearSettings() {
        RuntimeEnvironment.getApplication().getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test
    fun customIdentityPersistsAndIsEncodedForBothAuthenticationProviders() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveVehicleName(context, "宋 PLUS")
        AirPlayPersistence.saveManufacturer(context, " 比亚迪 ")
        AirPlayPersistence.saveModel(context, " 宋 PLUS DMi ")
        AirPlayPersistence.saveAccessorySerialNumber(context, " SONG-PLUS-0001 ")
        AirPlayPersistence.saveNetworkHostName(context, " Song-Plus ")

        for (target in listOf(MfiTarget.LOCAL, MfiTarget.USB_CH341)) {
            val activity = newActivity()
            ReflectionHelpers.setField(activity, "mfiTarget", target)
            val config = refreshedConfig(activity)
            assertEquals(target, config.mfiTarget)
            assertEquals("song-plus", config.hostName)
            val parameters = Iap2ParameterList.parse(
                Iap2IdentificationClient.identificationInformation(config.identification).payload,
            ).asList()
            assertEquals("宋 PLUS\u0000", parameters.single { it.id == 0 }.payload.decodeToString())
            assertEquals("宋 PLUS DMi\u0000", parameters.single { it.id == 1 }.payload.decodeToString())
            assertEquals("比亚迪\u0000", parameters.single { it.id == 2 }.payload.decodeToString())
            assertEquals("SONG-PLUS-0001\u0000", parameters.single { it.id == 3 }.payload.decodeToString())
        }
    }

    @Test
    fun clearingIdentityOverridesRestoresTheSameAutomaticSerial() {
        val activity = newActivity()
        val original = refreshedConfig(activity)
        assertNull(original.hostName)
        AirPlayPersistence.saveManufacturer(activity, "BYD")
        AirPlayPersistence.saveModel(activity, "Song Plus")
        AirPlayPersistence.saveAccessorySerialNumber(activity, "CUSTOM-001")
        AirPlayPersistence.saveNetworkHostName(activity, "song-plus")
        assertEquals("CUSTOM-001", refreshedConfig(activity).identification.serialNumber)

        AirPlayPersistence.saveManufacturer(activity, "")
        AirPlayPersistence.saveModel(activity, "")
        AirPlayPersistence.saveAccessorySerialNumber(activity, "")
        AirPlayPersistence.saveNetworkHostName(activity, "")
        val restored = refreshedConfig(activity)
        assertEquals(original.identification.serialNumber, restored.identification.serialNumber)
        assertEquals("DiPlay", restored.identification.manufacturer)
        assertEquals("DiPlay", restored.identification.modelIdentifier)
        assertNull(restored.hostName)
    }

    @Test
    fun invalidIdentityEditsDoNotOverwriteSavedValues() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveManufacturer(context, "BYD")
        AirPlayPersistence.saveModel(context, "Song Plus")
        AirPlayPersistence.saveAccessorySerialNumber(context, "CUSTOM-001")
        AirPlayPersistence.saveNetworkHostName(context, "song-plus")
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayPersistence.saveManufacturer(context, "BYD\u0000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayPersistence.saveModel(context, "Song\nPlus")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayPersistence.saveAccessorySerialNumber(context, "x".repeat(65))
        }
        for (invalidHost in listOf("-song", "song-", "song_plus", "song.plus", "宋", "x".repeat(64))) {
            assertThrows(IllegalArgumentException::class.java) {
                AirPlayPersistence.saveNetworkHostName(context, invalidHost)
            }
        }
        assertEquals("BYD", AirPlayPersistence.loadManufacturer(context))
        assertEquals("Song Plus", AirPlayPersistence.loadModel(context))
        assertEquals("CUSTOM-001", AirPlayPersistence.loadAccessorySerialNumber(context))
        assertEquals("song-plus", AirPlayPersistence.loadNetworkHostName(context))
        assertTrue(AirPlayPersistence.isValidIdentityText("宋".repeat(64)))
        assertTrue(AirPlayPersistence.isValidNetworkHostName("x".repeat(63)))
        assertFalse(AirPlayPersistence.isValidNetworkHostName("\n"))
    }

    private fun newActivity(): CarPlayHostActivity {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(activity, "airPlayIdentity", AirPlayPersistence.loadIdentity(activity))
        return activity
    }

    private fun refreshedConfig(activity: CarPlayHostActivity): CarPlayRuntimeConfig {
        CarPlayHostActivity::class.java.getDeclaredMethod("refreshVehicleIdentitySettings")
            .apply { isAccessible = true }.invoke(activity)
        return CarPlayHostActivity::class.java.getDeclaredMethod("createRuntimeConfig")
            .apply { isAccessible = true }.invoke(activity) as CarPlayRuntimeConfig
    }

    @Test
    fun renamedVehicleIsEncodedForLocalAndUsbAuthentication() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(activity, "airPlayIdentity", AirPlayIdentity.generate())
        val refresh = CarPlayHostActivity::class.java.getDeclaredMethod("refreshVehicleIdentitySettings")
            .apply { isAccessible = true }
        val runtime = CarPlayHostActivity::class.java.getDeclaredMethod("createRuntimeConfig")
            .apply { isAccessible = true }

        var originalSerial: String? = null
        for (target in listOf(MfiTarget.LOCAL, MfiTarget.USB_CH341)) {
            ReflectionHelpers.setField(activity, "mfiTarget", target)
            ReflectionHelpers.setField(activity, "vehicleName", "DiPlay")
            AirPlayPersistence.saveVehicleName(activity, "宋 PLUS")
            refresh.invoke(activity)
            val config = runtime.invoke(activity) as CarPlayRuntimeConfig
            assertEquals(target, config.mfiTarget)
            assertEquals("宋 PLUS", config.label)
            val wireless = config.identification.copy(
                wireless = Iap2WirelessIdentification("02:00:00:00:00:02", "test-hotspot"),
            )
            val parameters = Iap2ParameterList.parse(
                Iap2IdentificationClient.identificationInformation(wireless).payload,
            ).asList()
            assertEquals("宋 PLUS\u0000", parameters.single { it.id == 0 }.payload.decodeToString())
            if (originalSerial == null) originalSerial = config.identification.serialNumber
            assertEquals(originalSerial, config.identification.serialNumber)
        }

        AirPlayPersistence.saveVehicleName(activity, "宋 PLUS DMi")
        refresh.invoke(activity)
        val renamed = runtime.invoke(activity) as CarPlayRuntimeConfig
        assertEquals("宋 PLUS DMi", renamed.identification.name)
        assertEquals(originalSerial, renamed.identification.serialNumber)
    }
}
