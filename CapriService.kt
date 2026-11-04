package app.capri.mobile

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.text.TextWatcher
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

// Capri melayang di atas app lain. Ketuk = buka/tutup chat, tahan lalu geser = pindah.
// Suara: Capri bicara lewat Text-to-Speech Android dan mendengar lewat pengenal suara Google.
class CapriService : Service() {
    private lateinit var wm: WindowManager
    private var pet: SpriteView? = null
    private var panel: LinearLayout? = null
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
    private var player: android.media.MediaPlayer? = null
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs(): SharedPreferences = getSharedPreferences("capri", Context.MODE_PRIVATE)
    private fun robotLevel() = prefs().getString("robot", "0")?.toIntOrNull() ?: 0
    private fun glitchLevel() = prefs().getString("glitch", "0")?.toIntOrNull() ?: 0
    private fun wanderOn() = prefs().getString("wander", "off") == "on"
    private fun headPatsOn() = prefs().getString("headpats", "on") == "on"
    private fun voiceOn() = prefs().getBoolean("voice", true) && prefs().getString("voice_mode", "cloud") != "off"
    private fun volumeLevel(): Float = ((prefs().getString("volume", "100")?.toIntOrNull() ?: 100) / 100f).coerceIn(0f, 1f)
    private fun speedValue(): Float = (prefs().getString("eleven_speed", "")?.toFloatOrNull() ?: 1.0f).coerceIn(0.7f, 1.2f)
    private fun pitchValue(): Float = when (prefs().getString("pitch", "normal")) {
        "low" -> 0.85f
        "high" -> 1.3f
        else -> 1.0f
    }
    private fun petWidthDp(): Int = when (prefs().getString("size", "medium")) {
        "small" -> 70
        "large" -> 120
        else -> 90
    }

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
                    override fun onStart(id: String?) { ui.post { pet?.talk(lastReply, true) } }
                    override fun onDone(id: String?) { ui.post { spoke() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { ui.post { spoke() } }
                })
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "screen_start") ScreenWatch.wanted = true
        startAsForeground()
        val key = (prefs().getString("size", "medium") ?: "medium") + "|" + (prefs().getString("perch", "off") ?: "off")
        if (pet != null && petKey != key) {
            try { wm.removeView(pet!!) } catch (_: Exception) {}
            pet = null
            removeBubble()
        }
        if (pet == null) {
            petKey = key
            showPet()
        }
        when (i?.action) {
            "hide" -> toggleHide()
            "bring" -> bringHere()
            "screen_start" -> {
                val code = i?.getIntExtra("code", 0) ?: 0
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    i?.getParcelableExtra("data", Intent::class.java)
                else i?.getParcelableExtra("data")
                if (data != null) {
                    val every = (prefs().getString("watch_every", "10")?.toLongOrNull() ?: 10L) * 1000L
                    try {
                        ScreenWatch.start(this, code, data, every)
                    } catch (e: Exception) {
                        ScreenWatch.wanted = false
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
            .setContentText("Ketuk Capri di layar buat ngobrol")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(action("hide", if (hidden) "Tampilkan" else "Sembunyikan"))
            .addAction(action("bring", "Bawa ke sini"))
            .build()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (ScreenWatch.wanted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(1, n, type)
            } else if (Build.VERSION.SDK_INT >= 30 && mic) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private var petLp: WindowManager.LayoutParams? = null
    private var petKey = ""
    private var lastTalk = System.currentTimeMillis()
    private var lastProactive = 0L
    private var lastCheckin = 0L
    private var hidden = false

    private val proactiveRun = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val p = prefs()
            if (panel == null && pet != null) {
                val gap = when (p.getString("popins", "off")) { "rare" -> 45L; "often" -> 15L; else -> 0L }
                if (gap > 0 && now - lastProactive > gap * 60_000L) {
                    lastProactive = now
                    proactive(POP_INSTR)
                }
                if (p.getString("checkin", "off") == "on" && now - lastTalk > 2 * 3_600_000L && now - lastCheckin > 2 * 3_600_000L) {
                    lastCheckin = now
                    lastTalk = now
                    proactive(CHECK_INSTR)
                }
                val r = kotlin.random.Random.nextFloat()
                if (p.getString("chaos", "off") == "on" && r < 0.05f) pet?.play(if (r < 0.025f) "glitch" else "dance", loop = false)
                rememberScreen(now)
            }
            ui.postDelayed(this, 60_000L)
        }
    }
    private var dragActive = false
    private var longFired = false

    private val longPressRun = Runnable {
        longFired = true
        bringHere()
    }
    private val screenLines = ArrayList<String>()
    private var lastRemember = 0L
    private var bubble: TextView? = null
    private val bubbleHide = Runnable { removeBubble() }
    private val followRun = Runnable { proactive(FOLLOW_INSTR) }

    // "buka Spotify" / "open Spotify": Capri membuka app yang namanya cocok di HP ini.
    private fun handleOpenApp(text: String): Boolean {
        val lower = text.trim().lowercase()
        val prefix = listOf("buka ", "bukain ", "open ", "nyalain ").firstOrNull { lower.startsWith(it) } ?: return false
        val query = lower.removePrefix(prefix).trim().removeSuffix(" dong").removeSuffix(" ya").trim()
        if (query.isEmpty()) return false
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == query }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(query) }
        val msg = if (hit == null) {
            "Nggak nemu app \"$query\" di HP ini."
        } else {
            val name = hit.loadLabel(pm).toString()
            val launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                "Oke, aku bukain $name."
            } else {
                "App $name nggak bisa dibuka dari sini."
            }
        }
        lastReply = msg
        say(msg)
        showBubble(msg)
        speak(msg)
        return true
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    private fun showBubble(msg: String) {
        val base = petLp ?: return
        if (pet == null) return
        removeBubble()
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            maxWidth = dp(230)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2101820"))
                setStroke(dp(2), Color.parseColor("#05D9E8"))
                cornerRadius = dp(8).toFloat()
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
        val ms = when (prefs().getString("bubble", "normal")) {
            "quick" -> 6000L
            "long" -> 25000L
            else -> 12000L
        }
        ui.removeCallbacks(bubbleHide)
        ui.postDelayed(bubbleHide, ms)
    }

    // Tiap menit, kalau layar dibagikan: Capri catat apa yang sedang dilihat, untuk ingatan obrolan.
    private fun rememberScreen(now: Long) {
        val p = prefs()
        if (p.getString("remember", "off") != "on" || p.getString("watch", "off") != "on") return
        if (now - lastRemember < 60_000L) return
        val img = ScreenWatch.freshB64(90_000L) ?: return
        lastRemember = now
        val key = (p.getString("key", "") ?: "").trim()
        if (key.isEmpty()) return
        val model = (p.getString("model", "") ?: "").trim().ifEmpty { Brain.DEFAULT_MODEL }
        val memory = p.getString("memory", "20")?.toIntOrNull() ?: 20
        val max = p.getString("watchmem", "20")?.toIntOrNull() ?: 20
        thread {
            val line = Brain.ask(key, model, REMEMBER_INSTR, "", "en", "short", memory, true, img, "")
            if (line.isBlank() || Brain.isError(line)) return@thread
            synchronized(screenLines) {
                screenLines.add(line.take(160))
                while (screenLines.size > max) screenLines.removeAt(0)
            }
        }
    }

    private var sleeping = false
    private val sleepRun = Runnable {
        sleeping = true
        pet?.play("laydown_sleep")
    }
    private val wanderRun = object : Runnable {
        override fun run() {
            if (wanderOn() && pet != null && panel == null && !dragActive && !sleeping) wanderTo(randomSpot())
            ui.postDelayed(this, 25000L)
        }
    }

    private fun proactive(instruction: String) {
        val p = prefs()
        val key = (p.getString("key", "") ?: "").trim()
        if (key.isEmpty()) return
        val model = (p.getString("model", "") ?: "").trim().ifEmpty { Brain.DEFAULT_MODEL }
        val nick = (p.getString("nick", "") ?: "").trim()
        val lang = p.getString("lang", "id") ?: "id"
        val replies = p.getString("replies", "short") ?: "short"
        val memory = p.getString("memory", "20")?.toIntOrNull() ?: 20
        thread {
            val out = Brain.ask(key, model, instruction, nick, lang, replies, memory, true)
            if (out.isBlank() || Brain.isError(out)) return@thread
            ui.post {
                lastReply = out
                if (panel != null) say(out)
                pet?.play("grin", loop = false)
                speak(out)
            }
        }
    }

    private fun playTone() {
        val vol = (prefs().getString("sounds", "100")?.toIntOrNull() ?: 100).coerceIn(0, 100)
        if (vol == 0) return
        try {
            val g = ToneGenerator(AudioManager.STREAM_NOTIFICATION, vol)
            g.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
            ui.postDelayed({ g.release() }, 200L)
        } catch (_: Exception) {}
    }

    private fun toggleHide() {
        hidden = !hidden
        pet?.visibility = if (hidden) View.GONE else View.VISIBLE
        startAsForeground()
    }

    private fun wakeOn() = prefs().getString("wake", "off") == "on"

    // Mode "Panggil Capri": mic terus mendengarkan dan membuka chat kalau nama Capri disebut.
    private fun wakeLoop(ms: Long) {
        ui.postDelayed({ if (wakeOn() && panel == null && !listening && !callMode) startListening() }, ms)
    }

    private fun wake() {
        if (sleeping) { sleeping = false; pet?.play("wake", loop = false) }
        ui.removeCallbacks(sleepRun)
        val mins = prefs().getString("sleep", "0")?.toIntOrNull() ?: 0
        if (mins > 0) ui.postDelayed(sleepRun, mins * 60_000L)
    }

    private fun randomSpot(): Pair<Int, Int> {
        val lp = petLp ?: return Pair(0, 0)
        val maxX = (resources.displayMetrics.widthPixels - lp.width).coerceAtLeast(0)
        val minY = dp(60)
        val maxY = (resources.displayMetrics.heightPixels - lp.height - dp(120)).coerceAtLeast(minY + 1)
        return Pair(
            kotlin.random.Random.nextInt(maxX + 1),
            minY + kotlin.random.Random.nextInt(maxY - minY)
        )
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
                override fun onAnimationEnd(a: Animator) { pet?.play("idle") }
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

    private fun showPet() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = SpriteView(this)
        v.setHologram(prefs().getString("hologram", "off") == "on")
        val lp = WindowManager.LayoutParams(
            dp(petWidthDp()), dp(petWidthDp() * 8 / 5),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
                        dragActive = false
                        v.play("idle")
                    } else if (!longFired) {
                        if (headPatsOn() && e.y < v.height * 0.35f) {
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
            if (prefs().getString("perch", "off") == "on") {
                bringHere()
                v.play("perch")
            } else {
                v.play("arrive", loop = false)
            }
            wake()
            ui.postDelayed(wanderRun, 25000L)
            ui.postDelayed(proactiveRun, 60000L)
            if (wakeOn()) wakeLoop(1000)
        } catch (e: Exception) {
            stopSelf() // izin overlay belum diberikan
        }
    }

    private fun smallButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun togglePanel() {
        if (panel != null) { closePanel(); return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2101820"))
                setStroke(dp(2), Color.parseColor("#05D9E8"))
                cornerRadius = dp(10).toFloat()
            }
        }
        val r = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 8
            text = "CAPRI: Yo. Ketik atau tekan mik dan ngomong."
        }
        val inp = EditText(this).apply {
            hint = "ketik ke Capri…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        inp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (prefs().getString("typingsound", "off") == "on") playTone()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val send = smallButton("Kirim")
        val mic = smallButton("🎤")
        val call = smallButton("📞")
        val voice = smallButton("🔊")
        val close = smallButton("Tutup")
        if (prefs().getString("mini", "off") == "on") {
            mic.visibility = View.GONE
            call.visibility = View.GONE
            voice.visibility = View.GONE
        }
        val row = LinearLayout(this)
        for (b in listOf(send, mic, call, voice, close)) {
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
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
        voice.setOnClickListener {
            prefs().edit().putBoolean("voice", !voiceOn()).apply()
            if (!voiceOn()) { tts?.stop(); pet?.stopTalk() }
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
        lp.y = dp(24)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        wm.addView(box, lp)
        panel = box
        reply = r
        input = inp
        micBtn = mic
        callBtn = call
        voiceBtn = voice
        refresh()
    }

    private fun closePanel() {
        callMode = false
        stopListening()
        panel?.let { wm.removeView(it) }
        panel = null
        reply = null
        input = null
        micBtn = null
        callBtn = null
        voiceBtn = null
        if (wakeOn()) wakeLoop(800)
    }

    private fun refresh() {
        micBtn?.text = if (listening) "⏹" else "🎤"
        callBtn?.text = if (callMode) "📴" else "📞"
        voiceBtn?.text = if (voiceOn()) "🔊" else "🔇"
    }

    private fun say(msg: String) { reply?.text = "CAPRI: $msg" }

    // ---------- Kirim ke otak ----------

    private fun sendTyped() {
        val inp = input ?: return
        val text = inp.text.toString().trim()
        if (text.isEmpty()) return
        inp.setText("")
        ask(text)
    }

    private fun ask(text: String) {
        if (handleOpenApp(text)) return
        val p = prefs()
        val key = (p.getString("key", "") ?: "").trim()
        val model = (p.getString("model", "") ?: "").trim().ifEmpty { Brain.DEFAULT_MODEL }
        val nick = (p.getString("nick", "") ?: "").trim()
        if (key.isEmpty()) {
            say("Belum ada API key. Buka app Capri Mobile dan isi key nvapi- dulu.")
            return
        }
        say("…")
        ui.removeCallbacks(followRun)
        lastTalk = System.currentTimeMillis()
        wake()
        stopAudio()
        pet?.stopTalk()
        pet?.play("glitch")
        thread {
            val lang = p.getString("lang", "id") ?: "id"
            val replies = p.getString("replies", "short") ?: "short"
            val memory = p.getString("memory", "20")?.toIntOrNull() ?: 20
            val img = if (prefs().getString("watch", "off") == "on") ScreenWatch.freshB64(90_000L) else null
            val ctx = synchronized(screenLines) { screenLines.joinToString("; ") }
            val out = Brain.ask(key, model, text, nick, lang, replies, memory, false, img, ctx)
            ui.post {
                lastReply = out
                say(out)
                showBubble(out)
                if (prefs().getString("followup", "off") == "on") {
                    ui.removeCallbacks(followRun)
                    ui.postDelayed(followRun, 30_000L)
                }
                pet?.play("idle")
                speak(out)
            }
        }
    }

    // ---------- Suara keluar (TTS) ----------

    private fun speak(text: String) {
        val p = prefs()
        val eKey = (p.getString("eleven_key", "") ?: "").trim()
        val eVoice = (p.getString("eleven_voice", "") ?: "").trim()
        val mode = p.getString("voice_mode", "cloud")
        val fKey = (p.getString("fish_key", "") ?: "").trim()
        val fVoice = (p.getString("fish_voice", "") ?: "").trim()
        if (voiceOn() && mode == "fish" && fKey.isNotEmpty() && fVoice.isNotEmpty()) {
            val clean2 = cleanForSpeech(text)
            if (clean2.isEmpty()) { afterReply(); return }
            val spd2 = speedValue()
            thread {
                try {
                    val wav = Effects.process(FishVoice.speak(fKey, fVoice, spd2, clean2), robotLevel(), glitchLevel())
                    ui.post { playWav(wav) }
                } catch (e: Exception) {
                    ui.post {
                        say(lastReply + "\n(suara Fish gagal: ${e.message?.take(80)})")
                        speakFallback(text)
                    }
                }
            }
        } else if (voiceOn() && eKey.isNotEmpty() && eVoice.isNotEmpty() && mode == "cloud") {
            val eModel = (p.getString("eleven_model", "") ?: "").trim()
            val stab = p.getString("eleven_stab", "")?.toFloatOrNull() ?: ElevenVoice.DEFAULT_STABILITY
            val sim = p.getString("eleven_sim", "")?.toFloatOrNull() ?: ElevenVoice.DEFAULT_SIMILARITY
            val spd = p.getString("eleven_speed", "")?.toFloatOrNull() ?: ElevenVoice.DEFAULT_SPEED
            val clean = cleanForSpeech(text)
            if (clean.isEmpty()) { afterReply(); return }
            thread {
                try {
                    val wav = Effects.process(ElevenVoice.speak(eKey, eVoice, eModel, stab, sim, spd.coerceIn(0.7f, 1.2f), clean), robotLevel(), glitchLevel())
                    ui.post { playWav(wav) }
                } catch (e: Exception) {
                    ui.post {
                        say(lastReply + "\n(suara ElevenLabs gagal: ${e.message?.take(80)})")
                        speakFallback(text)
                    }
                }
            }
        } else {
            speakFallback(text)
        }
    }

    // Suara bawaan HP, dipakai kalau belum ada key/voice ID ElevenLabs atau kalau ElevenLabs gagal.
    private fun speakFallback(text: String) {
        val clean = cleanForSpeech(text)
        if (voiceOn() && ttsReady && clean.isNotEmpty()) {
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

    private fun playWav(bytes: ByteArray) {
        stopAudio()
        try {
            val f = java.io.File(cacheDir, "capri-voice.wav")
            f.writeBytes(bytes)
            val mp = android.media.MediaPlayer()
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnPreparedListener {
                pet?.talk(lastReply, true)
                it.setVolume(volumeLevel(), volumeLevel())
                it.start()
            }
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                spoke()
            }
            mp.setOnErrorListener { m, _, _ ->
                m.release()
                if (player === m) player = null
                spoke()
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            spoke()
        }
    }

    private fun stopAudio() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }

    private fun spoke() {
        pet?.stopTalk()
        afterReply()
    }

    private fun afterReply() {
        if (callMode && panel != null) listenSoon(400)
    }

    // ---------- Suara masuk (mik) ----------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
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
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""
            if (panel == null && wakeOn()) {
                if (t.contains("capri", ignoreCase = true)) {
                    val rest = t.replace(Regex("(?i)capri[,.!?]?"), "").trim()
                    togglePanel()
                    if (rest.isNotEmpty()) ask(rest) else say("Ya? Apa, choom?")
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
            listening = false
            if (panel == null && wakeOn() && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && error != SpeechRecognizer.ERROR_NETWORK) {
                wakeLoop(800)
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (callMode) listenSoon(600)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    callMode = false
                    say("Izin mikrofon belum diberikan. Buka app Capri Mobile dan izinkan, lalu Hentikan dan Mulai lagi.")
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    callMode = false
                    say("Pengenal suara butuh internet. Cek koneksi.")
                }
                else -> {
                    callMode = false
                    say("Mik error (kode $error).")
                }
            }
            refresh()
        }
    }

    private fun listenSoon(ms: Long) {
        ui.postDelayed({ if (callMode && !listening && panel != null) startListening() }, ms)
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
        tts?.stop() // supaya suara Capri sendiri nggak ikut terdengar mik
        pet?.stopTalk()
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        listening = true
        say("Mendengarkan…")
        refresh()
        rec.startListening(intent)
    }

    private fun stopListening() {
        if (listening) recognizer?.cancel()
        listening = false
        refresh()
    }

    override fun onDestroy() {
        if (pet != null) {
            closePanel()
            wm.removeView(pet)
            pet = null
        }
        ui.removeCallbacksAndMessages(null)
        stopAudio()
        tts?.stop()
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }
}
