package app.capri.mobile

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlin.random.Random

// Cari dan putar lagu di app musik apa pun. Urutannya: perintah putar-dari-pencarian bawaan Android,
// lalu pencarian milik app itu, lalu buka halaman hasil di browser.
object MusicSearch {
    class App(val pkg: String, val label: String)

    private val known = listOf(
        App("com.spotify.music", "Spotify"),
        App("com.google.android.apps.youtube.music", "YouTube Music"),
        App("com.google.android.youtube", "YouTube"),
        App("premium.gotube.adblock.utube", "GoTube"),
        App("com.soundcloud.android", "SoundCloud"),
        App("com.aspiro.tidal", "Tidal"),
        App("deezer.android.app", "Deezer"),
        App("com.amazon.mp3", "Amazon Music"),
        App("com.sec.android.app.music", "Samsung Music"),
        App("com.miui.player", "Mi Music"),
        App("com.maxmpz.audioplayer", "Poweramp"),
        App("com.aimp.player", "AIMP"),
        App("in.krosbits.musicolet", "Musicolet"),
        App("com.rhmsoft.pulsar", "Pulsar"),
        App("ch.vanilla.music", "Vanilla Music"),
        App("org.videolan.vlc", "VLC"),
        App("com.zlhb.resso", "Resso"),
        App("com.joox.softbank", "JOOX")
    )

    val favoriteSongs = listOf(
        "I Really Want to Stay at Your House",
        "This Fffire",
        "Who's Ready for Tomorrow",
        "Major Crimes",
        "Let You Down",
        "Never Fade Away",
        "The Rebel Path",
        "Night City"
    )

    fun favorite(): String = favoriteSongs[Random.nextInt(favoriteSongs.size)]

    // Daftar app musik yang benar-benar terpasang di HP ini.
    fun installed(ctx: Context): List<App> {
        val pm = ctx.packageManager
        return known.filter {
            try {
                pm.getPackageInfo(it.pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun pick(ctx: Context, preferred: String): App? {
        if (preferred == "web") return null
        val list = installed(ctx)
        if (preferred.isNotBlank() && preferred != "auto") {
            val key = preferred.lowercase()
            list.firstOrNull { it.pkg == preferred || it.label.lowercase() == key }?.let { return it }
        }
        return list.firstOrNull()
    }

    fun appChoiceLabels(ctx: Context): Array<String> {
        val list = arrayListOf("Otomatis")
        for (a in installed(ctx)) list.add(a.label)
        if (list.size == 1) list.add("Browser / YouTube")
        return list.toTypedArray()
    }

    fun appChoiceValues(ctx: Context): Array<String> {
        val list = arrayListOf("auto")
        for (a in installed(ctx)) list.add(a.pkg)
        if (list.size == 1) list.add("web")
        return list.toTypedArray()
    }

    class Result(val ok: Boolean, val app: String, val message: String)

    fun play(ctx: Context, query: String, preferred: String): Result {
        val q = query.trim().ifBlank { favorite() }
        val app = pick(ctx, preferred)
        if (app != null) {
            if (playFromSearch(ctx, app, q)) return Result(true, app.label, q)
            if (openDeepLink(ctx, app, q)) return Result(true, app.label, q)
        }
        if (openWeb(ctx, q)) return Result(true, app?.label ?: "browser", q)
        return Result(false, app?.label ?: "app musik", q)
    }

    private fun playFromSearch(ctx: Context, app: App, q: String): Boolean {
        val sends = listOf(
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, q),
            Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, q)
        )
        for (base in sends) {
            val i = Intent(base).setPackage(app.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(ctx.packageManager) == null) continue
            try {
                ctx.startActivity(i)
                AppLog.add("MUSIK", "putar-dari-cari ${app.label}: $q")
                return true
            } catch (e: Exception) {
                AppLog.add("MUSIK", "gagal ${app.label}: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        return false
    }

    private fun openDeepLink(ctx: Context, app: App, q: String): Boolean {
        val e = Uri.encode(q)
        val uri = when (app.pkg) {
            "com.spotify.music" -> "spotify:search:$e"
            "com.google.android.youtube" -> "vnd.youtube://results?search_query=$e"
            "com.google.android.apps.youtube.music" -> "https://music.youtube.com/search?q=$e"
            "premium.gotube.adblock.utube" -> "https://www.youtube.com/results?search_query=$e"
            "com.soundcloud.android" -> "https://soundcloud.com/search?q=$e"
            "com.aspiro.tidal" -> "https://tidal.com/search?q=$e"
            "deezer.android.app" -> "https://www.deezer.com/search/$e"
            else -> return openWeb(ctx, q)
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (app.pkg != "com.google.android.youtube" && app.pkg != "com.spotify.music" && uri.startsWith("http")) {
            view.setPackage(app.pkg)
        }
        if (view.resolveActivity(ctx.packageManager) == null) return false
        return try {
            ctx.startActivity(view)
            AppLog.add("MUSIK", "buka ${app.label} untuk: $q")
            true
        } catch (e: Exception) {
            AppLog.add("MUSIK", "deep link gagal ${app.label}: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    private fun openWeb(ctx: Context, q: String): Boolean {
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(web)
            AppLog.add("MUSIK", "cari di browser: $q")
            true
        } catch (e: Exception) {
            AppLog.add("MUSIK", "browser gagal: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }
}
