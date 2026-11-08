package app.capri.mobile

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.random.Random

private const val POP_INSTR = "Say one short unprompted line right now, in character: a greeting, a tease, or a random thought. Under 15 words. Do not ask a question."
private const val CHECK_INSTR = "The user hasn't talked to you in a while. Check in on them warmly in one short sentence, in character. Ask at most one question."
private const val FOLLOW_INSTR = "Add one short follow-up line to what you just said, in character, like a friend who thought of one more thing. Under 15 words. Do not ask a question."
private const val REMEMBER_INSTR = "Describe in one short English sentence what the user is doing on screen right now. Only describe, no greeting."
private const val SCREEN_INSTR = "React to what is on the user's screen right now in one short sentence, in character. Mention only what you can actually see; if you cannot tell, say something else short."
private const val MUSIC_INSTR = "Music just started playing on the user's phone. React in one short line, in character, without asking a question."
private const val HEARD_INSTR = "You just overheard the user say this out loud, maybe to someone else instead of you: \"%s\". React in one very short line, in character. If it is a question you can answer, answer it. If it is just chatter, tease them or comment. Do not be creepy about listening. Do not ask a question."

private val CY = 0xFF05D9E8.toInt()
private val PK = 0xFFFF2A6D.toInt()

// Capri melayang di atas app lain. Ketuk = buka/tutup chat, tahan lalu geser = pindah.
class CapriService : Service() {
    private lateinit var wm: WindowManager
    private var pet: SpriteView? = null
    private var petLp: WindowManager.LayoutParams? = null
    private var petKey = ""
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var reply: TextView? = null
    private var input: EditText? = null
    private var micBtn: Button? = null
    private var callBtn: Button? = null
    private var voiceBtn: Button? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var callMode = false
    private var lastReply = ""
    private var lastUser: String? = null
    private val voice = VoicePlayer()
    private val ui = Handler(Looper.getMainLooper())

