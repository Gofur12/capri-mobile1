package app.capri.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import java.io.ByteArrayOutputStream

// Lihat layar: frame terakhir hanya disimpan di memori. Dikirim ke NVIDIA hanya saat abang kirim pesan.
object ScreenWatch {
    @Volatile var wanted = false
    @Volatile private var latest: String? = null
    @Volatile private var latestAt = 0L
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var ht: HandlerThread? = null
    private var lastEnc = 0L
    @Volatile private var gotFirst = false

    fun start(ctx: Context, code: Int, data: Intent, intervalMs: Long) {
        stop()
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val thread = HandlerThread("capri-screen").also { it.start() }
        ht = thread
        val h = Handler(thread.looper)
        AppLog.add("LAYAR", "memanggil getMediaProjection")
        val p = mpm.getMediaProjection(code, data) ?: return
        AppLog.add("LAYAR", "projection didapat")
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop() }
        }, h)
        val dm = ctx.resources.displayMetrics
        val w = (dm.widthPixels / 2).coerceAtLeast(160)
        val hh = (dm.heightPixels / 2).coerceAtLeast(160)
        val r = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd -> onFrame(rd, intervalMs) }, h)
        projection = p
        reader = r
        display = p.createVirtualDisplay(
            "capri-screen", w, hh, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
        )
        AppLog.add("LAYAR", "virtual display dibuat (${w}x$hh)")
    }

    private fun onFrame(rd: ImageReader, intervalMs: Long) {
        val img = rd.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastEnc < intervalMs) return
            lastEnc = now
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPad = plane.rowStride - pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + rowPad / pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val crop = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
            val out = ByteArrayOutputStream()
            crop.compress(Bitmap.CompressFormat.JPEG, 60, out)
            latest = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            latestAt = now
            if (!gotFirst) { gotFirst = true; AppLog.add("LAYAR", "frame pertama diterima") }
        } catch (_: Exception) {
        } finally {
            img.close()
        }
    }

    fun freshB64(maxAgeMs: Long): String? =
        if (System.currentTimeMillis() - latestAt <= maxAgeMs) latest else null

    fun stop() {
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) {}
        ht?.quitSafely()
        ht = null
        latest = null
        latestAt = 0L
        gotFirst = false
    }
}
