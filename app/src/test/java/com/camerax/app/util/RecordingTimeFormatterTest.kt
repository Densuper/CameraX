package com.camerax.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTimeFormatterTest {
    @Test
    fun formatsZero() {
        assertEquals("00:00", RecordingTimeFormatter.format(0L))
    }

    @Test
    fun formatsMinuteAndSeconds() {
        assertEquals("01:05", RecordingTimeFormatter.format(65_000L))
    }

    @Test
    fun clampsNegative() {
        assertEquals("00:00", RecordingTimeFormatter.format(-1L))
    }
}

