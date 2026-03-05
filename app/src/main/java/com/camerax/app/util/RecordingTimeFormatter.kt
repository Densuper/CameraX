package com.camerax.app.util

import java.util.Locale

object RecordingTimeFormatter {
    fun format(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).toInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

