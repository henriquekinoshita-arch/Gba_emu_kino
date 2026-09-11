package com.kino.gbaemu.data

import android.content.Context
import java.io.File

/**
 * A native crash (SIGSEGV/abort in the mGBA/JNI layer) kills the process
 * instantly - no Java exception handler and no logcat access without adb
 * can tell you what happened. mgba_jni.c writes a plain-text breadcrumb
 * line before/after each risky native call into this file (fsync'd, so the
 * last line survives even if the very next call crashes); this object just
 * owns its path and lets the UI show or clear it, no adb required.
 */
object CrashLog {
    private const val FILE_NAME = "native_crash_breadcrumb.txt"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun path(context: Context): String = file(context).absolutePath

    fun read(context: Context): String? {
        val f = file(context)
        return if (f.exists()) f.readText() else null
    }

    /** Truncates any previous breadcrumbs so the file only ever reflects the latest attempt. */
    fun clear(context: Context) {
        file(context).delete()
    }
}
