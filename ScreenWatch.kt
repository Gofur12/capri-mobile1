package app.capri.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import java.io.ByteArrayOutputStream

// Lihat layar: frame terbaru selalu ditahan (tanpa diubah dulu), lalu diubah jadi JPEG hanya saat dibutuhkan.
// Dengan begitu gambar yang dikirim ke otak selalu keadaan layar yang paling baru, walau layar sedang diam.
object ScreenWatch {
    @Volatile var wanted = false
    private val lock = Any()
    private var held: Image? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var ht: HandlerThread? = null
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
        val scale = minOf(1f, 720f / dm.widthPixels)
        val w = (dm.widthPixels * scale).toInt().coerceAtLeast(160)
        val hh = (dm.heightPixels * scale).toInt().coerceAtLeast(160)
        val r = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, h)
        projection = p
        reader = r
        display = p.createVirtualDisplay(
            "capri-screen", w, hh, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
        )
        AppLog.add("LAYAR", "virtual display dibuat (${w}x$hh)")
    }

    private fun onFrame(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return
        synchronized(lock) {
            held?.close()
            held = img
        }
        if (!gotFirst) {
            gotFirst = true
            AppLog.add("LAYAR", "frame pertama diterima")
        }
    }

    // Gambar layar terbaru sebagai JPEG base64, atau null kalau belum ada frame.
    fun latestB64(): String? = synchronized(lock) {
        val img = held ?: return null
        try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPad = plane.rowStride - pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + rowPad / pixelStride, img.height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            bmp.copyPixelsFromBuffer(plane.buffer)
            val crop = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
            val out = ByteArrayOutputStream()
            crop.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            AppLog.add("LAYAR", "gagal mengubah gambar: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    // Nama lama dipertahankan: umur gambar tidak dibatasi lagi karena frame yang ditahan selalu yang terbaru.
    @Suppress("UNUSED_PARAMETER")
    fun freshB64(maxAgeMs: Long): String? = latestB64()

    fun stop() {
        synchronized(lock) {
            try { held?.close() } catch (_: Exception) {}
            held = null
        }
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) {}
        ht?.quitSafely()
        ht = null
        gotFirst = false
    }
}
