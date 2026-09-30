package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.Configuration
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayAppearanceMonitorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://carsettings/global")
    private lateinit var provider: AppearanceProvider
    private lateinit var monitor: CarPlayAppearanceMonitor
    private val changes = mutableListOf<Boolean>()

    @Before fun setup() {
        provider = AppearanceProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "carsettings" })
        ShadowContentResolver.registerProviderInternal("carsettings", provider)
        monitor = CarPlayAppearanceMonitor(
            context, Handler(Looper.getMainLooper()), Configuration.UI_MODE_NIGHT_NO,
        ) { changes.add(it) }
    }

    @After fun cleanup() {
        monitor.stop()
        val thread = ReflectionHelpers.getField<HandlerThread>(monitor, "workerThread")
        thread.join(2_000)
        assertFalse("Appearance worker must stop with its owner", thread.isAlive)
    }

    @Test fun vehicleNotificationUpdatesAppearanceWithoutAnAndroidConfigurationChange() {
        monitor.start()
        flush()
        assertEquals(listOf(false), changes)
        assertFalse("Provider queries must run off the main thread", provider.queriedOnMain)
        provider.screenMode = "0"
        provider.dayOrNight = "0"
        context.contentResolver.notifyChange(uri, null)
        flush()
        assertEquals(listOf(false, true), changes)
        provider.dayOrNight = "1"
        context.contentResolver.notifyChange(uri, null)
        flush()
        assertEquals(listOf(false, true, false), changes)
    }

    @Test fun pollingDetectsVehicleChangesEvenWithoutProviderNotifications() {
        monitor.start()
        flush()
        provider.screenMode = "2"
        shadowOf(worker().looper).idleFor(1, TimeUnit.SECONDS)
        flush()
        assertEquals(listOf(false, true), changes)
    }

    @Test fun unchangedVehicleStateDoesNotResendAppearance() {
        monitor.start()
        flush()
        repeat(3) {
            context.contentResolver.notifyChange(uri, null)
            flush()
        }
        assertEquals(listOf(false), changes)
    }

    @Test fun deniedProviderFallsBackToAndroidAndCanRecover() {
        provider.denyQueries = true
        monitor.start()
        flush()
        monitor.updateUiMode(Configuration.UI_MODE_NIGHT_YES)
        flush()
        assertEquals(listOf(false, true), changes)
        provider.denyQueries = false
        context.contentResolver.notifyChange(uri, null)
        flush()
        assertEquals(listOf(false, true, false), changes)
    }

    @Test fun stoppingSuppressesCallbacksAlreadyQueuedOnTheMainThread() {
        monitor.start()
        awaitWorker()
        monitor.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(changes.isEmpty())
    }

    private fun worker(): Handler = ReflectionHelpers.getField(monitor, "worker")

    private fun awaitWorker() {
        val completed = CountDownLatch(1)
        assertTrue(worker().post { completed.countDown() })
        assertTrue("Appearance worker did not finish", completed.await(2, TimeUnit.SECONDS))
    }

    private fun flush() {
        // A content observer may enqueue the query after the first barrier.
        repeat(2) { awaitWorker() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class AppearanceProvider : ContentProvider() {
        @Volatile var screenMode = "1"
        @Volatile var dayOrNight = "1"
        @Volatile var denyQueries = false
        @Volatile var queriedOnMain = false

        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            queriedOnMain = queriedOnMain || Looper.myLooper() == Looper.getMainLooper()
            if (denyQueries) throw SecurityException("Vehicle provider denied access")
            val value = when (selectionArgs?.singleOrNull()) {
                "sys_screen_mode" -> screenMode
                "sys_day_or_night" -> dayOrNight
                else -> error("Unexpected vehicle setting")
            }
            return MatrixCursor(arrayOf("value")).apply { addRow(arrayOf(value)) }
        }
        override fun getType(uri: Uri) = "vnd.android.cursor.item/appearance"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
