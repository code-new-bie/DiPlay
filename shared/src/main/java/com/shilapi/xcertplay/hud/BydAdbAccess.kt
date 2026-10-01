package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/** What the settings page shows about the ADB link that DiPlay's optional BYD features need. */
object BydAdbAccess {
    enum class State { READY, NOT_APPROVED, ADB_OFF, PAIRING_ONLY }

    class Status(
        val state: State,
        val dashboardMode: BydClusterNaviMode? = null,
        val dashboardShowsMap: Boolean = true,
        val batteryPercent: Double? = null,
        val rangeKm: Int? = null,
        /** Which identity the head unit's own adbd gave DiPlay: uid=2000(shell) or uid=0(root). */
        val shellIdentity: String? = null,
        /** `ro.debuggable`; 1 means `adb root` is available on this build. */
        val debuggable: String? = null,
    )

    /** Blocking: run off the main thread. [mayAsk] lets the car show its approval dialog for DiPlay's key. */
    fun check(context: Context, mayAsk: Boolean): Status {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            val state = when (adb.connect(mayAsk)) {
                LocalAdb.Access.READY -> State.READY
                LocalAdb.Access.NOT_APPROVED -> State.NOT_APPROVED
                LocalAdb.Access.UNREACHABLE -> State.ADB_OFF
                LocalAdb.Access.UNSUPPORTED -> State.PAIRING_ONLY
            }
            if (state != State.READY) return Status(state)
            return readStatus(context, adb::shell)
        }
    }

    /** Read and publish the same battery data that the settings page reports as ready. */
    internal fun readStatus(context: Context, shell: (String) -> String?): Status {
        val mode = BydClusterNaviMode.parseRead(shell(BydClusterNaviMode.READ_COMMAND))
        val battery = BydBattery.read(shell)
        battery?.let { BydBatteryStatus.accept(context, it) }
        // Report what this adbd actually allows: a signature check cannot be bypassed with shell,
        // so the identity decides whether an ADB-driven grant is even worth attempting.
        val identity = shell("id")?.trim()?.take(64)
        val debuggable = shell("getprop ro.debuggable")?.trim()?.take(8)
        return Status(
            State.READY,
            mode,
            mode?.showsMap != false,
            battery?.percent,
            battery?.rangeKm,
            shellIdentity = identity,
            debuggable = debuggable,
        )
    }
}
