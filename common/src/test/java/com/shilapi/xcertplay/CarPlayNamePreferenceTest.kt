package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CarPlayNamePreferenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun vehicleNameAlsoUpdatesCarPlayReturnLabel() {
        AirPlayPersistence.saveCarPlayName(context, "My Car")
        assertEquals("My Car", AirPlayPersistence.loadCarPlayName(context))
        assertEquals("My Car", AirPlayPersistence.loadOemLabel(context))

        AirPlayPersistence.saveCarPlayName(context, "New Car")
        assertEquals("New Car", AirPlayPersistence.loadOemLabel(context))
    }

    @Test
    fun oldDefaultLabelFollowsExistingCustomVehicleName() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().putString("carplay_name", "Existing Car")
            .putString("oem_label", AirPlayPersistence.DEFAULT_OEM_LABEL).commit()

        assertEquals("Existing Car", AirPlayPersistence.loadOemLabel(context))
    }

    @Test
    fun separatelyCustomizedReturnLabelIsPreserved() {
        AirPlayPersistence.saveCarPlayName(context, "My Car")
        AirPlayPersistence.saveOemLabel(context, "Home")
        AirPlayPersistence.saveCarPlayName(context, "New Car")

        assertEquals("Home", AirPlayPersistence.loadOemLabel(context))
    }
}
