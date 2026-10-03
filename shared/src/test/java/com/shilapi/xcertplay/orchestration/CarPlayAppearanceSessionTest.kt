package com.shilapi.xcertplay.orchestration

import android.os.Looper
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.ControlCipher
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.RtspMessage
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.ByteArrayOutputStream
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayAppearanceSessionTest {
    private val config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
        AirPlayDisplayConfig(800, 480))
    private val identity = AirPlayIdentity.generate()
    private val pairings = PairingStore()
    private val media = object : AirPlayMediaHandler {}
    private val sessions = mutableListOf<AirPlaySession>()
    private lateinit var controller: CarPlayController
    private lateinit var lifecycle: AirPlaySessionListener

    @Before
    fun setUp() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            // The test head unit has no BYD navigation service; avoid Robolectric's null binder callback.
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int) = false
        }
        controller = CarPlayController(context,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                "test", "test", "test", "test", "1", "1", 3)),
            config, identity, pairings, object : AirPlaySessionListener {}, media, {})
        lifecycle = CarPlayController::class.java.getDeclaredField("sessionListener").run {
            isAccessible = true
            get(controller) as AirPlaySessionListener
        }
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
        sessions.forEach { it.close() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun session() = AirPlaySession(Socket(), config, identity, pairings, null, lifecycle, media)
        .also { sessions += it }

    private fun channel(session: AirPlaySession): ByteArrayOutputStream {
        val output = ByteArrayOutputStream()
        field(session, "eventSocket", object : Socket() { override fun getOutputStream() = output })
        field(session, "eventCipher", ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
        return output
    }

    private fun field(session: AirPlaySession, name: String, value: Any) {
        AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
    }

    private fun modes(output: ByteArrayOutputStream): List<Boolean> {
        val cipher = ControlCipher(ByteArray(32) { 2 }, ByteArray(32) { 1 })
        val requests = RtspMessage.parseMessages(cipher.decrypt(output.toByteArray()).data).messages
        return requests.map { request ->
            assertEquals("POST", request.method)
            assertEquals("/command", request.path)
            val body = BplistCodec.decode(request.body) as Map<*, *>
            assertEquals("setNightMode", body["type"])
            (body["params"] as Map<*, *>)["nightMode"] as Boolean
        }
    }

    @Test
    fun recoveredUiCanSwitchThemeWithoutAnotherSessionActiveCallback() {
        val session = session()
        val output = channel(session)
        lifecycle.onSessionActive(session)
        var callbacks = 0
        controller.attachUi(object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) { callbacks++ }
        }, {})
        shadowOf(Looper.getMainLooper()).idle()
        // This is the recovery path: the new Activity has never received an active session.
        assertEquals(0, callbacks)
        assertTrue(controller.setNightMode(true))
        assertTrue(controller.setNightMode(false))
        assertEquals(listOf(true, false), modes(output))
    }

    @Test
    fun updatesFollowTheCurrentSessionAfterReplacement() {
        val old = session()
        val oldOutput = channel(old)
        lifecycle.onSessionActive(old)
        val next = session()
        val nextOutput = channel(next)
        lifecycle.onSessionActive(next)
        lifecycle.onSessionEnded(old)
        assertTrue(controller.setNightMode(true))
        assertEquals(0, oldOutput.size())
        assertEquals(listOf(true), modes(nextOutput))
    }

    @Test
    fun inactiveAndClosedControllersDoNotSend() {
        assertFalse(controller.setNightMode(true))
        val session = session()
        val output = channel(session)
        lifecycle.onSessionActive(session)
        controller.close()
        assertFalse(controller.setNightMode(true))
        assertEquals(0, output.size())
    }

    @Test
    fun endedSessionDoesNotReceiveFurtherThemeUpdates() {
        val session = session()
        val output = channel(session)
        lifecycle.onSessionActive(session)
        lifecycle.onSessionEnded(session)
        assertFalse(controller.setNightMode(true))
        assertEquals(0, output.size())
    }

    @Test
    fun lateEventChannelReceivesOnlyTheLatestPendingTheme() {
        val session = session()
        lifecycle.onSessionActive(session)
        assertFalse(controller.setNightMode(true))
        assertFalse(controller.setNightMode(false))
        val output = channel(session)
        val flush = AirPlaySession::class.java.getDeclaredMethod("sendPendingNightModeLocked")
            .apply { isAccessible = true }
        assertEquals(true, flush.invoke(session))
        assertEquals(listOf(false), modes(output))
    }
}
