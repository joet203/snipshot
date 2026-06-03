package com.jt.snipshot

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Catches uncaught exceptions on any thread and writes a stack trace to disk
 * BEFORE the process dies, so the UI can surface it on the next launch.
 *
 * Standard handler chain pattern: capture, write, then delegate to the previous
 * handler so the system still kills the process / shows ANR dialog normally.
 */
object CrashLogger {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? {
        val f = File(context.filesDir, FILE_NAME)
        return if (f.exists()) f.readText() else null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    private fun writeCrash(context: Context, thread: Thread, t: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        pw.println("Thread: ${thread.name}")

        // Best-effort app + device info.
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            pw.println("App: ${info.versionName} (code ${info.longVersionCode})")
        }
        pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        pw.println("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")

        // Last known service state — usually the most diagnostically valuable line.
        pw.println()
        pw.println("Service state at crash:")
        pw.println("  isRunning=${ServiceStatus.isRunning}")
        pw.println("  observerFires=${ServiceStatus.observerFires}")
        pw.println("  lastUri=${ServiceStatus.lastUri}")
        pw.println("  lastDecision=${ServiceStatus.lastDecision}")
        pw.println("  lastAcceptedFire=${ServiceStatus.lastAcceptedFire}")
        pw.println("  overlayAttempts=${ServiceStatus.overlayAttempts}")
        pw.println("  overlayResult=${ServiceStatus.overlayResult}")
        pw.println("  decodeResult=${ServiceStatus.decodeResult}")
        pw.println("  lastError=${ServiceStatus.lastError}")

        pw.println()
        t.printStackTrace(pw)
        pw.flush()
        File(context.filesDir, FILE_NAME).writeText(sw.toString())
    }
}
