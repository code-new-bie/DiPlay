package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticAuthenticationSummaryTest {
    @Test fun everySavedTargetHasItsOwnDescription() {
        val labels = mapOf(
            MfiTarget.LOCAL to "Local offline",
            MfiTarget.USB_CH341 to "USB/CH341",
            MfiTarget.I2C to "Linux I2C",
            MfiTarget.REMOTE to "Remote server",
        )
        for ((target, label) in labels) {
            val report = DiagnosticAuthenticationSummary.report(target, target)
            assertTrue(report.lineSequence().first().contains(": $label"))
            assertTrue(report.contains("Session authentication target: $label"))
            assertEquals(target == MfiTarget.LOCAL, report.contains("no remote fallback"))
        }
    }

    @Test fun changedPreferencesDoNotRelabelTheExistingUsbSession() {
        val lines = DiagnosticAuthenticationSummary.report(MfiTarget.LOCAL, MfiTarget.USB_CH341).lines()
        assertTrue(lines[0].contains("Local offline"))
        assertEquals("Session authentication target: USB/CH341", lines[1])
    }

    @Test fun disconnectedReportDoesNotClaimAnAuthenticationSession() {
        val report = DiagnosticAuthenticationSummary.report(MfiTarget.USB_CH341, null)
        assertTrue(report.endsWith("Session authentication target: none"))
        assertFalse(report.contains("no remote fallback"))
    }
}
