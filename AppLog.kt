package app.capri.mobile

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Catatan kejadian app (disimpan di file di HP). Crash yang tak tertangkap juga dicatat di sini.
object AppLog {
    const val BUILD = "r9"
    private const val MAX = 60_000
    private var file: File? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "capri.log")
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            add("CRASH", e.javaClass.name + ": " + e.message + "\n" + e.stackTraceToString().take(3000))
            old?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun add(tag: String, msg: String) {
        val f = file ?: return
        try {
            f.appendText(fmt.format(Date()) + " [" + tag + "] " + msg + "\n")
            if (f.length() > MAX) f.writeText(f.readText().takeLast(MAX / 2))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun read(): String = try { file?.readText() ?: "" } catch (_: Exception) { "" }

    @Synchronized
    fun clear() {
        try { file?.writeText("") } catch (_: Exception) {}
    }
}
