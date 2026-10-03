package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayMfiSelectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var directory: File
    private lateinit var context: Context
    private var controller: CarPlayController? = null
    private val statuses = mutableListOf<CarPlayStatus>()
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        directory = temporary.newFolder()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = directory
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int) = false
        }
    }

    @After
    fun tearDown() {
        controller?.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun usbSelectionWaitsForHardwareEvenWhenValidOfflineCredentialsExist() {
        val offline = offlineIdentity()
        LocalMfiAuthenticationClient.load(offline)
        start(MfiTarget.USB_CH341)
        assertWaitingForUsb()
    }

    @Test
    fun usbSelectionWaitsForHardwareWithoutOfflineCredentials() {
        start(MfiTarget.USB_CH341)
        assertWaitingForUsb()
    }

    @Test
    fun incompleteOfflineCredentialsDoNotOverrideUsbSelection() {
        File(directory, LocalMfiAuthenticationClient.DIRECTORY).mkdir()
        start(MfiTarget.USB_CH341)
        assertWaitingForUsb()
    }

    @Test
    fun explicitlySelectedLocalCredentialsStillAuthenticate() {
        offlineIdentity()
        start(MfiTarget.LOCAL)
        assertTrue(statuses.contains(CarPlayStatus.MfiReady))
        assertTrue(statuses.contains(CarPlayStatus.WaitingForIphone))
        assertFalse(statuses.any { it is CarPlayStatus.Failed })
        assertTrue(logs.any { "backend=LocalOffline" in it })
        assertFalse(logs.any { "backend=CH341" in it })
    }

    @Test
    fun missingLocalCredentialsFailWithoutSelectingUsb() {
        start(MfiTarget.LOCAL)
        assertTrue(statuses.any { it is CarPlayStatus.Failed })
        assertFalse(statuses.contains(CarPlayStatus.MfiReady))
        assertFalse(logs.any { "backend=CH341" in it })
    }

    private fun assertWaitingForUsb() {
        assertTrue(statuses.contains(CarPlayStatus.WaitingForMfi))
        assertFalse(statuses.contains(CarPlayStatus.MfiReady))
        assertFalse(statuses.any { it is CarPlayStatus.Failed })
        assertTrue(logs.any { "backend=CH341" in it })
        assertFalse(logs.any { "backend=LocalOffline" in it })
    }

    private fun start(target: MfiTarget) {
        val config = CarPlayRuntimeConfig(
            mfiTarget = target,
            ch341Devices = listOf(UsbDeviceId(0x1a86, 0x5512)),
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3),
        )
        val next = CarPlayController(context, config,
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {
                override fun onDebugLog(message: String) { logs += message }
            }, object : AirPlayMediaHandler {}, statuses::add)
        controller = next
        next.start()
        // Wait for the controller's worker, then deliver the posted statuses on the main thread.
        val executor = ReflectionHelpers.getField<ExecutorService>(next, "executor")
        executor.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun offlineIdentity(): File {
        val offline = File(directory, LocalMfiAuthenticationClient.DIRECTORY).apply { mkdir() }
        // Fresh synthetic credentials: never read the release identity into a test.
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val name = X500Name("CN=DiPlay MFi selection test only")
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger.ONE))
            setSignature(algorithm)
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(0)))
            setEndDate(Time(Date(4102444800000L)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
        }.generateTBSCertificate()
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(pair.private)
            update(tbs.encoded)
        }
        File(offline, "identity.pk8").writeBytes(pair.private.encoded)
        File(offline, "certificate.p7b").writeBytes(DERSequence(arrayOf<ASN1Encodable>(
            tbs, algorithm, DERBitString(signer.sign()),
        )).encoded)
        return offline
    }
}