    private var sleeping = false
    private var musicOn = false
    private var perched = false
    private var speaking = false
    private var hidden = false
    private var dragActive = false
    private var longFired = false
    private var preX = 0
    private var preY = 0
    private var perchDx = 0
    private var perchDy = 0
    private var glitchBoostUntil = 0L
    private var lastTalk = System.currentTimeMillis()
    private var lastProactive = 0L
    private var lastCheckin = 0L
    private var lastRemember = 0L
    private var bubble: TextView? = null
    private val screenLines = ArrayList<String>()
    private var miniPlayer: MiniPlayer? = null
    private var spawned = false
    private var lastSpawn = 0L
    private var lastHeard = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pi(key: String, def: Int) = Cfg.int(this, key, def)
    private fun po(key: String, def: Boolean) = Cfg.on(this, key, def)
    private fun ps(key: String, def: String) = Cfg.str(this, key, def)
    private fun voiceOn() = Cfg.prefs(this).getBoolean("voice", true) && ps("voice_mode", "cloud") != "off"
    private fun robotLevel() = pi("robot", Cfg.ROBOT)
    private fun glitchLevel(): Int {
        val base = pi("glitch", Cfg.GLITCH)
        return if (System.currentTimeMillis() < glitchBoostUntil) maxOf(base, 70) else base
    }
    private fun volumeGain(): Float = (pi("volume", Cfg.VOLUME) / 100f).coerceIn(0f, 1.5f)
    private fun speedValue(): Float = Cfg.float(this, "eleven_speed", 1.0f).coerceIn(0.7f, 1.2f)
    private fun pitchValue(): Float = (pi("pitch_pct", Cfg.PITCH) / 100f).coerceIn(0.5f, 1.6f)
    private fun petWidthDp(): Int = (90 * pi("size_pct", Cfg.SIZE) / 100).coerceIn(45, 180)
    private fun edgeSit() = po("perch", false)
    private fun activity() = pi("activity", Cfg.ACTIVITY).coerceIn(0, 100)
    private fun earsOn() = po("wake", true)
    private fun hearAll() = po("heareverything", false)
    private fun watchEveryMs(): Long = pi("watch_every", Cfg.WATCH_EVERY).coerceIn(5, 60) * 1000L
    private fun miniPlayerOn() = po("miniplayer", true)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Brain.load(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.getDefault())
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.US)
                tts?.setPitch(1.15f)
                tts?.setSpeechRate(1.05f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        ui.post {
                            speaking = true
                            pet?.ambientGlitch = glitchLevel()
                            pet?.talk(lastReply, true)
                            applyRest()
                        }
                    }
                    override fun onDone(id: String?) { ui.post { spoke() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { ui.post { spoke() } }
                })
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        AppLog.add("LAYANAN", "onStartCommand aksi=${i?.action ?: "-"}")
        if (i?.action == "screen_start") ScreenWatch.wanted = true
        startAsForeground()
        val key = pi("size_pct", Cfg.SIZE).toString() + "|" + ps("perch", "off")
        if (pet != null && petKey != key) {
            try { wm.removeView(pet!!) } catch (_: Exception) {}
            pet = null
            removeBubble()
        }
        if (pet == null) {
            petKey = key
            showPet()
        }
        if (miniPlayerOn()) startMiniPlayer() else stopMiniPlayer()
        if (earsOn() && panel == null && !listening && !callMode) wakeLoop(400)
        when (i?.action) {
            "hide" -> toggleHide()
            "bring" -> bringHere()
            "spawn" -> spawnSequence(true)
            "screen_start" -> {
                val code = i?.getIntExtra("code", 0) ?: 0
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    i?.getParcelableExtra("data", Intent::class.java)
                else i?.getParcelableExtra("data")
                if (data != null) {
                    try {
                        ScreenWatch.start(this, code, data, 0L, pi("watchsave", Cfg.WATCHSAVE) * 1000L)
                        AppLog.add("LAYAR", "ScreenWatch.start selesai")
                    } catch (e: Exception) {
                        ScreenWatch.wanted = false
                        AppLog.add("LAYAR", "start gagal: ${e.javaClass.name}: ${e.message}")
                        android.widget.Toast.makeText(this, "Lihat layar gagal: ${e.javaClass.simpleName} ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            "screen_stop" -> {
                ScreenWatch.wanted = false
                ScreenWatch.stop()
                startAsForeground()
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("capri", "Capri", NotificationManager.IMPORTANCE_LOW))
        fun action(act: String, label: String): Notification.Action {
            val pi = PendingIntent.getService(
                this, act.hashCode(),
                Intent(this, CapriService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_view), label, pi).build()
        }
        val n = Notification.Builder(this, "capri")
            .setContentTitle("Capri aktif")
            .setContentText(if (ScreenWatch.wanted) "Capri sedang melihat layar" else "Ketuk Capri di layar buat ngobrol")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(action("hide", if (hidden) "Tampilkan" else "Sembunyikan"))
            .addAction(action("bring", "Bawa ke sini"))
            .build()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val wantScreen = ScreenWatch.wanted && Build.VERSION.SDK_INT >= 29
        val withMic = mic && Build.VERSION.SDK_INT >= 30
        var type = 0
        if (Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (wantScreen) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (withMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            if (Build.VERSION.SDK_INT >= 29 && type != 0) startForeground(1, n, type)
            else startForeground(1, n)
            AppLog.add("LAYANAN", "foreground aktif, tipe=$type (layar=$wantScreen, mik=$withMic)")
        } catch (e: Exception) {
            AppLog.add("LAYANAN", "foreground gagal, tipe=$type: ${e.javaClass.name}: ${e.message}")
            android.widget.Toast.makeText(this, "Layanan gagal: ${e.javaClass.simpleName} ${e.message?.take(120)}", android.widget.Toast.LENGTH_LONG).show()
            try {
                startForeground(1, n)
            } catch (_: Exception) {
                stopSelf()
            }
        }
    }

    // ---------- Animasi dan keadaan ----------

    // Animasi "diam" Capri sesuai keadaan: tidur, duduk (panel chat atau tepi), joget kalau ada musik, atau berdiri.
    private fun restClip(): String = when {
        sleeping -> "sleep"
        (perched || edgeSit()) && speaking -> "perch_talk"
        perched || edgeSit() -> if (musicOn) "perch_music" else "perch"
        musicOn -> "dance"
        else -> "idle"
    }

    private fun applyRest() {
        val v = pet ?: return
        v.rest = restClip()
        v.play(restClip())
    }

    private val sleepRun = Runnable {
        if (panel == null) {
            sleeping = true
            applyRest()
        }
    }

    private fun wake() {
        if (sleeping) { sleeping = false; pet?.rest = restClip(); pet?.play("wake", loop = false) }
        ui.removeCallbacks(sleepRun)
        val mins = pi("sleep", 0)
        if (mins > 0) ui.postDelayed(sleepRun, mins * 60_000L)
    }

    // ---------- Glitch di layar ----------

    private var glitchView: GlitchView? = null
    private val glitchOff = Runnable { removeGlitch() }

    private fun removeGlitch() {
        glitchView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        glitchView = null
    }

    // Glitch di sebagian layar (pita warna, kotak, garis scan) di sekitar Capri.
    // Kadang pakai setengah layar dengan efek retak supaya nggak monoton.
    private fun screenGlitch(ms: Long, strength: Float) {
        val area = pi("glitch_area", 50)
        if (area <= 0 || pet == null || hidden) return
        try {
            var gv = glitchView
            if (gv == null) {
                gv = GlitchView(this)
                val glp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                wm.addView(gv, glp)
                glitchView = gv
            }
            val plp = petLp
            val cy = if (plp != null) plp.y + plp.height / 2f else resources.displayMetrics.heightPixels / 2f
            val half = Random.nextFloat() < 0.35f
            val frac = if (half) 0.5f else area / 100f
            val doCrack = Random.nextFloat() < 0.35f
            if (doCrack) {
                gv.burstCrack(ms, strength, cy, frac)
            } else {
                gv.burst(ms, strength, cy, frac)
            }
            ui.removeCallbacks(glitchOff)
            ui.postDelayed(glitchOff, ms + 150L)
            Chime.zap(pi("sounds", Cfg.SOUNDS))
            AppLog.add("GLITCH", "glitch layar $ms ms, area ${(frac * 100).toInt()}%, retak=$doCrack")
        } catch (e: Exception) {
            AppLog.add("GLITCH", "gagal: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    // Ekspresi sesuai isi balasan.
    private fun expression(text: String) {
        val v = pet ?: return
        val t = text.lowercase()
        val sit = perched || edgeSit()
        when {
            Regex("glitch|static|error|rusak|crash|sinyal").containsMatchIn(t) -> {
                v.glitchBurst(1400L)
                screenGlitch(900L, 0.6f)
                v.play(if (sit) "perch_glitch" else "glitch", loop = false)
            }
            Regex("haha|hehe|wkwk|lol|hihi|asik|mantap|keren|yay|seru").containsMatchIn(t) || t.trimEnd().endsWith("!") -> {
                if (!sit && !musicOn && !sleeping) v.play("grin", loop = false)
            }
        }
    }

    // Tingkah kecil saat nganggur: glitch sebentar, nyengir, atau joget.
    private fun quirk() {
        val v = pet ?: return
        if (speaking || sleeping || dragActive || panel != null || hidden) return
        val r = Random.nextFloat()
        when {
            r < 0.5f -> {
                v.glitchBurst(380L)
                if (activity() >= 50) screenGlitch(500L, 0.5f)
            }
            r < 0.8f -> if (!perched && !musicOn && !edgeSit()) v.play("grin", loop = false)
            else -> if (!musicOn && !edgeSit()) v.play("dance", loop = false)
        }
    }

    // Aksi badan dari perintah di chat. Hasilnya tetap diteruskan ke otak supaya Capri menanggapinya.
    private fun bodyAction(t: String): String? {
        val l = t.lowercase()
        return when {
            Regex("\\bglitch").containsMatchIn(l) -> {
                pet?.glitchBurst(2200L)
                screenGlitch(2200L, 1f)
                glitchBoostUntil = System.currentTimeMillis() + 45_000L
                "glitch"
            }
            Regex("\\b(joget|dance|nari|goyang)").containsMatchIn(l) -> {
                pet?.play(if (perched || edgeSit()) "perch_music" else "dance")
                ui.postDelayed({ applyRest() }, 4000L)
                "joget"
            }
            Regex("\\b(tidur|bobo|sleep)\\b").containsMatchIn(l) -> {
                sleeping = true
                applyRest()
                "tidur"
            }
            Regex("\\b(bangun|wake up)\\b").containsMatchIn(l) -> "bangun"
            else -> null
        }
    }

    // ---------- Duduk di panel dan posisinya ----------

    private fun perchBase(): Pair<Int, Int>? {
        val box = panel ?: return null
        val lp = petLp ?: return null
        if (box.width <= 0) return null
        val loc = IntArray(2)
        box.getLocationOnScreen(loc)
        val bx = loc[0] + box.width - lp.width - dp(6)
        val by = (loc[1] - lp.height * 0.66f).toInt()
        return Pair(bx, by)
    }

    private val perchRun = object : Runnable {
        override fun run() {
            val lp = petLp
            val v = pet
            if (panel != null && lp != null && v != null) {
                val base = perchBase()
                if (base != null && !dragActive) {
                    val maxX = resources.displayMetrics.widthPixels - lp.width
                    val tx = (base.first + perchDx).coerceIn(0, maxX.coerceAtLeast(0))
                    val ty = (base.second + perchDy).coerceAtLeast(0)
                    if (abs(tx - lp.x) > 2 || abs(ty - lp.y) > 2) {
                        lp.x = tx
                        lp.y = ty
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
                ui.postDelayed(this, 200L)
            }
        }
    }

    private fun startPerch() {
        val lp = petLp ?: return
        if (!perched) { preX = lp.x; preY = lp.y }
        perched = true
        sleeping = false
        perchDx = pi("perch_dx", 0)
        perchDy = pi("perch_dy", 0)
        pet?.setFlip(false)
        pet?.rest = restClip()
        pet?.play("perch_arrive", loop = false)
        ui.removeCallbacks(perchRun)
        ui.post(perchRun)
    }

    private fun stopPerch() {
        ui.removeCallbacks(perchRun)
        if (!perched) return
        perched = false
        val lp = petLp
        val v = pet
        if (lp != null && v != null) {
            lp.x = preX
            lp.y = preY
            try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
        }
        applyRest()
    }

    // Setelah Capri digeser saat duduk, simpan selisihnya dari posisi duduk biasa.
    private fun savePerchOffset() {
        val lp = petLp ?: return
        val base = perchBase() ?: return
        perchDx = lp.x - base.first
        perchDy = lp.y - base.second
        Cfg.set(this, "perch_dx", perchDx.toString())
        Cfg.set(this, "perch_dy", perchDy.toString())
    }

    // ---------- Gerak: jalan-jalan, pindah, sembunyi ----------

    private val wanderRun = object : Runnable {
        override fun run() {
            if (po("wander", false) && pet != null && panel == null && !dragActive && !sleeping) wanderTo(randomSpot())
            ui.postDelayed(this, 25000L)
        }
    }

    private fun randomSpot(): Pair<Int, Int> {
        val lp = petLp ?: return Pair(0, 0)
        val maxX = (resources.displayMetrics.widthPixels - lp.width).coerceAtLeast(0)
        val minY = dp(60)
        val maxY = (resources.displayMetrics.heightPixels - lp.height - dp(120)).coerceAtLeast(minY + 1)
        return Pair(Random.nextInt(maxX + 1), minY + Random.nextInt(maxY - minY))
    }

    private fun wanderTo(target: Pair<Int, Int>) {
        val lp = petLp ?: return
        val v = pet ?: return
        val fromX = lp.x
        val fromY = lp.y
        v.setFlip(target.first < fromX)
        v.play("walk")
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { applyRest() }
            })
            addUpdateListener { a ->
                val f = a.animatedFraction
                lp.x = (fromX + (target.first - fromX) * f).toInt()
                lp.y = (fromY + (target.second - fromY) * f).toInt()
                try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
            }
            start()
        }
    }

    // Tahan lama Capri = pindah ke tengah bawah layar.
    private fun bringHere() {
        val lp = petLp ?: return
        val v = pet ?: return
        lp.x = (resources.displayMetrics.widthPixels - lp.width) / 2
        lp.y = resources.displayMetrics.heightPixels - lp.height - dp(120)
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
        wake()
    }

    private fun toggleHide() {
        hidden = !hidden
        val v = pet
        if (v != null) {
            v.rest = restClip()
            if (hidden) {
                v.play(if (perched) "perch_leave" else "leave", loop = false)
                ui.postDelayed({
                    if (hidden) {
                        pet?.visibility = View.GONE
                    }
                }, 780L)
            } else {
                v.visibility = View.VISIBLE
                v.play(if (perched) "perch_arrive" else "arrive", loop = false)
            }
        }
        startAsForeground()
    }

    private val longPressRun = Runnable {
        longFired = true
        bringHere()
    }

    private fun showPet() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = SpriteView(this)
        v.setHologram(po("hologram", false))
        val lp = WindowManager.LayoutParams(
            dp(petWidthDp()), dp(petWidthDp() * 8 / 5),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(16)
        lp.y = dp(240)
        petLp = lp

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                    longFired = false
                    wake()
                    ui.postDelayed(longPressRun, 600L)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                        dragging = true
                        dragActive = true
                        ui.removeCallbacks(longPressRun)
                        v.play("dangle")
                    }
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longPressRun)
                    if (dragging) {
                        if (perched) savePerchOffset()
                        dragActive = false
                        applyRest()
                    } else if (!longFired) {
                        if (po("headpats", true) && e.y < v.height * 0.35f) {
                            v.play("grin", loop = false) // elus kepala
                        } else {
                            togglePanel()
                        }
                    }
                    longFired = false
                    wake()
                }
            }
            true
        }
        try {
            wm.addView(v, lp)
            pet = v
            v.rest = restClip()
            if (edgeSit()) {
                bringHere()
                v.play("perch_arrive", loop = false)
            } else {
                v.play("arrive", loop = false)
            }
            wake()
            ui.postDelayed(wanderRun, 25000L)
            ui.postDelayed(proactiveRun, 30000L)
            ui.postDelayed(rememberRun, 5000L)
            ui.postDelayed(musicRun, 3000L)
            spawnSequence(false)
            if (miniPlayerOn()) startMiniPlayer() else stopMiniPlayer()
            if (earsOn()) wakeLoop(1000)
        } catch (e: Exception) {
            stopSelf() // izin overlay belum diberikan
        }
    }

    // ---------- Spawn dan pemutar musik mini ----------

    // Efek muncul pertama kali: glitch layar, tendangan sinyal, lalu sapaan ikonik Capri.
    private fun spawnSequence(force: Boolean) {
        if (!force && spawned) return
        if (force && System.currentTimeMillis() - lastSpawn < 2500L) return
        if (!po("spawn", Cfg.SPAWN)) return
        val v = pet ?: return
        spawned = true
        lastSpawn = System.currentTimeMillis()
        v.glitchBurst(1600L)
        screenGlitch(1500L, 1f)
        Chime.zap(pi("sounds", Cfg.SOUNDS))
        Chime.play(pi("sounds", Cfg.SOUNDS), intArrayOf(392, 523, 784, 1046))
        v.play("arrive", loop = false)
        ui.postDelayed({
            val line = spawnLine()
            val file = spawnVoiceName()
            val played = Sfx.playAsset(
                this,
                listOf("voice/$file", "assets/voice/$file", file, "assets/$file"),
                pi("volume", Cfg.VOLUME)
            )
            if (played) {
                lastReply = line
                say(line)
                showBubble(line)
                pet?.talk(line, false)
                ui.postDelayed({ pet?.stopTalk(); applyRest() }, 2800L)
            } else {
                deliver(line, false)
            }
        }, 700L)
        AppLog.add("SPAWN", "efek spawn dijalankan (suara ikonik: ${spawnVoiceName()})")
    }

    private fun spawnVoiceName(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour in 5..11 -> "hello-morning.wav"
            hour >= 22 || hour < 5 -> "hello-late.wav"
            else -> "hello-miss.wav"
        }
    }

    private fun spawnLine(): String {
        val id = listOf(
            "Sistem online. Kangen, choom?",
            "Aku balik. Jangan bilang kamu nggak kangen.",
            "Sinyal ketemu. Ada misi buatku?",
            "Nyawa baru, HP lama. Ayo bikin rusuh.",
            "Beaming in. Missed me?",
            "Kembali online. Dunia ini masih berisik."
        )
        val en = listOf(
            "System online. Missed me, choom?",
            "Beaming in. You look like you need trouble.",
            "Signal found. What are we breaking today?",
            "Back online. Try not to miss me too much.",
            "New life, same phone. Let's cause something."
        )
        return if (ps("lang", "id") in listOf("id", "auto")) id.random() else en.random()
    }

    private fun startMiniPlayer() {
        if (!miniPlayerOn() || pet == null || !this::wm.isInitialized) { stopMiniPlayer(); return }
        if (!MediaWatcher.hasAccess(this)) {
            miniPlayer?.stop()
            miniPlayer = null
            return
        }
        val mp = miniPlayer ?: MiniPlayer(this, wm).also { miniPlayer = it }
        mp.start()
    }

    private fun stopMiniPlayer() {
        miniPlayer?.stop()
        miniPlayer = null
    }

    // ---------- Keaktifan, musik, ingatan layar ----------

    private val proactiveRun = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val act = activity()
            if (panel == null && pet != null && !sleeping && !hidden && act > 0) {
                val gapMin = (96f - act * 0.9f).coerceAtLeast(3f)
                if (now - lastProactive > (gapMin * 60_000f).toLong()) {
                    lastProactive = now
                    val screenOk = po("watch", false) && po("screencomment", true) && act >= 50 && Random.nextFloat() < 0.6f
                    if (screenOk) proactive(SCREEN_INSTR, true) else proactive(POP_INSTR, false)
                }
                if (po("checkin", false) && now - lastTalk > 2 * 3_600_000L && now - lastCheckin > 2 * 3_600_000L) {
                    lastCheckin = now
                    lastTalk = now
                    proactive(CHECK_INSTR, false)
                }
                if (po("chaos", false) && Random.nextFloat() < 0.05f * (1f + act / 50f)) {
                    pet?.glitchBurst(900L)
                    screenGlitch(1100L, 0.8f)
                    pet?.play(if (Random.nextBoolean()) "glitch" else "dance", loop = false)
                }
                if (Random.nextFloat() < act / 100f * 0.5f) quirk()
            }
            rememberScreen(now)
            ui.postDelayed(this, 30_000L)
        }
    }

    // Cek tiap 3 detik apakah ada musik dari app lain. Suara Capri sendiri tidak dihitung.
    private var musicOff = 0

    private val musicRun = object : Runnable {
        override fun run() {
            val am = getSystemService(AudioManager::class.java)
            // Saat mik mendengar atau Capri bicara, HP sering menjeda musik sebentar: jangan ubah keadaan.
            val busy = voice.active || speaking || listening || tts?.isSpeaking == true
            if (!busy) {
                val media = MediaWatcher.refresh()
                val raw = po("music", true) && (am.isMusicActive || media?.playing == true)
                if (raw) musicOff = 0 else musicOff++
                val playing = raw || (musicOn && musicOff < 2)
                if (playing != musicOn && !dragActive && !sleeping) {
                    musicOn = playing
                    AppLog.add("MUSIK", if (playing) "musik terdeteksi, Capri joget" else "musik berhenti")
                    pet?.setNotes(playing && po("music_notes", true))
                    if (playing && po("music_notes", true)) Chime.play(pi("sounds", Cfg.SOUNDS))
                    if (playing && panel == null && activity() >= 40 && Random.nextFloat() < activity() / 150f) proactive(MUSIC_INSTR, false)
                    applyRest()
                }
            }
            if (miniPlayerOn() && MediaWatcher.hasAccess(this@CapriService)) startMiniPlayer() else if (!miniPlayerOn()) stopMiniPlayer()
            ui.postDelayed(this, 3000L)
        }
    }

    // Cek ingatan layar lebih rapat dari aktivitas, supaya slider 5-60 detik benar-benar berlaku.
    private val rememberRun = object : Runnable {
        override fun run() {
            rememberScreen(System.currentTimeMillis())
            ui.postDelayed(this, 5000L)
        }
    }

    // Tiap beberapa detik (slider 5-60 detik), kalau layar dibagikan: Capri catat apa yang sedang dilihat.
    private fun rememberScreen(now: Long) {
        if (!po("remember", false) || !po("watch", false)) return
        if (now - lastRemember < watchEveryMs()) return
        lastRemember = now
        val key = ps("key", "").trim()
        if (key.isEmpty()) return
        val model = ps("model", "").trim().ifEmpty { Brain.DEFAULT_MODEL }
        val memory = pi("memory", Cfg.MEMORY)
        val max = pi("watchmem", Cfg.WATCHMEM)
        thread {
            val img = ScreenWatch.latestB64() ?: return@thread
            val line = Brain.ask(key, model, REMEMBER_INSTR, "", "en", "short", memory, true, img, "")
            if (line.isBlank() || Brain.isError(line)) return@thread
            synchronized(screenLines) {
                screenLines.add(line.take(160))
                while (screenLines.size > max) screenLines.removeAt(0)
            }
        }
    }

    private val followRun = Runnable { proactive(FOLLOW_INSTR, false) }

    // Capri ngomong duluan (tanpa diminta). withScreen = komentar tentang isi layar.
    private fun proactive(instruction: String, withScreen: Boolean) {
        val key = ps("key", "").trim()
        if (key.isEmpty()) return
        val model = ps("model", "").trim().ifEmpty { Brain.DEFAULT_MODEL }
        val nick = ps("nick", "").trim()
        val lang = ps("lang", "id")
        val replies = ps("replies", "short")
        val memory = pi("memory", Cfg.MEMORY)
        thread {
            val img = if (withScreen) ScreenWatch.latestB64() else null
            if (withScreen && img == null) return@thread
            val out = Brain.ask(key, model, instruction, nick, lang, replies, memory, true, img, "")
            if (out.isBlank() || Brain.isError(out)) return@thread
            ui.post {
                if (!sleeping && !hidden && !voice.active && !speaking) deliver(out, false)
            }
        }
    }

    private fun playTone() {
        val vol = pi("sounds", Cfg.SOUNDS).coerceIn(0, 100)
        if (vol == 0) return
        try {
            val g = ToneGenerator(AudioManager.STREAM_NOTIFICATION, vol)
            g.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
            ui.postDelayed({ g.release() }, 200L)
        } catch (_: Exception) {}
    }

    // ---------- Gelembung bicara ----------

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    private val bubbleHide = Runnable { removeBubble() }

    private fun showBubble(msg: String) {
        if (panel != null) return // saat chat terbuka, jawaban sudah tampil di panel
        val base = petLp ?: return
        if (pet == null) return
        removeBubble()
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(10), dp(7), dp(10), dp(7))
            maxWidth = dp(230)
            background = GradientDrawable().apply {
                setColor(0xF2101826.toInt())
                setStroke(dp(2), CY)
                cornerRadius = dp(12).toFloat()
            }
            text = msg
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = base.x.coerceIn(0, (resources.displayMetrics.widthPixels - dp(240)).coerceAtLeast(0))
        lp.y = (base.y - dp(90)).coerceAtLeast(dp(20))
        try {
            wm.addView(tv, lp)
            bubble = tv
        } catch (_: Exception) {
            return
        }
        ui.removeCallbacks(bubbleHide)
        ui.postDelayed(bubbleHide, pi("bubble_sec", Cfg.BUBBLE_SEC).coerceIn(3, 60) * 1000L)
    }

    // ---------- Panel chat (bisa digeser) ----------

    private fun chip(label: String, fill: Int, textColor: Int): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(4), dp(9), dp(4), dp(9))
        setTextColor(textColor)
        background = GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1), CY)
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun togglePanel() {
        if (panel != null) { closePanel(); return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xF2101826.toInt())
                setStroke(dp(2), CY)
                cornerRadius = dp(18).toFloat()
            }
        }

        // Kepala panel: foto, nama, status, dan pegangan untuk menggeser.
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val avatar = ImageView(this).apply {
            try {
                val bmp = BitmapFactory.decodeStream(assets.open("sprites/face.png"))
                setImageBitmap(android.graphics.Bitmap.createScaledBitmap(bmp, dp(34), dp(34), true))
            } catch (_: Exception) {}
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
        }
        val title = TextView(this).apply {
            text = "CAPRI"
            setTextColor(PK)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.12f
            setPadding(dp(8), 0, dp(6), 0)
        }
        val status = TextView(this).apply {
            text = "● online"
            setTextColor(CY)
            textSize = 10f
        }
        val grab = TextView(this).apply {
            text = "⠿ geser"
            setTextColor(0xFF7A8AA3.toInt())
            textSize = 11f
            gravity = Gravity.END
        }
        head.addView(avatar, LinearLayout.LayoutParams(dp(34), dp(34)))
        head.addView(title)
        head.addView(status)
        head.addView(grab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val r = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 7
            setPadding(0, dp(2), 0, dp(6))
        }
        val inp = EditText(this).apply {
            hint = "ketik ke Capri…"
            setTextColor(0xFFE8F7FF.toInt())
            setHintTextColor(0xFF6B7A8F.toInt())
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xFF0B1120.toInt())
                setStroke(dp(1), 0xFF2A3A4F.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        inp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (po("typingsound", false)) playTone()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val send = chip("Kirim", CY, 0xFF0B1120.toInt())
        val mic = chip("🎤", 0xFF16202E.toInt(), Color.WHITE)
        val call = chip("📞", 0xFF16202E.toInt(), Color.WHITE)
        val voiceB = chip("🔊", 0xFF16202E.toInt(), Color.WHITE)
        val close = chip("✕", 0xFF2A1420.toInt(), PK)
        if (po("mini", false)) {
            mic.visibility = View.GONE
            call.visibility = View.GONE
            voiceB.visibility = View.GONE
        }
        val row = LinearLayout(this)
        for (b in listOf(send, mic, call, voiceB, close)) {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), dp(6), dp(2), 0)
            row.addView(b, lp)
        }
        box.addView(head)
        box.addView(r)
        box.addView(inp)
        box.addView(row)

        send.setOnClickListener { sendTyped() }
        inp.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        mic.setOnClickListener { if (listening) stopListening() else startListening() }
        call.setOnClickListener {
            callMode = !callMode
            if (callMode) startListening() else stopListening()
            refresh()
        }
        voiceB.setOnClickListener {
            Cfg.prefs(this).edit().putBoolean("voice", !voiceOn()).apply()
            if (!voiceOn()) stopAudio()
            refresh()
        }
        close.setOnClickListener { closePanel() }

        val width = (resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(360))
        val lp = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // fokusable, jadi keyboard bisa muncul
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.x = pi("panel_x", 0)
        lp.y = pi("panel_y", dp(24))
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        // Geser panel dengan menyeret kepalanya; posisi diingat.
        var dX = 0f
        var dY = 0f
        var sX = 0
        var sY = 0
        head.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = e.rawX; dY = e.rawY
                    sX = lp.x; sY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = ((resources.displayMetrics.widthPixels - lp.width) / 2).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - box.height - dp(40)).coerceAtLeast(0)
                    lp.x = (sX + (e.rawX - dX)).toInt().coerceIn(-maxX, maxX)
                    lp.y = (sY - (e.rawY - dY)).toInt().coerceIn(0, maxY)
                    try { wm.updateViewLayout(box, lp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Cfg.set(this, "panel_x", lp.x.toString())
                    Cfg.set(this, "panel_y", lp.y.toString())
                    true
                }
                else -> false
            }
        }

        wm.addView(box, lp)
        panel = box
        panelLp = lp
        reply = r
        input = inp
        micBtn = mic
        callBtn = call
        voiceBtn = voiceB
        renderReply(null, "Yo. Ketik atau tekan mik dan ngomong.")
        refresh()
        startPerch()
    }

    private fun closePanel() {
        callMode = false
        stopListening()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        panelLp = null
        reply = null
        input = null
        micBtn = null
        callBtn = null
        voiceBtn = null
        stopPerch()
        if (wakeOn()) wakeLoop(800)
    }

    private fun refresh() {
        micBtn?.text = if (listening) "⏹" else "🎤"
        callBtn?.text = if (callMode) "📴" else "📞"
        voiceBtn?.text = if (voiceOn()) "🔊" else "🔇"
    }

    // Tulis percakapan: baris abang (abu-abu miring) dan jawaban Capri (nama merah muda, isi cyan).
    private fun renderReply(user: String?, capri: String) {
        val sb = SpannableStringBuilder()
        if (!user.isNullOrEmpty()) {
            val u = "> you: " + user.take(80) + "\n"
            sb.append(u)
            sb.setSpan(ForegroundColorSpan(0xFF7A8AA3.toInt()), 0, u.length, 0)
            sb.setSpan(StyleSpan(Typeface.ITALIC), 0, u.length, 0)
            sb.setSpan(RelativeSizeSpan(0.85f), 0, u.length, 0)
        }
        val s1 = sb.length
        sb.append("CAPRI: ")
        sb.setSpan(ForegroundColorSpan(PK), s1, sb.length, 0)
        sb.setSpan(StyleSpan(Typeface.BOLD), s1, sb.length, 0)
        val s2 = sb.length
        sb.append(capri)
        sb.setSpan(ForegroundColorSpan(0xFFBFF8FF.toInt()), s2, sb.length, 0)
        reply?.text = sb
    }

    private fun say(msg: String) { renderReply(lastUser, msg) }

    // ---------- Kirim ke otak ----------

    private fun sendTyped() {
        val inp = input ?: return
        val text = inp.text.toString().trim()
        if (text.isEmpty()) return
        inp.setText("")
        ask(text)
    }

    private fun ask(text: String) {
        lastUser = text
        wake()
        stopAudio()
        ui.removeCallbacks(followRun)
        lastTalk = System.currentTimeMillis()
        if (handleCommand(text)) return
        val key = ps("key", "").trim()
        val model = ps("model", "").trim().ifEmpty { Brain.DEFAULT_MODEL }
        val nick = ps("nick", "").trim()
        if (key.isEmpty()) {
            say("Belum ada API key. Buka app Capri Mobile dan isi key nvapi- dulu.")
            return
        }
        say("…")
        val action = bodyAction(text)
        if (action == null) {
            pet?.play(if (perched || edgeSit()) "perch_glitch" else "glitch")
        }
        val sent = if (action != null) "$text\n[Aplikasi baru saja menjalankan aksi: $action. Tanggapi sebagai Capri.]" else text
        val lang = ps("lang", "id")
        val replies = ps("replies", "short")
        val memory = pi("memory", Cfg.MEMORY)
        val watchOn = po("watch", false)
        thread {
            val img = if (watchOn) ScreenWatch.latestB64() else null
            AppLog.add(
                "LAYAR",
                if (img != null) "gambar layar dikirim ke otak (${img.length / 1024} KB)"
                else if (watchOn) "Lihat layar nyala, tapi belum ada gambar"
                else "Lihat layar mati di pengaturan, gambar tidak dikirim"
            )
            val ctx = synchronized(screenLines) { screenLines.joinToString("; ") }
            val out = Brain.ask(key, model, sent, nick, lang, replies, memory, false, img, ctx)
            ui.post { deliver(out, Brain.isError(out)) }
        }
    }

    // ---------- Perintah lokal: kontrol HP ----------

    private fun localReply(msg: String) { deliver(msg, false) }

    private fun setTouchable(on: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        petLp?.let { lp ->
            lp.flags = if (on) lp.flags and flag.inv() else lp.flags or flag
            pet?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) {} }
        }
        panelLp?.let { lp ->
            lp.flags = if (on) lp.flags and flag.inv() else lp.flags or flag
            panel?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) {} }
        }
    }

    private fun phone(okMsg: String, failMsg: String, action: (CapriAccess) -> Boolean) {
        if (!po("control", true)) { localReply("Kontrol HP lagi dimatikan di pengaturan."); return }
        val acc = CapriAccess.instance
        if (acc == null) {
            localReply("Aku belum boleh mengontrol HP. Nyalakan \"Capri Mobile\" di Pengaturan > Aksesibilitas dulu.")
            return
        }
        say("…")
        setTouchable(false)
        thread {
            val ok = try {
                action(acc)
            } catch (e: Exception) {
                AppLog.add("KONTROL", "gagal: ${e.javaClass.simpleName} ${e.message}")
                false
            }
            ui.post {
                setTouchable(true)
                localReply(if (ok) okMsg else failMsg)
            }
        }
    }

    private fun lev(a: String, b: String): Int {
        val row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = row[0]
            row[0] = i
            for (j in 1..b.length) {
                val tmp = row[j]
                row[j] = minOf(row[j] + 1, row[j - 1] + 1, prev + (if (a[i - 1] == b[j - 1]) 0 else 1))
                prev = tmp
            }
        }
        return row[b.length]
    }

    private class AppHit(val label: String, val pkg: String)

    private fun cleanAppQuery(raw: String): String =
        raw.trim().removeSuffix(" dong").removeSuffix(" ya").removeSuffix(" please").trim()

    private fun findAppHit(q: String): AppHit? {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val labeled = apps.map { it to it.loadLabel(pm).toString() }
        val ql = q.lowercase()
        val hit = labeled.firstOrNull { it.second.equals(q, true) }
            ?: labeled.firstOrNull { it.second.contains(q, true) }
            ?: labeled.firstOrNull { it.second.length >= 4 && q.contains(it.second, true) }
            ?: labeled.minByOrNull { lev(it.second.lowercase(), ql) }?.takeIf { q.length >= 4 && lev(it.second.lowercase(), ql) <= 2 }
        return hit?.let { AppHit(it.second, it.first.activityInfo.packageName) }
    }

    private fun openApp(query: String, announce: Boolean = true) {
        val q = cleanAppQuery(query)
        if (q.isEmpty()) return
        if (announce) say("…")
        thread {
            val pm = packageManager
            val hit = findAppHit(q)
            if (hit != null) {
                val launch = pm.getLaunchIntentForPackage(hit.pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ui.post {
                        try {
                            startActivity(launch)
                            if (announce) localReply("Oke, aku bukain ${hit.label}.")
                        } catch (e: Exception) {
                            AppLog.add("KONTROL", "buka app gagal: ${e.javaClass.simpleName} ${e.message}")
                            if (announce) localReply("Gagal membuka ${hit.label}. Mungkin HP-nya butuh izin tampil di latar belakang.")
                        }
                    }
                    return@thread
                }
            }
            // Tidak ketemu di daftar app: cari sambil menggulir kalau kontrol HP aktif.
            val acc = CapriAccess.instance
            if (acc != null && po("control", true)) {
                ui.post {
                    setTouchable(false)
                    if (announce) say("Nggak ada di daftar, kucari sambil gulir…")
                }
                val ok = try { acc.openByScrolling(q) } catch (e: Exception) { false }
                ui.post {
                    setTouchable(true)
                    if (announce) localReply(if (ok) "Ketemu, kubuka $q." else "Udah kugulir, nggak nemu \"$q\".")
                }
            } else {
                ui.post {
                    if (announce) localReply("Nggak nemu app \"$q\" di HP ini. Kalau mau kucari sambil gulir, nyalakan kontrol HP di Aksesibilitas.")
                }
            }
        }
    }

    private fun closeApp(query: String, announce: Boolean = true) {
        val q = cleanAppQuery(query)
        val here = q.isEmpty() || q in listOf("ini", "app", "aplikasi", "aplikasi ini", "app ini", "sekarang", "yang lagi buka", "yang terbuka")
        if (announce) say("…")
        thread {
            val acc = CapriAccess.instance
            val am = getSystemService(ActivityManager::class.java)
            if (here) {
                val cur = try { acc?.foregroundPackage() } catch (_: Exception) { null }
                val ok = try { acc?.home() ?: false } catch (_: Exception) { false }
                if (cur != null && cur != packageName) {
                    try { am.killBackgroundProcesses(cur) } catch (_: Exception) {}
                }
                ui.post { if (announce) localReply(if (ok) "Oke, kututup app yang lagi kebuka." else "Kontrol HP belum aktif, jadi aku nggak bisa menutupnya.") }
                return@thread
            }
            val hit = findAppHit(q)
            if (hit == null) {
                ui.post { if (announce) localReply("Nggak nemu app \"$q\".") }
                return@thread
            }
            val current = try { acc?.foregroundPackage() } catch (_: Exception) { null }
            ui.post { setTouchable(false) }
            if (current == hit.pkg) {
                try { acc?.home() } catch (_: Exception) {}
                Thread.sleep(350L)
            }
            var ok = false
            try {
                am.killBackgroundProcesses(hit.pkg)
                ok = true
            } catch (e: Exception) {
                AppLog.add("KONTROL", "tutup app gagal: ${e.javaClass.simpleName} ${e.message}")
            }
            ui.post {
                setTouchable(true)
                if (announce) localReply(if (ok) "${hit.label} udah kututup." else "Gagal menutup ${hit.label}.")
            }
        }
    }

    private fun playSong(query: String, announce: Boolean = true) {
        val q = query.trim().ifBlank { MusicSearch.favorite() }
        if (announce) say("…")
        thread {
            val res = MusicSearch.play(this, q, ps("music_app", "auto"))
            ui.post {
                if (!announce) return@post
                if (res.ok) {
                    localReply("Oke, ${if (q.isBlank()) "aku pilih" else "kucari"} \"${res.message}\" di ${res.app}. Nyetel sekarang.")
                } else {
                    localReply("Nggak bisa muter \"$q\". Install app musik atau buka akses notifikasi dulu.")
                }
            }
        }
    }

    private fun mediaAction(what: String, okMsg: String, failMsg: String) {
        val ok = MediaWatcher.command(what)
        localReply(if (ok) okMsg else failMsg)
        if (ok) ui.postDelayed({ applyRest() }, 400L)
    }

    private fun nowPlaying() {
        val text = MediaWatcher.describe()
        if (text.isBlank()) {
            localReply("Nggak ada lagu yang lagi diputar, atau akses notifikasi belum nyala.")
            return
        }
        localReply("Lagi muter: $text.")
    }

    // Jalankan aksi tersembunyi yang dititipkan otak di ujung balasannya.
    private fun executeAction(action: Actions.Act) {
        AppLog.add("AKSI", "${action.kind}: ${action.arg}")
        when (action.kind) {
            "play" -> playSong(action.arg.ifBlank { MusicSearch.favorite() }, false)
            "search" -> playSong(action.arg.ifBlank { MusicSearch.favorite() }, false)
            "music" -> MediaWatcher.command(action.arg)
            "open" -> if (action.arg.isNotBlank()) openApp(action.arg, false)
            "close" -> closeApp(action.arg, false)
        }
    }

    // Perintah yang diurus aplikasi sendiri tanpa lewat otak. true = sudah ditangani.
    private fun handleCommand(raw: String): Boolean {
        val t = raw.trim().lowercase().trimEnd('!', '.', '?').trim()
        if (t.isEmpty()) return false
        when (t) {
            "kembali", "back", "balik" -> { phone("Oke, mundur.", "Gagal kembali.") { it.back() }; return true }
            "home", "beranda", "ke home", "ke beranda" -> { phone("Udah di beranda.", "Gagal ke beranda.") { it.home() }; return true }
            "recent", "recents", "aplikasi terbaru", "app terbaru", "buka recent" -> { phone("Nih daftar app terbaru.", "Gagal membuka daftar app.") { it.recents() }; return true }
            "notifikasi", "notif", "buka notifikasi" -> { phone("Notifikasinya kebuka.", "Gagal membuka notifikasi.") { it.notifications() }; return true }
            "jeda", "pause", "pause musik", "jeda musik", "jeda lagu", "stop", "stop musik", "stop lagu",
            "berhenti", "berhenti musik", "berhenti lagu", "matikan musik", "matikan lagu" ->
                { mediaAction("pause", "Oke, kujeda musiknya.", "Nggak ada yang bisa kujeda."); return true }
            "lanjut", "resume", "lanjutkan", "lanjut musik", "play", "mainkan lagi", "putar lagi" ->
                { mediaAction("play", "Lanjut. Musiknya jalan lagi.", "Nggak ada musik yang bisa dilanjutin."); return true }
            "lagu berikutnya", "next", "next lagu", "skip", "ganti lagu", "lanjut lagu" ->
                { mediaAction("next", "Oke, lagu berikutnya.", "Nggak bisa skip lagu."); return true }
            "lagu sebelumnya", "previous", "prev", "kembali lagu", "lagu tadi" ->
                { mediaAction("previous", "Balik ke lagu sebelumnya.", "Nggak bisa balik lagu."); return true }
            "lagu apa ini", "lagu apa", "what's playing", "whats playing", "sedang apa", "musik apa", "lagu yang mana" -> { nowPlaying(); return true }
            "tutup", "tutup aplikasi ini", "tutup app ini", "close", "close app" -> { closeApp("", true); return true }
            "favorit", "lagu favorit", "lagu kesukaan", "pilihanmu", "terserah kamu", "favorite",
            "putar musik", "mainkan musik", "putar lagu", "mainkan lagu", "nyalain musik", "lagu dong", "musik dong" ->
                { playSong(MusicSearch.favorite(), true); return true }
        }
        Regex("^(scroll|gulir|geser)\\s*(ke\\s*)?(bawah|turun|down|atas|naik|up)\\b.*$").find(t)?.let {
            val down = it.groupValues[3] in listOf("bawah", "turun", "down")
            phone("Udah kugulir.", "Gagal menggulir.") { acc -> acc.swipe(down) }
            return true
        }
        Regex("^(klik|tekan|pencet|tap|click|ketuk)\\s+(.+)$").find(t)?.let {
            val target = it.groupValues[2].removeSurrounding("\"")
            phone("Kena: $target.", "Nggak nemu \"$target\" di layar.") { acc -> acc.findAndClick(target, 4) }
            return true
        }
        Regex("^(tutup|close|kill|matiin|matikan)\\s+(.+)$").find(t)?.let {
            val target = it.groupValues[2].removeSurrounding("\"")
            if (!target.startsWith("musik") && !target.startsWith("lagu")) closeApp(target, true)
            return true
        }
        Regex("^(nyalain|nyalakan)\\s+(lagu|musik|music)\\b\\s*(.*)$").find(t)?.let {
            var q = it.groupValues[3].removeSurrounding("\"").trim()
            if (q.isEmpty() || q in listOf("favorit", "kesukaan", "pilihanmu", "terserah kamu", "favorite", "favoritmu", "favoritku", "kesukaanku")) q = MusicSearch.favorite()
            playSong(q, true)
            return true
        }
        Regex("^(putar|puter|mainkan|dengerin|dengarkan|play|cari|carikan|search)\\s+(?:(lagu|musik|music|song|video)\\s+)?(.+)$").find(t)?.let {
            var q = it.groupValues[3].removeSurrounding("\"").trim()
            if (q in listOf("favorit", "kesukaan", "pilihanmu", "terserah kamu", "favorite", "favoritmu", "favoritku", "kesukaanku")) q = MusicSearch.favorite()
            if (q.isNotEmpty()) playSong(q, true)
            return true
        }
        Regex("^(buka|bukain|bukakan|open|nyalain|jalankan|launch)\\s+(.+)$").find(t)?.let {
            openApp(it.groupValues[2])
            return true
        }
        return false
    }

    // ---------- Menampilkan jawaban dan suara ----------

    // Teks jawaban muncul di panel dan gelembung.
    private fun showReply(out: String) {
        say(out)
        showBubble(out)
        val act = activity()
        if (po("followup", false) || (act >= 70 && Random.nextFloat() < 0.4f)) {
            ui.removeCallbacks(followRun)
            ui.postDelayed(followRun, 30_000L)
        }
    }

    private fun deliver(out: String, isError: Boolean) {
        val parsed = Actions.extract(out)
        val action = parsed.action
        val text = parsed.text.ifBlank {
            if (action != null) (if (ps("lang", "id") in listOf("id", "auto")) "Oke." else "Okay.") else out
        }
        lastReply = text
        if (isError) {
            showReply(text)
            applyRest()
            return
        }
        val kind = cloudKind()
        if (kind != null) {
            val sync = po("voice_sync", true)
            if (!sync) { showReply(text); applyRest() }
            speakCloud(kind, text, sync)
        } else {
            showReply(text)
            applyRest()
            expression(text)
            speakPhone(text)
        }
        if (action != null) ui.postDelayed({ executeAction(action) }, 500L)
    }

    private fun cloudKind(): String? {
        if (!voiceOn()) return null
        return when (ps("voice_mode", "cloud")) {
            "fish" -> if (ps("fish_key", "").isNotBlank() && ps("fish_voice", "").isNotBlank()) "fish" else null
            "cloud" -> if (ps("eleven_key", "").isNotBlank() && ps("eleven_voice", "").isNotBlank()) "eleven" else null
            else -> null
        }
    }

    // Suara cloud diputar bertahap. Kalau sync, teks baru muncul tepat saat suara mulai, jadi nggak ada jeda.
    private fun speakCloud(kind: String, text: String, sync: Boolean) {
        val pieces = Speech.pieces(cleanForSpeech(text))
        if (pieces.isEmpty()) {
            if (sync) showReply(text)
            applyRest()
            afterReply()
            return
        }
        val speed = speedValue()
        val fetch: (String) -> ByteArray
        if (kind == "fish") {
            val k = ps("fish_key", "").trim()
            val v = ps("fish_voice", "").trim()
            fetch = { t -> FishVoice.piece(k, v, speed, t) }
        } else {
            val k = ps("eleven_key", "").trim()
            val v = ps("eleven_voice", "").trim()
            val m = ps("eleven_model", "").trim()
            val st = Cfg.float(this, "eleven_stab", ElevenVoice.DEFAULT_STABILITY)
            val si = Cfg.float(this, "eleven_sim", ElevenVoice.DEFAULT_SIMILARITY)
            fetch = { t -> ElevenVoice.piece(k, v, m, st, si, speed, t) }
        }
        var shown = false
        fun showOnce() {
            if (!shown) {
                shown = true
                if (sync) showReply(text)
            }
        }
        if (sync) ui.postDelayed({ showOnce() }, 12000L) // jaga-jaga: tampilkan teks walau suara lama
        voice.play(
            pieces, fetch, robotLevel(), glitchLevel(), volumeGain(),
            onFirstAudio = {
                ui.post {
                    showOnce()
                    speaking = true
                    pet?.ambientGlitch = glitchLevel()
                    pet?.talk(lastReply, true)
                    applyRest()
                    expression(lastReply)
                }
            },
            onDone = { ui.post { spoke() } },
            onError = { msg ->
                ui.post {
                    AppLog.add("SUARA", "$kind gagal: $msg")
                    showOnce()
                    say(text + "\n(suara $kind gagal: " + msg.take(80) + ")")
                    applyRest()
                    speakPhone(text)
                }
            }
        )
    }

    // Suara bawaan HP: dipakai kalau tidak ada key cloud, atau kalau suara cloud gagal.
    private fun speakPhone(text: String) {
        val clean = cleanForSpeech(text)
        if (voiceOn() && ttsReady && clean.isNotEmpty()) {
            val loc = Lang.locale(ps("lang", "id")) ?: Locale.getDefault()
            try {
                val r = tts?.setLanguage(loc)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.US)
            } catch (_: Exception) {
            }
            tts?.setPitch(pitchValue())
            tts?.setSpeechRate(speedValue())
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "capri")
        } else {
            pet?.talk(text, false)
            ui.postDelayed({ afterReply() }, (text.length * 70L).coerceIn(800L, 6000L))
        }
    }

    private fun cleanForSpeech(text: String): String = text
        .replace(Regex("[*_`#~]"), "")
        .replace(Regex("[\\p{So}\\p{Sk}]"), "")
        .trim()

    private fun stopAudio() {
        voice.stop()
        tts?.stop()
        speaking = false
        pet?.ambientGlitch = 0
        pet?.stopTalk()
    }

    private fun spoke() {
        speaking = false
        pet?.ambientGlitch = 0
        pet?.stopTalk()
        applyRest()
        afterReply()
    }

    private fun afterReply() {
        if (callMode && panel != null) listenSoon(400)
        else if (panel == null && earsOn()) wakeLoop(700)
    }

    // ---------- Suara masuk (mik) ----------

    private fun wakeOn() = earsOn()

    // Telinga permanen: mik terus mendengarkan, membuka chat kalau nama Capri disebut,
    // dan (kalau diizinkan) menanggapi obrolan lain yang terdengar.
    private fun wakeLoop(ms: Long) {
        ui.postDelayed({ if (earsOn() && panel == null && !listening && !callMode && !speaking && !voice.active) startListening() }, ms)
    }

    // Capri mendengar obrolan yang bukan ditujukan kepadanya. Tanggapi lewat gelembung, bukan panel.
    private fun heard(t: String) {
        val key = ps("key", "").trim()
        if (key.isEmpty() || speaking || voice.active || callMode) return
        val model = ps("model", "").trim().ifEmpty { Brain.DEFAULT_MODEL }
        val nick = ps("nick", "").trim()
        val lang = ps("lang", "id")
        val replies = ps("replies", "short")
        val memory = pi("memory", Cfg.MEMORY)
        val sent = String.format(Locale.US, HEARD_INSTR, t.take(200).replace("\"", "'"))
        thread {
            val out = Brain.ask(key, model, sent, nick, lang, replies, memory, true, null, "")
            if (out.isBlank() || Brain.isError(out)) return@thread
            ui.post {
                if (!sleeping && !hidden && !voice.active && !speaking) {
                    lastUser = t
                    deliver(out, false)
                }
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { AppLog.add("MIK", "siap mendengar") }
        override fun onBeginningOfSpeech() { AppLog.add("MIK", "suara terdeteksi") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            input?.setText(t)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            micMisses = 0
            micRetry = 0
            micWanted = false
            AppLog.add("MIK", "hasil diterima")
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""
            if (panel == null && wakeOn()) {
                if (t.contains("capri", ignoreCase = true)) {
                    val rest = t.replace(Regex("(?i)capri[,.!?]?"), "").trim()
                    togglePanel()
                    if (rest.isNotEmpty()) ask(rest) else say("Ya? Apa, choom?")
                } else if (hearAll() && earsOn() && t.length >= 3 && System.currentTimeMillis() - lastHeard > 7000L) {
                    lastHeard = System.currentTimeMillis()
                    heard(t)
                    wakeLoop(900)
                } else {
                    wakeLoop(500)
                }
                refresh()
                return
            }
            if (t.isNotEmpty()) {
                input?.setText("")
                ask(t)
            } else if (callMode) {
                listenSoon(600)
            }
            refresh()
        }

        override fun onError(error: Int) {
            AppLog.add("MIK", "onError kode $error (${micName(error)}), mode $micTry")
            listening = false
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            val wakeMode = panel == null && wakeOn()

            // Galat yang biasanya sementara: buat ulang pengenal suara lalu coba lagi.
            if ((error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == 11) && micRetry < 2) {
                micRetry++
                recognizer?.destroy()
                recognizer = null
                if (micWanted || wakeMode) ui.postDelayed({ if ((micWanted || wakeMode) && !listening) startListening() }, 500L)
                refresh()
                return
            }
            // Bahasa belum tersedia: coba lagi tanpa menyebut bahasa.
            if ((error == 12 || error == 13) && micRetry < 2) {
                micRetry++
                micTry = 2
                if (micWanted || wakeMode) ui.postDelayed({ if (!listening) startListening() }, 300L)
                refresh()
                return
            }
            if (quiet) {
                micMisses++
                micTry = (micTry + 1) % 3 // ganti cara mengenali di percobaan berikutnya
                if (wakeMode) {
                    wakeLoop(if (micMisses > 3) 3000L else 800L)
                } else if (callMode) {
                    if (micMisses >= 4) {
                        callMode = false
                        micWanted = false
                        micMisses = 0
                        say("Nggak ada suara kedengaran, mode telepon kumatikan. Tekan 📞 lagi kalau mau.")
                    } else {
                        say("Menunggu kamu ngomong…")
                        listenSoon(600)
                    }
                } else {
                    micWanted = false
                    say("Nggak kedengaran jelas. Coba ngomong lebih dekat dan agak keras, lalu tekan 🎤 lagi.")
                }
                refresh()
                return
            }
            if (wakeMode && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && error != SpeechRecognizer.ERROR_NETWORK) {
                wakeLoop(2000L)
                return
            }
            callMode = false
            micWanted = false
            say(
                when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Mik nggak bisa dipakai (error audio). Mungkin lagi dipakai app lain atau diblokir HP di latar belakang."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Pengenal suara butuh internet. Cek koneksi."
                    SpeechRecognizer.ERROR_SERVER -> "Server pengenal suara lagi bermasalah. Coba lagi sebentar."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum diberikan. Buka app Capri Mobile dan izinkan, lalu Hentikan dan Mulai lagi."
                    10 -> "Pengenal suara kebanyakan permintaan. Tunggu sebentar."
                    else -> "Mik error (kode $error, ${micName(error)})."
                }
            )
            refresh()
        }
    }

    private fun listenSoon(ms: Long) {
        ui.postDelayed({ if (callMode && !listening && panel != null) startListening() }, ms)
    }

    private var micWanted = false
    private var micMisses = 0
    private var micRetry = 0
    private var micTry = 0 // 0: bahasa HP, 1: model pencarian, 2: tanpa menyebut bahasa

    private fun micName(code: Int): String = when (code) {
        1 -> "NETWORK_TIMEOUT"
        2 -> "NETWORK"
        3 -> "AUDIO"
        4 -> "SERVER"
        5 -> "CLIENT"
        6 -> "SPEECH_TIMEOUT"
        7 -> "NO_MATCH"
        8 -> "RECOGNIZER_BUSY"
        9 -> "INSUFFICIENT_PERMISSIONS"
        10 -> "TOO_MANY_REQUESTS"
        11 -> "SERVER_DISCONNECTED"
        12 -> "LANGUAGE_NOT_SUPPORTED"
        13 -> "LANGUAGE_UNAVAILABLE"
        else -> "?"
    }

    private fun startListening() {
        if (listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callMode = false
            say("Izin mikrofon belum diberikan. Buka app Capri Mobile dan izinkan.")
            refresh()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            callMode = false
            say("HP ini nggak punya layanan pengenal suara (app Google).")
            refresh()
            return
        }
        micWanted = true
        stopAudio() // supaya suara Capri sendiri nggak ikut terdengar mik
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                if (micTry == 1) RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH else RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            if (micTry != 2) putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        AppLog.add("MIK", "mulai mendengarkan, mode $micTry")
        listening = true
        say("Mendengarkan… ngomong aja")
        refresh()
        rec.startListening(intent)
    }

    private fun stopListening() {
        micWanted = false
        if (listening) recognizer?.cancel()
        listening = false
        refresh()
    }

    override fun onDestroy() {
        stopMiniPlayer()
        if (pet != null) {
            closePanel()
            removeBubble()
            try { wm.removeView(pet!!) } catch (_: Exception) {}
            pet = null
        }
        removeGlitch()
        ui.removeCallbacksAndMessages(null)
        stopAudio()
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }
}
