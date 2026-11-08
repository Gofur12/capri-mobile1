package app.capri.mobile

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings

// Membaca sesi musik dari app apa pun (Spotify, YouTube, GoTube, YouTube Music, pemutar lokal, dll.).
// Butuh izin "Akses notifikasi" supaya Android mengizinkan kita melihat sesi media.
object MediaWatcher {
    class Now(
        val title: String,
        val artist: String,
        val app: String,
        val pkg: String,
        val playing: Boolean,
        val canPrevious: Boolean,
        val canNext: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val controller: MediaController?
    )

    @Volatile private var listener: CapriNotifications? = null
    @Volatile private var last: Now? = null

    fun attach(service: CapriNotifications) {
        listener = service
        AppLog.add("MUSIK", "akses notifikasi tersambung")
    }

    fun detach(service: CapriNotifications) {
        if (listener === service) listener = null
        last = null
    }

    fun hasAccess(ctx: Context): Boolean {
        return try {
            val flat = ComponentName(ctx, CapriNotifications::class.java).flattenToString()
            val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            enabled.split(':').any { it.equals(flat, true) || it.contains(ctx.packageName, true) }
        } catch (_: Exception) {
            false
        }
    }

    fun refresh(): Now? {
        val service = listener ?: return last
        return try {
            val ctx = service.applicationContext
            val msm = ctx.getSystemService(MediaSessionManager::class.java)
            val sessions = msm.getActiveSessions(ComponentName(ctx, CapriNotifications::class.java))
            var best: MediaController? = null
            var bestPlaying = false
            for (c in sessions) {
                val md = c.metadata ?: continue
                val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                if (title.isBlank()) continue
                val playing = isPlaying(c.playbackState)
                if (best == null || (playing && !bestPlaying)) {
                    best = c
                    bestPlaying = playing
                }
            }
            val c = best ?: run {
                last = null
                return null
            }
            val md = c.metadata ?: return last
            val state = c.playbackState
            val pkg = c.packageName ?: ""
            val app = try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg.ifBlank { "musik" }
            }
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""
            val actions = state?.actions ?: 0L
            val now = Now(
                title = title,
                artist = artist,
                app = app,
                pkg = pkg,
                playing = isPlaying(state),
                canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                positionMs = state?.position ?: 0L,
                durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                controller = c
            )
            last = now
            now
        } catch (e: Exception) {
            AppLog.add("MUSIK", "baca sesi gagal: ${e.javaClass.simpleName} ${e.message}")
            last
        }
    }

    private fun isPlaying(state: PlaybackState?): Boolean =
        state != null && (state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING)

    fun command(what: String): Boolean {
        val now = refresh() ?: return false
        val controls = now.controller?.transportControls ?: return false
        return try {
            when (what.lowercase()) {
                "play", "resume" -> controls.play()
                "pause" -> controls.pause()
                "toggle" -> if (now.playing) controls.pause() else controls.play()
                "next", "skip" -> controls.skipToNext()
                "previous", "prev", "back" -> controls.skipToPrevious()
                "stop" -> controls.stop()
                else -> return false
            }
            AppLog.add("MUSIK", "kontrol $what di ${now.app}")
            true
        } catch (e: Exception) {
            AppLog.add("MUSIK", "kontrol $what gagal: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    fun seekTo(ms: Long): Boolean {
        val now = refresh() ?: return false
        return try {
            now.controller?.transportControls?.seekTo(ms.coerceAtLeast(0L))
            true
        } catch (e: Exception) {
            AppLog.add("MUSIK", "seek gagal: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    fun describe(): String {
        val now = refresh() ?: return ""
        return if (now.artist.isBlank()) "${now.title} (${now.app})" else "${now.title} oleh ${now.artist} (${now.app})"
    }
}
