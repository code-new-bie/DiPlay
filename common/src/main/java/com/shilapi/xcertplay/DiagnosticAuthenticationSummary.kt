package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.MfiTarget

/** Saved settings can change while the current controller keeps its original authentication target. */
internal object DiagnosticAuthenticationSummary {
    fun report(savedTarget: MfiTarget, sessionTarget: MfiTarget?): String =
        "Saved authentication target (may differ from active session): ${label(savedTarget)}\n" +
            "Session authentication target: ${sessionTarget?.let(::label) ?: "none"}"

    private fun label(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> "Local offline (experimental beta identity; no remote fallback)"
        MfiTarget.USB_CH341 -> "USB/CH341"
        MfiTarget.I2C -> "Linux I2C"
        MfiTarget.REMOTE -> "Remote server"
    }
}
