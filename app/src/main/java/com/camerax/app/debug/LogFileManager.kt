package com.camerax.app.debug

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogFileManager {
    private const val PREFS = "camerax_logs"
    private const val KEY_LOG_FOLDER_URI = "log_folder_uri"

    private var sessionLogUri: Uri? = null
    private var sessionLogFile: File? = null

    fun hasUserFolder(context: Context): Boolean = getUserFolderUri(context) != null

    fun getUserFolderUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOG_FOLDER_URI, null)
        return raw?.let { Uri.parse(it) }
    }

    fun setUserFolder(context: Context, uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOG_FOLDER_URI, uri.toString())
            .apply()
    }

    fun startSession(context: Context) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val fileName = "session_$timestamp.txt"

        sessionLogUri = createFileInTargetFolder(context, fileName, "text/plain")
        sessionLogFile = if (sessionLogUri == null) createFileInAppFolder(context, fileName) else null

        writeLine(
            context,
            "${now()} | session_start=$timestamp\n" +
                "${now()} | device=${android.os.Build.BRAND} ${android.os.Build.MODEL}\n" +
                "${now()} | sdk=${android.os.Build.VERSION.SDK_INT} release=${android.os.Build.VERSION.RELEASE}\n"
        )
    }

    fun append(context: Context, message: String) {
        val line = "${now()} | $message\n"
        if (sessionLogUri == null && sessionLogFile == null) {
            sessionLogFile = createFileInAppFolder(context, "latest_session.txt")
        }
        writeLine(context, line)
    }

    fun writeCrash(context: Context, details: String, timestamp: String) {
        val fileName = "crash_$timestamp.txt"
        val crashUri = createFileInTargetFolder(context, fileName, "text/plain")

        if (crashUri != null) {
            try {
                context.contentResolver.openOutputStream(crashUri, "w")?.use { out ->
                    out.write(details.toByteArray())
                }
                val latest = createFileInTargetFolder(context, "latest_crash.txt", "text/plain")
                latest?.let {
                    context.contentResolver.openOutputStream(it, "w")?.use { out ->
                        out.write(details.toByteArray())
                    }
                }
                return
            } catch (_: Exception) {
                // Fall through.
            }
        }

        val fallbackDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash_logs")
        if (!fallbackDir.exists()) fallbackDir.mkdirs()
        File(fallbackDir, fileName).writeText(details)
        File(fallbackDir, "latest_crash.txt").writeText(details)
    }

    private fun createFileInTargetFolder(context: Context, name: String, mimeType: String): Uri? {
        val folderUri = getUserFolderUri(context) ?: return null
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return null
        if (!folder.exists() || !folder.canWrite()) return null

        val existing = folder.findFile(name)
        if (existing != null) return existing.uri

        return folder.createFile(mimeType, name)?.uri
    }

    private fun createFileInAppFolder(context: Context, name: String): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name)
    }

    private fun writeLine(context: Context, line: String) {
        val uri = sessionLogUri
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri, "wa")?.use { out ->
                    out.write(line.toByteArray())
                }
                return
            } catch (_: Exception) {
                // Fall through to file fallback.
            }
        }

        try {
            val fallbackFile = sessionLogFile ?: createFileInAppFolder(context, "latest_session.txt").also {
                sessionLogFile = it
            }
            fallbackFile.appendText(line)
        } catch (_: Exception) {
            // Ignore logging failures.
        }
    }

    fun defaultLogFolderPath(context: Context): String {
        return try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
            dir.absolutePath
        } catch (_: Exception) {
            context.filesDir.absolutePath
        }
    }

    private fun now(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
