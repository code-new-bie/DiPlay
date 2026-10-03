package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The CarPlay vehicle name must never silently fall back to the build default on a fresh install. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VehicleNamePreferenceTest {
    private lateinit var app: Context
    private val headUnitName = "宋 Plus"

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test
    fun savedNameWinsOverTheHeadUnitName() {
        AirPlayPersistence.saveVehicleName(app, "  My Car  ")
        assertEquals("My Car", AirPlayPersistence.loadVehicleName(app) { headUnitName })
    }

    @Test
    fun whitespaceOnlySavedNameFollowsTheHeadUnitName() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().putString("vehicle_name", "   ").commit()
        assertEquals(headUnitName, AirPlayPersistence.loadVehicleName(app) { headUnitName })
    }

    @Test
    fun freshInstallFollowsTheHeadUnitName() {
        assertEquals(headUnitName, AirPlayPersistence.loadVehicleName(app) { headUnitName })
    }

    @Test
    fun headUnitNameIsNormalizedAndClamped() {
        assertEquals("宋 Plus", AirPlayPersistence.loadVehicleName(app) { "\t$headUnitName\n" })
        assertEquals(64, AirPlayPersistence.loadVehicleName(app) { "x".repeat(200) }.length)
    }

    @Test
    fun missingHeadUnitNameKeepsTheApplicationDefault() {
        assertEquals(AirPlayPersistence.DEFAULT_VEHICLE_NAME, AirPlayPersistence.loadVehicleName(app) { null })
        assertEquals(AirPlayPersistence.DEFAULT_VEHICLE_NAME, AirPlayPersistence.loadVehicleName(app) { "   " })
    }

    @Test
    fun defaultSourceAlwaysResolvesToSomethingUsable() {
        assertTrue(AirPlayPersistence.loadVehicleName(app).isNotBlank())
    }
}
