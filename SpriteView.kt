package app.capri.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.random.Random

// Sprite strip Capri. Jumlah frame dan fps tiap animasi mengikuti versi PC. Animasi dimuat saat pertama dipakai
// dan yang lama dilepas, supaya memori HP tidak penuh.
class SpriteView(context: Context) : View(context) {
    private class Def(val frames: Int, val fps: Float, val shiftX: Float = 0f)
    private class Clip(val frames: List<Bitmap>, val fps: Float, val shiftX: Float, var used: Long)

    private val defs = mapOf(
        "idle" to Def(16, 8f),
        "blink" to Def(4, 8f),
        "mouth" to Def(5, 12f),
        "glitch" to Def(10, 15f),
        "grin" to Def(16, 8f),
        "dangle" to Def(24, 10f),
        "walk" to Def(12, 12f),
        "dance" to Def(16, 16f),
        "arrive" to Def(14, 15f),
        "leave" to Def(12, 15f),
        "sleep" to Def(16, 6f),
        "wake" to Def(13, 15f),
        "perch" to Def(60, 30f, -26f / 384f),
        "perch_blink" to Def(8, 30f, -26f / 384f),
        "perch_glitch" to Def(10, 15f, -26f / 384f),
        "perch_music" to Def(60, 30f, -26f / 384f),
        "perch_arrive" to Def(14, 15f, -26f / 384f),
        "perch_leave" to Def(12, 15f, -26f / 384f)
    )
    private val core = setOf("idle", "blink", "mouth")
    private val maxCached = 4

    private val clips = HashMap<String, Clip>()
    private var cur: Clip? = null
    private var curName = "idle"
    private var loop = true
    private var started = SystemClock.uptimeMillis()
    private var cycle = 0
    private var blinkOn = false
    private var mouthText: String? = null
    private var mouthStart = 0L
    private var mouthHold = false
    private var flip = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    // Animasi yang diputar setelah animasi sekali-jalan selesai (idle, perch, dance, atau sleep).
    var rest = "idle"

    init {
        ensure("idle")
        ensure("blink")
        ensure("mouth")
        cur = clips["idle"]
    }

    private fun ensure(name: String): Clip? {
        clips[name]?.let { it.used = SystemClock.uptimeMillis(); return it }
        val def = defs[name] ?: return null
        // buang animasi non-inti yang paling lama tidak dipakai
        val loaded = clips.keys.filter { it !in core && it != curName }
        if (loaded.size >= maxCached) {
            loaded.minByOrNull { clips.getValue(it).used }?.let { old ->
                clips.remove(old)?.frames?.forEach { it.recycle() }
            }
        }
        return try {
            val strip = context.assets.open("sprites/$name.png").use { BitmapFactory.decodeStream(it) } ?: return null
            val fw = strip.width / def.frames
            if (fw <= 0) { strip.recycle(); return null }
            val list = (0 until def.frames).map { Bitmap.createBitmap(strip, it * fw, 0, fw, strip.height) }
            strip.recycle()
            Clip(list, def.fps, def.shiftX, SystemClock.uptimeMillis()).also { clips[name] = it }
        } catch (e: Throwable) {
            AppLog.add("SPRITE", "gagal memuat $name: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    fun play(name: String, loop: Boolean = true) {
        val c = ensure(name) ?: return
        cur = c
        curName = name
        this.loop = loop
        started = SystemClock.uptimeMillis()
        cycle = 0
        blinkOn = false
        invalidate()
    }

    fun setFlip(on: Boolean) {
        flip = on
        invalidate()
    }

    fun setHologram(on: Boolean) {
        paint.colorFilter = if (on) LightingColorFilter(Color.rgb(90, 255, 255), Color.rgb(0, 20, 30)) else null
        paint.alpha = if (on) 200 else 255
        invalidate()
    }

    // Mulut bergerak mengikuti huruf-huruf balasan. hold = terus bergerak sampai stopTalk().
    fun talk(text: String, hold: Boolean) {
        if (text.isBlank()) return
        mouthText = text
        mouthStart = SystemClock.uptimeMillis()
        mouthHold = hold
        invalidate()
    }

    fun stopTalk() {
        mouthText = null
    }

    private fun mouthFor(ch: Char, step: Int): Int {
        val l = ch.lowercaseChar()
        return when {
            l in "aeo" -> if (ch.isUpperCase()) 4 else 3
            l in "iuy" -> 2
            l in "mbp" || !ch.isLetter() -> 0
            else -> if (step % 2 == 1) 1 else 2
        }
    }

    // Menggambar satu frame rata bawah. Skala dihitung dari lebar 240 px (frame biasa), jadi frame
    // duduk yang lebih sempit (196 px) tetap sama besar dengan Capri berdiri.
    private fun drawFrame(canvas: Canvas, bmp: Bitmap, shiftX: Float) {
        val s = width / 240f
        val dw = bmp.width * s
        val dh = bmp.height * s
        val left = (width - dw) / 2f + shiftX * 384f * s
        dst.set(left, height - dh, left + dw, height.toFloat())
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val clip = cur ?: return
        val now = SystemClock.uptimeMillis()
        val t = (now - started) / 1000f
        val raw = (t * clip.fps).toInt()
        if (!loop && raw >= clip.frames.size) { play(rest); return }
        val i = raw % clip.frames.size
        val c = raw / clip.frames.size
        if (c != cycle) { cycle = c; blinkOn = Random.nextFloat() < 0.45f }

        canvas.save()
        if (flip) canvas.scale(-1f, 1f, width / 2f, height / 2f)

        val idle = curName == "idle"
        val blink = clips["blink"]
        val body: Bitmap
        var shiftX = clip.shiftX
        if (idle && blinkOn && blink != null && i in 9..12 && (i - 9) < blink.frames.size) {
            body = blink.frames[i - 9]
        } else if (curName == "perch" && blinkOn && i in 28..35) {
            val pb = ensure("perch_blink")
            if (pb != null && (i - 28) < pb.frames.size) {
                body = pb.frames[i - 28]
                shiftX = pb.shiftX
            } else {
                body = clip.frames[i]
            }
        } else {
            body = clip.frames[i]
        }
        drawFrame(canvas, body, shiftX)

        val text = mouthText
        val mouth = clips["mouth"]
        if (idle && text != null && mouth != null) {
            val letters = ((now - mouthStart) / 1000f * 14f).toInt()
            var n = letters
            if (n >= text.length) {
                if (mouthHold) n %= text.length else { mouthText = null; n = -1 }
            }
            if (n >= 0) drawFrame(canvas, mouth.frames[mouthFor(text[n], letters)], 0f)
        }
        canvas.restore()
        postInvalidateOnAnimation()
    }
}
