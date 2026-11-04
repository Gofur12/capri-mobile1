package app.capri.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import kotlin.random.Random

// Sprite strip Capri: tiap frame lebar 240 px, tinggi sesuai file. Gambar digambar rata bawah.
class SpriteView(context: Context) : View(context) {
    private class Clip(val frames: List<Bitmap>, val fps: Float)

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
    private val dst = Rect()

    init {
        load("idle", 8f)
        load("glitch", 15f)
        load("grin", 8f)
        load("dangle", 10f)
        load("blink", 8f)
        load("mouth", 12f)
        load("walk", 12f)
        load("dance", 10f)
        load("arrive", 10f)
        load("wake", 10f)
        load("perch", 8f)
        load("laydown_sleep", 6f)
        load("blink_glitch", 15f)
        cur = clips["idle"]
    }

    private fun load(name: String, fps: Float) {
        val strip = context.assets.open("sprites/$name.png").use { BitmapFactory.decodeStream(it) } ?: return
        val fw = 240
        if (strip.width % fw != 0) { strip.recycle(); return }
        val list = (0 until strip.width / fw).map { Bitmap.createBitmap(strip, it * fw, 0, fw, strip.height) }
        strip.recycle()
        clips[name] = Clip(list, fps)
    }

    fun play(name: String, loop: Boolean = true) {
        val c = clips[name] ?: return
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

    override fun onDraw(canvas: Canvas) {
        val clip = cur ?: return
        val now = SystemClock.uptimeMillis()
        val t = (now - started) / 1000f
        val raw = (t * clip.fps).toInt()
        if (!loop && raw >= clip.frames.size) { play("idle"); return }
        val i = raw % clip.frames.size
        val c = raw / clip.frames.size
        if (c != cycle) { cycle = c; blinkOn = Random.nextFloat() < 0.45f }

        val idle = curName == "idle"
        val blink = clips["blink"]
        val body = if (idle && blinkOn && blink != null && i in 9..12 && (i - 9) < blink.frames.size)
            blink.frames[i - 9] else clip.frames[i]

        val dh = (width * body.height / body.width.toFloat()).toInt()
        dst.set(0, height - dh, width, height)
        canvas.save()
        if (flip) canvas.scale(-1f, 1f, width / 2f, height / 2f)
        canvas.drawBitmap(body, null, dst, paint)

        val text = mouthText
        val mouth = clips["mouth"]
        if (idle && text != null && mouth != null) {
            val letters = ((now - mouthStart) / 1000f * 14f).toInt()
            var n = letters
            if (n >= text.length) {
                if (mouthHold) n %= text.length else { mouthText = null; n = -1 }
            }
            if (n >= 0) canvas.drawBitmap(mouth.frames[mouthFor(text[n], letters)], null, dst, paint)
        }
        canvas.restore()
        postInvalidateOnAnimation()
    }
}
