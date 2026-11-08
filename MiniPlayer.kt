package app.capri.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

// Pemutar musik mini ala Capri Desktop. Membaca lagu dari app musik apa pun dan menampilkan
// judul, artis, posisi, serta tombol sebelumnya / putar-jeda / berikutnya.
class MiniPlayer(
    private val ctx: Context,
    private val wm: WindowManager
) {
    private val ui = Handler(Looper.getMainLooper())
    private val cy = 0xFF05D9E8.toInt()
    private val pk = 0xFFFF2A6D.toInt()
    private val tx = 0xFFE8F7FF.toInt()
    private val muted = 0xFF7A8AA3.toInt()
    private val bg = 0xF20A0E16.toInt()

    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var titleV: TextView? = null
    private var artistV: TextView? = null
    private var appV: TextView? = null
    private var timeV: TextView? = null
    private var seek: SeekBar? = null
    private var playV: TextView? = null
    private var head: LinearLayout? = null

    private var running = false
    private var visible = false
    private var draggingSeek = false
    private var missingSince = 0L
    private var dismissed = ""

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radius).toFloat()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            ui.postDelayed(this, 1000L)
        }
    }

    fun start() {
        if (running) return
        running = true
        ui.post(tick)
    }

    fun stop() {
        running = false
        ui.removeCallbacks(tick)
        hide()
        root = null
        lp = null
    }

    private fun refresh() {
        val now = MediaWatcher.refresh()
        if (now == null) {
            if (visible) {
                if (missingSince == 0L) missingSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - missingSince > 8000L) {
                    hide()
                    dismissed = ""
                }
            }
            return
        }
        missingSince = 0L
        val key = now.pkg + "|" + now.title
        if (key == dismissed) {
            hide()
            return
        }
        if (!visible) show()
        titleV?.text = now.title.ifBlank { "Musik" }
        artistV?.text = now.artist.ifBlank { " " }
        appV?.text = now.app.uppercase(Locale.US)
        playV?.text = if (now.playing) "⏸" else "▶"
        updateTime(now)
    }

    private fun updateTime(now: MediaWatcher.Now) {
        val s = seek ?: return
        if (!draggingSeek) {
            if (now.durationMs > 0) {
                s.progress = ((now.positionMs * 1000L) / now.durationMs).toInt().coerceIn(0, 1000)
            } else {
                s.progress = 0
            }
        }
        timeV?.text = "${clock(now.positionMs)} / ${clock(now.durationMs)}"
    }

    private fun clock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    private fun control(label: String, size: Float): TextView = TextView(ctx).apply {
        text = label
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(cy)
        setPadding(dp(6), dp(2), dp(6), dp(2))
        isClickable = true
        isFocusable = false
    }

    private fun build() {
        val plate = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = rounded(bg, cy, 16)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(ctx).apply {
            text = "⠿  CAPRI // PLAYER"
            setTextColor(pk)
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        val app = TextView(ctx).apply {
            setTextColor(muted)
            textSize = 9f
            gravity = Gravity.END
            setPadding(dp(6), 0, dp(6), 0)
        }
        val close = TextView(ctx).apply {
            text = "✕"
            setTextColor(pk)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(2), 0)
            isClickable = true
            setOnClickListener {
                dismissed = (MediaWatcher.refresh()?.let { it.pkg + "|" + it.title }) ?: ""
                hide()
            }
        }
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(app)
        header.addView(close)

        val title = TextView(ctx).apply {
            setTextColor(tx)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        val artist = TextView(ctx).apply {
            setTextColor(muted)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val bar = SeekBar(ctx).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(cy)
            thumbTintList = ColorStateList.valueOf(pk)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF2A3A4F.toInt())
            setPadding(0, dp(2), 0, dp(2))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val now = MediaWatcher.refresh()
                        if (now != null && now.durationMs > 0) {
                            timeV?.text = clock(now.durationMs * progress / 1000L) + " / " + clock(now.durationMs)
                        }
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { draggingSeek = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    draggingSeek = false
                    val now = MediaWatcher.refresh()
                    val p = sb?.progress ?: return
                    if (now != null && now.durationMs > 0) MediaWatcher.seekTo(now.durationMs * p / 1000L)
                }
            })
        }
        val time = TextView(ctx).apply {
            setTextColor(muted)
            textSize = 9f
            gravity = Gravity.END
        }
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = control("⏮", 20f)
        val play = control("▶", 24f)
        val next = control("⏭", 20f)
        prev.setOnClickListener {
            MediaWatcher.command("previous")
            ui.postDelayed({ refresh() }, 350L)
        }
        play.setOnClickListener {
            MediaWatcher.command("toggle")
            ui.postDelayed({ refresh() }, 250L)
        }
        next.setOnClickListener {
            MediaWatcher.command("next")
            ui.postDelayed({ refresh() }, 350L)
        }
        for (b in listOf(prev, play, next)) {
            val l = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            l.setMargins(dp(4), 0, dp(4), 0)
            controls.addView(b, l)
        }

        plate.addView(header)
        plate.addView(title)
        plate.addView(artist)
        plate.addView(bar)
        plate.addView(time)
        plate.addView(controls)

        val l = WindowManager.LayoutParams(
            dp(300), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        l.gravity = Gravity.TOP or Gravity.START
        l.x = Cfg.int(ctx, "mp_x", dp(10))
        l.y = Cfg.int(ctx, "mp_y", ctx.resources.displayMetrics.heightPixels - dp(220))
        l.x = l.x.coerceIn(0, (ctx.resources.displayMetrics.widthPixels - dp(300)).coerceAtLeast(0))
        l.y = l.y.coerceIn(0, (ctx.resources.displayMetrics.heightPixels - dp(120)).coerceAtLeast(0))

        var dx = 0f
        var dy = 0f
        var sx = 0
        var sy = 0
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX
                    dy = e.rawY
                    sx = l.x
                    sy = l.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    l.x = (sx + (e.rawX - dx)).toInt()
                    l.y = (sy + (e.rawY - dy)).toInt()
                    try { wm.updateViewLayout(plate, l) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Cfg.set(ctx, "mp_x", l.x.toString())
                    Cfg.set(ctx, "mp_y", l.y.toString())
                    true
                }
                else -> false
            }
        }

        root = plate
        lp = l
        titleV = title
        artistV = artist
        appV = app
        timeV = time
        seek = bar
        playV = play
        head = header
    }

    private fun show() {
        if (visible) return
        if (root == null) build()
        val v = root ?: return
        val l = lp ?: return
        try {
            wm.addView(v, l)
            visible = true
            AppLog.add("MUSIK", "mini player tampil")
        } catch (e: Exception) {
            AppLog.add("MUSIK", "mini player gagal tampil: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun hide() {
        if (!visible) return
        val v = root ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        visible = false
        AppLog.add("MUSIK", "mini player disembunyikan")
    }

    fun isVisible(): Boolean = visible
}
