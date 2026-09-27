package com.supervideo.core.util

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application log written to `<dataDir>/logs/supervideo.log` (rotated to `supervideo.1.log` at [MAX_BYTES]).
 * Every line is also forwarded to the platform [Echo] (logcat on Android, stderr on desktop).
 * Before [init] messages only go to the echo, so logging is always safe to call.
 */
object AppLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Echo {
        fun write(level: Level, tag: String, message: String, error: Throwable?)
    }

    const val FILE_NAME = "supervideo.log"
    const val PREVIOUS_FILE_NAME = "supervideo.1.log"
    private const val MAX_BYTES = 1L shl 20

    private val lock = Any()
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    var logDir: File? = null
        private set

    @Volatile
    private var echo: Echo = Echo { level, tag, message, error ->
        System.err.println("${level.name[0]}/$tag: $message")
        error?.printStackTrace()
    }

    val currentFile: File? get() = logDir?.let { File(it, FILE_NAME) }

    /** Opens the log in [dir], installs an uncaught-exception logger and writes a session header. */
    fun init(dir: File, echo: Echo? = null, header: Map<String, String> = emptyMap()) {
        dir.mkdirs()
        if (echo != null) this.echo = echo
        logDir = dir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("Crash", "Uncaught exception on thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        i("App", "---- session start ----")
        header.forEach { (key, value) -> i("App", "$key: $value") }
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(Level.ERROR, tag, message, error)

    /** Current and previous log files, oldest first, joined as one text (for sharing). */
    fun collect(): String = synchronized(lock) {
        val dir = logDir ?: return ""
        listOf(PREVIOUS_FILE_NAME, FILE_NAME)
            .map { File(dir, it) }
            .filter { it.isFile }
            .joinToString("\n") { it.readText() }
    }

    private fun log(level: Level, tag: String, message: String, error: Throwable?) {
        runCatching { echo.write(level, tag, message, error) }
        val file = currentFile ?: return
        val line = buildString {
            synchronized(timestamp) { append(timestamp.format(Date())) }
            append(' ').append(level.name[0]).append(' ')
            append('[').append(Thread.currentThread().name).append("] ")
            append(tag).append(": ").append(message).append('\n')
            if (error != null) {
                val trace = StringWriter()
                error.printStackTrace(PrintWriter(trace))
                append(trace).append('\n')
            }
        }
        synchronized(lock) {
            runCatching {
                if (file.length() > MAX_BYTES) {
                    val previous = File(file.parentFile, PREVIOUS_FILE_NAME)
                    previous.delete()
                    file.renameTo(previous)
                }
                file.appendText(line)
            }
        }
    }
}
