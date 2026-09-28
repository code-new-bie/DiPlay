package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticMessageReducerTest {
    @Test fun nowPlayingUpdatesAreSummarizedEveryTenSeconds() {
        var now = 0L
        val reducer = DiagnosticMessageReducer { now }
        val update = "iAP tunnel iap2 rx=0x5001"

        assertEquals("iAP tunnel now-playing updates=1", reducer.reduce(update))
        repeat(9) { assertNull(reducer.reduce(update)) }
        assertEquals("connection ready", reducer.reduce("connection ready"))
        now = 10_000L
        assertEquals("iAP tunnel now-playing updates=10", reducer.reduce(update))
    }
}
