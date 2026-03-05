package com.camerax.app.debug

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {

    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashFile(appContext, thread, throwable)
            } catch (_: Exception) {
                // Never crash while logging a crash.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

        val stack = StringWriter().also { sw ->
            throwable.printStackTrace(PrintWriter(sw))
        }.toString()

        val details = buildString {
            appendLine("timestamp=$ts")
            appendLine("thread=${thread.name}")
            appendLine("brand=${Build.BRAND}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("release=${Build.VERSION.RELEASE}")
            appendLine("appId=${context.packageName}")
            appendLine("\nstacktrace:")
            appendLine(stack)
        }

        LogFileManager.append(context, "fatal_exception thread=${thread.name}")
        LogFileManager.writeCrash(context, details, ts)
    }
}
