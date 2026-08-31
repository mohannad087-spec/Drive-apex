package com.driveapex.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-disk log that outlives the process.
 *
 * The question this exists to answer is why the app sometimes disappears on its
 * own, and an in-memory log cannot answer it: it dies with the process it was
 * meant to explain. So every line is appended and flushed immediately, and the
 * crash handler writes synchronously before letting the process go.
 *
 * It also separates the two ways an app vanishes, which look identical from the
 * driver's seat and need opposite fixes:
 *
 *  - A crash leaves an uncaught exception and a stack trace.
 *  - Being killed by the head unit -- for memory, or because the app went to
 *    background -- leaves nothing at all.
 *
 * A marker file written on an orderly shutdown tells them apart on the next
 * launch: no marker and no stack trace means nobody crashed, the system simply
 * took the process away, and the memory-pressure lines just before the end say
 * why.
 */
object DriveApexLog {
    private const val TAG = "DriveApex"
    private const val MAX_BYTES = 192 * 1024

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var dir: File? = null
    @Volatile private var installed = false

    /** How the previous run ended, decided once at startup. */
    @Volatile var previousExit: String = "unknown"
        private set

    fun init(context: Context) {
        if (installed) return
        installed = true
        val base = runCatching {
            File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
        }.getOrNull() ?: return
        dir = base

        previousExit = classifyPreviousExit(base)
        runCatching { File(base, "clean_exit").delete() }

        installCrashHandler()
        i("app", "--- session start --- previous exit: $previousExit")
    }

    private fun classifyPreviousExit(base: File): String {
        val clean = File(base, "clean_exit")
        if (clean.exists()) return "clean"
        val crash = File(base, "last_crash.txt")
        // A crash file newer than the last orderly shutdown belongs to the run
        // that just ended; otherwise nothing crashed and the process was taken.
        return if (crash.exists() && crash.length() > 0) {
            "CRASHED (see crash report)"
        } else {
            "KILLED or power loss (no crash recorded)"
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    appendLine("time    : ${stamp.format(Date())}")
                    appendLine("thread  : ${thread.name}")
                    appendLine("type    : ${error.javaClass.name}")
                    appendLine("message : ${error.message}")
                    appendLine()
                    append(stackOf(error))
                }
                dir?.let { File(it, "last_crash.txt").writeText(text) }
                write("FATAL", "app", "uncaught on ${thread.name}: ${error.javaClass.simpleName}: ${error.message}")
            }
            // Chain, so the platform still does whatever it would have done.
            previous?.uncaughtException(thread, error)
        }
    }

    fun i(tag: String, message: String) = write("INFO", tag, message)
    fun w(tag: String, message: String) = write("WARN", tag, message)

    fun e(tag: String, message: String, error: Throwable? = null) {
        write("ERROR", tag, if (error == null) message else "$message :: ${error.javaClass.simpleName}: ${error.message}")
        if (error != null) runCatching { append(stackOf(error)) }
    }

    /** Records an orderly shutdown, so the next start knows this was not a crash. */
    fun markCleanExit() {
        i("app", "--- clean exit ---")
        runCatching { dir?.let { File(it, "clean_exit").writeText("1") } }
    }

    fun crashReport(): String? = runCatching {
        dir?.let { File(it, "last_crash.txt") }?.takeIf { it.exists() && it.length() > 0 }?.readText()
    }.getOrNull()

    fun clearCrashReport() {
        runCatching { dir?.let { File(it, "last_crash.txt").delete() } }
    }

    /** The most recent lines, newest last, for the on-screen viewer. */
    fun recent(maxLines: Int = 400): List<String> = runCatching {
        val file = dir?.let { File(it, "current.log") } ?: return emptyList()
        if (!file.exists()) return emptyList()
        file.readLines().takeLast(maxLines)
    }.getOrDefault(emptyList())

    fun logFile(): File? = dir?.let { File(it, "current.log") }?.takeIf { it.exists() }

    private fun write(level: String, tag: String, message: String) {
        Log.println(
            when (level) {
                "ERROR", "FATAL" -> Log.ERROR
                "WARN" -> Log.WARN
                else -> Log.INFO
            },
            TAG, "[$tag] $message"
        )
        append("${stamp.format(Date())}  $level  $tag  $message")
    }

    /**
     * Appends and flushes at once. Buffering would lose exactly the lines that
     * explain a crash, which are the ones written last.
     */
    private fun append(line: String) {
        val base = dir ?: return
        synchronized(lock) {
            runCatching {
                val file = File(base, "current.log")
                if (file.length() > MAX_BYTES) {
                    File(base, "previous.log").delete()
                    file.renameTo(File(base, "previous.log"))
                }
                file.appendText(line + "\n")
            }
        }
    }

    private fun stackOf(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }
}
