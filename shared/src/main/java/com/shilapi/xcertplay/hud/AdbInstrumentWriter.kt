package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Base64

/**
 * Writes the BYD instrument's music source, play state and song text over the head unit's own adb
 * ("ADB over network", 127.0.0.1:5555) instead of the in-process SDK.
 *
 * DiPlay runs as an ordinary app (uid >= 10000). The head unit's `autoservice` refuses the
 * instrument write for that uid: its `checkSetPermission` allows any caller with uid <= 9999 with no
 * permission, but routes higher uids through the signature-only `BYDAUTO_INSTRUMENT_SET` gate, which
 * a third-party app can never be granted. The adb shell runs as uid 2000, so a tiny helper launched
 * there via `app_process` clears the gate and performs the writes the app cannot.
 *
 * The helper calls `BYDAutoInstrumentDevice.setMediaState/setMediaInfo` directly (the thin
 * forwarders to `BYDAutoManager.setInt/setBuffer`), bypassing `MediaStateDelegate`, whose
 * focus-owner check the shell process would never satisfy. One `app_process` launch per write keeps
 * the protocol trivial; lyric changes a few seconds apart make the ~spawn cost immaterial.
 */
internal class AdbInstrumentWriter(
    private val context: Context,
    private val shell: BydAdbShell = BydAdbShell(TAG),
    private val diagnostic: (String) -> Unit = {},
) : MusicTextWriter {
    private val app = context.applicationContext
    private var pushed = false
    private var pushReported = false

    override fun source(value: Int): Int = invoke(value.toString(), DASH, DASH, "source")

    override fun state(value: Int): Int = invoke(DASH, value.toString(), DASH, "state")

    override fun send(text: String): Int {
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return invoke(DASH, DASH, encoded, "text")
    }

    /** 0 on a confirmed instrument write, non-zero otherwise so the relay disables this connection. */
    private fun invoke(source: String, state: String, text: String, stage: String): Int {
        if (!ensurePushed()) return ERR_PUSH
        val output = shell.run(app, "CLASSPATH=$REMOTE_JAR app_process /system/bin $MAIN $source $state $text")
            ?: return ERR_LINK
        // The helper prints "<stage>=<rc>" on success or "<stage>=ERR:<type>" on failure.
        val line = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("$stage=") }
        if (line == null) {
            report("$stage no reply: ${output.take(120)}")
            return ERR_NO_REPLY
        }
        val value = line.substringAfter('=')
        val rc = value.toIntOrNull()
        if (rc == null) {
            report("$stage failed: $value")
            return ERR_HELPER
        }
        return rc
    }

    private fun ensurePushed(): Boolean {
        if (pushed) return true
        val bytes = try {
            app.assets.open(ASSET).use { it.readBytes() }
        } catch (error: Exception) {
            report("helper asset missing: ${error.javaClass.simpleName}")
            return false
        }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        var first = true
        for (part in encoded.chunked(CHUNK)) {
            val redirect = if (first) ">" else ">>"
            if (shell.run(app, "echo $part | base64 -d $redirect $REMOTE_JAR") == null) {
                report("helper push link failed")
                return false
            }
            first = false
        }
        val size = shell.run(app, "wc -c < $REMOTE_JAR")?.trim()?.toIntOrNull()
        if (size != bytes.size) {
            report("helper push incomplete size=$size expected=${bytes.size}")
            return false
        }
        pushed = true
        if (!pushReported) {
            pushReported = true
            report("helper staged over adb (${bytes.size} bytes)")
        }
        return true
    }

    private fun report(message: String) {
        runCatching { diagnostic("BYD music text ADB: $message") }
    }

    private companion object {
        const val TAG = "DiPlay-BYD-Instr-ADB"
        const val ASSET = "diplay-instrument-helper.jar"
        const val REMOTE_JAR = "/data/local/tmp/diplay-instrument-helper.jar"
        const val MAIN = "com.shilapi.xcertplay.instrumenthelper.InstrumentHelperMain"
        const val DASH = "-"
        const val CHUNK = 2048
        // Non-zero results the relay treats as a write failure; kept clear of the helper's own codes.
        const val ERR_PUSH = -1001
        const val ERR_LINK = -1002
        const val ERR_NO_REPLY = -1003
        const val ERR_HELPER = -1004
    }
}
