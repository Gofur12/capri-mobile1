package app.capri.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

// Sprite strip Capri. Jumlah frame dan fps tiap animasi mengikuti versi PC. Animasi dimuat saat pertama dipakai
// dan yang lama dilepas, supaya memori HP tidak penuh. Efek glitch dan not musik digambar lewat kode.
class SpriteView(context: Context) : View(context) {
    private class Def(val frames: Int, val fps: Float, val shiftX: Float = 0f)
    private class Clip(val frames: List<Bitmap>, val fps: Float, val shiftX: Float, var used: Long)
    private class Note(val born: Long, val x: Float, val glyph: String, val color: Int, val speed: Float)

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
        "perch_talk" to Def(60, 30f, -26f / 384f),
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
    private var glitchUntil = 0L
    private var nextAmbient = 0L
    private var notesOn = false
    private var nextNote = 0L
    private val notes = ArrayList<Note>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val srcR = Rect()
    private val cyanFilter = LightingColorFilter(Color.rgb(80, 255, 255), 0)
    private val pinkFilter = LightingColorFilter(Color.rgb(255, 60, 170), 0)

    // Animasi yang diputar setelah animasi sekali-jalan selesai (idle, perch, dance, atau sleep).
    var rest = "idle"

    // Seberapa sering glitch kecil muncul saat Capri bicara (0-100).
    var ambientGlitch = 0

    init {
        ensure("idle")
        ensure("blink")
        ensure("mouth")
        cur = clips["idle"]
    }

    private fun ensure(name: String): Clip? {
        clips[name]?.let { it.used = SystemClock.uptimeMillis(); return it }
        val def = defs[name] ?: return null
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

    // Glitch visual: pita-pita gambar bergeser dan warnanya berkedip sebentar.
    fun glitchBurst(ms: Long) {
        glitchUntil = maxOf(glitchUntil, SystemClock.uptimeMillis() + ms)
        invalidate()
    }

    fun setNotes(on: Boolean) {
        notesOn = on
        if (!on) notes.clear()
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
    private fun drawFrame(canvas: Canvas, bmp: Bitmap, shiftX: Float, glitchy: Boolean) {
        val s = width / 240f
        val dw = bmp.width * s
        val dh = bmp.height * s
        val left = (width - dw) / 2f + shiftX * 384f * s
        val top = height - dh
        if (!glitchy) {
            dst.set(left, top, left + dw, height.toFloat())
            canvas.drawBitmap(bmp, null, dst, paint)
            return
        }
        val bands = 9
        val rnd = Random(SystemClock.uptimeMillis() / 70L)
        val bh = bmp.height / bands
        val saved = paint.colorFilter
        for (b in 0 until bands) {
            val y0 = b * bh
            val y1 = if (b == bands - 1) bmp.height else y0 + bh
            srcR.set(0, y0, bmp.width, y1)
            val jitter = if (rnd.nextFloat() < 0.5f) (rnd.nextFloat() - 0.5f) * width * 0.22f else 0f
            paint.colorFilter = when (rnd.nextInt(5)) {
                0 -> cyanFilter
                1 -> pinkFilter
                else -> saved
            }
            dst.set(left + jitter, top + y0 * s, left + jitter + dw, top + y1 * s)
            canvas.drawBitmap(bmp, srcR, dst, paint)
        }
        paint.colorFilter = saved
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

        if (mouthText != null && ambientGlitch > 0 && now >= nextAmbient) {
            nextAmbient = now + 200L
            if (Random.nextFloat() < ambientGlitch / 100f * 0.3f) glitchUntil = maxOf(glitchUntil, now + 120L + Random.nextInt(140))
        }
        val glitchy = now < glitchUntil

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
        drawFrame(canvas, body, shiftX, glitchy)

        val text = mouthText
        val mouth = clips["mouth"]
        if (idle && text != null && mouth != null) {
            val letters = ((now - mouthStart) / 1000f * 14f).toInt()
            var n = letters
            if (n >= text.length) {
                if (mouthHold) n %= text.length else { mouthText = null; n = -1 }
            }
            if (n >= 0) drawFrame(canvas, mouth.frames[mouthFor(text[n], letters)], 0f, false)
        }
        canvas.restore()

        if (notesOn) {
            if (now >= nextNote) {
                nextNote = now + 380L + Random.nextLong(300L)
                notes.add(
                    Note(
                        now,
                        width * (0.1f + 0.8f * Random.nextFloat()),
                        if (Random.nextBoolean()) "♪" else "♫",
                        if (Random.nextBoolean()) 0xFF05D9E8.toInt() else 0xFFFF2A6D.toInt(),
                        0.6f + Random.nextFloat() * 0.6f
                    )
                )
            }
            val iter = notes.iterator()
            while (iter.hasNext()) {
                val n = iter.next()
                val age = (now - n.born) / 2200f
                if (age >= 1f) { iter.remove(); continue }
                notePaint.color = n.color
                notePaint.alpha = ((1f - age) * 255).toInt()
                notePaint.textSize = width * 0.18f
                val y = height * (0.5f - 0.5f * age * n.speed)
                val x = n.x + sin((age * 6f + n.x).toDouble()).toFloat() * width * 0.04f
                canvas.drawText(n.glyph, x, y, notePaint)
            }
        }
        postInvalidateOnAnimation()
    }
}
