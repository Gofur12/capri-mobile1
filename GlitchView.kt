package app.capri.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.random.Random

// Glitch di sebagian layar: pita warna, kotak piksel, garis scan, dan robekan. Hanya gambar di atas layar,
// tidak mengubah isi app di bawahnya. Bentuknya berganti sekitar 18 kali per detik supaya terlihat berkedip.
class GlitchView(context: Context) : View(context) {
    private val paint = Paint()
    private var start = 0L
    private var until = 0L
    private var strength = 1f
    private var centerY = 0f
    private var areaFrac = 0.5f
    private val colors = intArrayOf(
        0xFF05D9E8.toInt(), 0xFFFF2A6D.toInt(), 0xFFFFFFFF.toInt(), 0xFF9B6BFF.toInt(), 0xFF8CFF6A.toInt()
    )

    fun burst(ms: Long, strength: Float, centerY: Float, areaFrac: Float) {
        val now = SystemClock.uptimeMillis()
        start = now
        until = now + ms
        this.strength = strength.coerceIn(0.2f, 1f)
        this.centerY = centerY
        this.areaFrac = areaFrac.coerceIn(0.05f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (now >= until) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = resources.displayMetrics.density
        val total = (until - start).coerceAtLeast(1L).toFloat()
        val t = (now - start) / total
        var env = 1f
        if (t < 0.15f) env = t / 0.15f
        else if (t > 0.75f) env = (1f - t) / 0.25f
        env = env.coerceIn(0f, 1f)
        val rnd = Random(now / 55L)
        val areaH = (h * areaFrac).coerceAtLeast(d * 60f)
        val top = centerY - areaH / 2f
        val topC = top.coerceIn(0f, (h - areaH).coerceAtLeast(0f))
        val bottom = topC + areaH
        val power = strength * env

        // 1. warna layar berkedip
        if (rnd.nextFloat() < 0.5f) {
            paint.color = colors[rnd.nextInt(2)]
            paint.alpha = (28 * power).toInt()
            canvas.drawRect(0f, topC, w, bottom, paint)
        }
        // 2. pita warna
        val bars = (6 + 16 * power).toInt()
        for (i in 0 until bars) {
            val y = topC + rnd.nextFloat() * areaH
            val bh = d * (2f + rnd.nextFloat() * 30f * power)
            val bw = w * (0.25f + 0.75f * rnd.nextFloat())
            val x = rnd.nextFloat() * (w - bw)
            paint.color = colors[rnd.nextInt(colors.size)]
            paint.alpha = ((60 + rnd.nextInt(130)) * power).toInt().coerceIn(0, 255)
            canvas.drawRect(x, y, x + bw, y + bh, paint)
        }
        // 3. kotak piksel
        val blocks = (4 + 10 * power).toInt()
        for (i in 0 until blocks) {
            val s = d * (8f + rnd.nextFloat() * 40f)
            val x = rnd.nextFloat() * (w - s).coerceAtLeast(1f)
            val y = topC + rnd.nextFloat() * (areaH - s).coerceAtLeast(1f)
            paint.color = colors[rnd.nextInt(colors.size)]
            paint.alpha = ((90 + rnd.nextInt(120)) * power).toInt().coerceIn(0, 255)
            canvas.drawRect(x, y, x + s, y + s, paint)
        }
        // 4. garis scan tipis
        paint.color = Color.BLACK
        paint.alpha = (70 * power).toInt().coerceIn(0, 255)
        var y = topC
        while (y < bottom) {
            canvas.drawRect(0f, y, w, y + d, paint)
            y += d * 4f
        }
        // 5. robekan lebar
        if (rnd.nextFloat() < 0.6f) {
            val y2 = topC + rnd.nextFloat() * areaH
            paint.color = Color.WHITE
            paint.alpha = (150 * power).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, y2, w, y2 + d * (2f + rnd.nextFloat() * 6f), paint)
            paint.color = colors[rnd.nextInt(2)]
            paint.alpha = (120 * power).toInt().coerceIn(0, 255)
            canvas.drawRect(rnd.nextFloat() * w * 0.2f, y2 + d * 3f, w, y2 + d * 6f, paint)
        }
        postInvalidateOnAnimation()
    }
}
