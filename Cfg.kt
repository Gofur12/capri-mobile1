package app.capri.mobile

import android.content.Context
import android.content.SharedPreferences

// Pengaturan bersama (dibaca layanan dan layar pengaturan) beserta nilai bawaannya.
object Cfg {
    const val ROBOT = 12
    const val GLITCH = 15
    const val ACTIVITY = 30
    const val SOUNDS = 100
    const val VOLUME = 100
    const val MEMORY = 20
    const val BUBBLE_SEC = 12
    const val WATCHMEM = 20
    const val WATCHSAVE = 60
    const val WATCH_EVERY = 15
    const val SPAWN = true
    const val SIZE = 100
    const val STAB = 40
    const val SIM = 75
    const val SPEED = 100
    const val PITCH = 100

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("capri", Context.MODE_PRIVATE)
    fun str(c: Context, key: String, def: String): String = prefs(c).getString(key, def) ?: def
    fun int(c: Context, key: String, def: Int): Int = prefs(c).getString(key, null)?.toIntOrNull() ?: def
    fun float(c: Context, key: String, def: Float): Float = prefs(c).getString(key, null)?.toFloatOrNull() ?: def
    fun on(c: Context, key: String, def: Boolean): Boolean = str(c, key, if (def) "on" else "off") == "on"
    fun set(c: Context, key: String, value: String) {
        prefs(c).edit().putString(key, value).apply()
    }
}
