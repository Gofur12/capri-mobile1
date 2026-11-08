package app.capri.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val bg = 0xFF0A0E16.toInt()
    private val cardBg = 0xFF111826.toInt()
    private val cy = 0xFF05D9E8.toInt()
    private val pk = 0xFFFF2A6D.toInt()
    private val pu = 0xFF9B6BFF.toInt()
    private val ye = 0xFFF9D342.toInt()
    private val lm = 0xFF8CFF6A.toInt()
    private val tx = 0xFFE8F7FF.toInt()
    private val muted = 0xFF7A8AA3.toInt()
    private val fieldBg = 0xFF0B1120.toInt()

    private lateinit var col: LinearLayout
    private val refreshers = ArrayList<() -> Unit>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val list = ArrayList<String>()
        if (!hasMic()) list.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (list.isNotEmpty()) requestPermissions(list.toTypedArray(), 1)
    }

    // ---------- Komponen tampilan ----------

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Float = 1f) = GradientDrawable().apply {
        setColor(fill)
        setStroke((strokeDp * resources.displayMetrics.density).toInt(), stroke)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun card(title: String, sub: String, accent: Int): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(cardBg, withAlpha(accent, 0x66), 16)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val bar = View(this).apply { setBackgroundColor(accent) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        wrap.addView(bar, LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT))
        wrap.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(TextView(this).apply {
            text = title
            setTextColor(accent)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.1f
        })
        if (sub.isNotEmpty()) {
            body.addView(TextView(this).apply {
                text = sub
                setTextColor(muted)
                textSize = 11f
                setPadding(0, dp(2), 0, dp(4))
            })
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(10), 0, 0)
        col.addView(wrap, lp)
        return body
    }

    private fun caption(p: LinearLayout, t: String) {
        p.addView(TextView(this).apply {
            text = t
            setTextColor(tx)
            textSize = 13f
            setPadding(0, dp(10), 0, dp(2))
        })
    }

    private fun note(p: LinearLayout, t: String) {
        p.addView(TextView(this).apply {
            text = t
            setTextColor(muted)
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun pillBg(color: Int, left: Boolean): GradientDrawable {
        val r = dp(10).toFloat()
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = if (left) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
    }

    // Saklar OFF / ON seperti di PC: OFF merah muda, ON cyan.
    private fun toggle(p: LinearLayout, labelText: String, key: String, def: Boolean) {
        var state = Cfg.on(this, key, def)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(2))
        }
        val name = TextView(this).apply {
            text = labelText
            setTextColor(tx)
            textSize = 13f
        }
        val offV = TextView(this).apply {
            text = "OFF"
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }
        val onV = TextView(this).apply {
            text = "ON"
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }
        fun paint() {
            offV.background = pillBg(if (!state) pk else 0xFF1B2638.toInt(), true)
            offV.setTextColor(if (!state) 0xFFFFFFFF.toInt() else muted)
            onV.background = pillBg(if (state) cy else 0xFF1B2638.toInt(), false)
            onV.setTextColor(if (state) 0xFF0A0E16.toInt() else muted)
        }
        paint()
        offV.setOnClickListener { state = false; Cfg.set(this, key, "off"); paint() }
        onV.setOnClickListener { state = true; Cfg.set(this, key, "on"); paint() }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(offV, LinearLayout.LayoutParams(dp(52), dp(30)))
        row.addView(onV, LinearLayout.LayoutParams(dp(52), dp(30)))
        p.addView(row)
    }

    // Slider untuk semua pengaturan angka. Tersimpan langsung saat digeser.
    private fun slider(
        p: LinearLayout, labelText: String, key: String, lo: Int, hi: Int, def: Int, accent: Int,
        fmt: (Int) -> String = { it.toString() },
        load: (String?) -> Int? = { it?.toIntOrNull() },
        store: (Int) -> String = { it.toString() }
    ) {
        var now = (load(Cfg.prefs(this).getString(key, null)) ?: def).coerceIn(lo, hi)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val name = TextView(this).apply {
            text = labelText
            setTextColor(tx)
            textSize = 13f
        }
        val value = TextView(this).apply {
            text = fmt(now)
            setTextColor(accent)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
        }
        head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(value)
        val bar = SeekBar(this).apply {
            this.max = hi - lo
            progress = now - lo
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(pk)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF2A3A4F.toInt())
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                now = lo + progress
                value.text = fmt(now)
                if (fromUser) Cfg.set(this@MainActivity, key, store(now))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        p.addView(head)
        p.addView(bar)
    }

    // Pilihan dropdown dengan teks terang di latar gelap.
    private fun choice(p: LinearLayout, labelText: String, key: String, items: Array<String>, values: Array<String>, def: String) {
        caption(p, labelText)
        val d = resources.displayMetrics.density
        val adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(tx)
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(tx)
                v.setBackgroundColor(cardBg)
                v.setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val sp = Spinner(this)
        sp.adapter = adapter
        sp.setPopupBackgroundDrawable(ColorDrawable(cardBg))
        sp.backgroundTintList = ColorStateList.valueOf(cy)
        sp.setSelection(values.indexOf(Cfg.str(this, key, def)).coerceAtLeast(0))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Cfg.set(this@MainActivity, key, values[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        p.addView(sp)
    }

    private fun textField(p: LinearLayout, labelText: String, key: String, hintText: String, secret: Boolean, defText: String = "") {
        caption(p, labelText)
        val saved = Cfg.prefs(this).getString(key, null)
        val et = EditText(this).apply {
            hint = hintText
            setText(saved ?: defText)
            setSingleLine()
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(tx)
            setHintTextColor(muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(fieldBg, 0xFF2A3A4F.toInt(), 12)
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { Cfg.set(this@MainActivity, key, s.toString().trim()) }
        })
        p.addView(et)
    }

    private fun button(p: LinearLayout, label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) 0xFF0A0E16.toInt() else if (danger) pk else cy)
            background = rounded(if (primary) cy else 0xFF101820.toInt(), if (danger) pk else cy, 14, 1.5f)
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(8), 0, 0)
        p.addView(b, lp)
    }

    // Baris status izin: titik hijau kalau sudah, merah kalau belum, plus tombol untuk membukanya.
    private fun statusRow(p: LinearLayout, labelText: String, check: () -> Boolean, actionText: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val dot = TextView(this).apply { text = "●"; textSize = 16f }
        val name = TextView(this).apply {
            text = labelText
            setTextColor(tx)
            textSize = 13f
            setPadding(dp(8), 0, 0, 0)
        }
        val act = Button(this).apply {
            text = actionText
            isAllCaps = false
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(cy)
            background = rounded(0xFF101820.toInt(), cy, 12, 1f)
            setOnClickListener { action() }
        }
        row.addView(dot)
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(act)
        p.addView(row)
        refreshers.add { dot.setTextColor(if (check()) lm else pk) }
    }

    // ---------- Aksi ----------

    private fun startCapri() {
        AppLog.add("APP", "Mulai Capri ditekan (mik=${hasMic()}, overlay=${Settings.canDrawOverlays(this)})")
        if (!hasMic()) {
            askPermissions()
            Toast.makeText(this, "Izinkan mikrofon dulu, lalu tekan Mulai lagi.", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan tampil di atas app lain dulu.", Toast.LENGTH_LONG).show()
            return
        }
        stopService(Intent(this, CapriService::class.java))
        startForegroundService(Intent(this, CapriService::class.java))
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42) return
        AppLog.add("LAYAR", "hasil izin layar: resultCode=$resultCode, data=${data != null}")
        if (resultCode == RESULT_OK && data != null) {
            try {
                startForegroundService(
                    Intent(this, CapriService::class.java)
                        .setAction("screen_start")
                        .putExtra("code", resultCode)
                        .putExtra("data", data)
                )
                Toast.makeText(this, "Capri sekarang bisa lihat layar.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                AppLog.add("LAYAR", "gagal kirim ke layanan: ${e.javaClass.name}: ${e.message}")
                Toast.makeText(this, "Gagal mulai lihat layar: ${e.javaClass.simpleName} ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Izin lihat layar dibatalkan.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        for (r in refreshers) r()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPermissions()
        val pad = dp(16)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(18), pad, dp(28))
        }

        // ----- Kepala -----
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val icon = ImageView(this).apply {
            try { setImageDrawable(applicationInfo.loadIcon(packageManager)) } catch (_: Exception) {}
            outlineProvider = ViewOutlineProvider.BACKGROUND
            background = rounded(cardBg, cy, 16, 1.5f)
            clipToOutline = true
        }
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val title = TextView(this).apply {
            text = "CAPRI MOBILE"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(cy)
            post {
                paint.shader = LinearGradient(0f, 0f, width.toFloat().coerceAtLeast(1f), 0f, cy, pk, Shader.TileMode.CLAMP)
                invalidate()
            }
        }
        val subtitle = TextView(this).apply {
            text = "// build ${AppLog.BUILD} · Android ${Build.VERSION.RELEASE}"
            setTextColor(muted)
            textSize = 11f
        }
        titleBox.addView(title)
        titleBox.addView(subtitle)
        hero.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)))
        hero.addView(titleBox)
        col.addView(hero)

        // ----- Kontrol utama -----
        val main = card("KONTROL UTAMA", "Mulai, hentikan, dan cek log", cy)
        button(main, "▶  Mulai Capri", primary = true) { startCapri() }
        button(main, "■  Hentikan Capri") { stopService(Intent(this, CapriService::class.java)) }
        button(main, "Lihat log (untuk cek error)") { startActivity(Intent(this, LogActivity::class.java)) }

        // ----- Izin dan kontrol HP -----
        val perm = card("IZIN & KONTROL HP", "Titik hijau = sudah aktif", lm)
        statusRow(perm, "Tampil di atas app lain", { Settings.canDrawOverlays(this) }, "Atur") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        statusRow(perm, "Mikrofon", { hasMic() }, "Izinkan") { askPermissions() }
        statusRow(perm, "Kontrol HP (Aksesibilitas)", { CapriAccess.instance != null }, "Buka") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        note(perm, "Kontrol HP: Capri bisa buka app, gulir layar, ketuk teks, kembali, dan beranda. Nyalakan \"Capri Mobile\" di Aksesibilitas. Di Android 13, kalau ditolak: Info app > menu ⋮ > Izinkan setelan terbatas.")
        toggle(perm, "Izinkan Capri mengontrol HP", "control", true)
        note(perm, "Perintah di chat: \"buka Spotify\", \"scroll ke bawah\", \"klik Kirim\", \"kembali\", \"home\", \"recent\", \"notifikasi\".")

        // ----- Otak -----
        val brain = card("OTAK", "Model dan gaya bicara", cy)
        textField(brain, "API key NVIDIA (gratis di build.nvidia.com)", "key", "nvapi-...", true)
        textField(brain, "Model otak", "model", Brain.DEFAULT_MODEL, false, Brain.DEFAULT_MODEL)
        choice(brain, "Bahasa Capri", "lang", Lang.labels(), Lang.codes(), "id")
        slider(
            brain, "Panjang jawaban", "replies", 0, 2, 0, cy,
            fmt = { listOf("Pendek", "Normal", "Panjang")[it] },
            load = { s -> when (s) { "short" -> 0; "normal" -> 1; "long" -> 2; else -> null } },
            store = { listOf("short", "normal", "long")[it] }
        )
        slider(brain, "Ingatan chat", "memory", 4, 60, Cfg.MEMORY, cy, fmt = { "$it pesan" })
        textField(brain, "Capri manggil kamu apa?", "nick", "mis. brezze (boleh kosong)", false)

        // ----- Keaktifan -----
        val act = card("KEAKTIFAN", "Pasif = diam kecuali diajak ngobrol. Makin tinggi, makin sering Capri ngomong sendiri.", pk)
        slider(
            act, "Tingkat keaktifan", "activity", 0, 100, Cfg.ACTIVITY, pk,
            fmt = {
                val label = when {
                    it == 0 -> "Pasif"
                    it < 35 -> "Santai"
                    it < 70 -> "Aktif"
                    else -> "Super aktif"
                }
                "$label ($it)"
            }
        )
        toggle(act, "Tanya kabar kalau lama nggak ngobrol", "checkin", false)
        toggle(act, "Komentari layar sendiri (perlu Lihat layar)", "screencomment", true)
        toggle(act, "Lanjut ngomong setelah jawaban", "followup", false)
        toggle(act, "Chaos: kadang glitch atau joget sendiri", "chaos", false)
        toggle(act, "Joget kalau ada musik diputar", "music", true)
        toggle(act, "Not musik dan bunyi note block", "music_notes", true)
        toggle(act, "Telinga permanen (mik nyala terus, panggil \"Capri\")", "wake", true)
        toggle(act, "Dengar semua obrolan, bukan cuma namaku", "heareverything", false)
        note(act, "Telinga permanen membuat Capri selalu siap dipanggil. \"Dengar semua\" membuatnya ikut menanggapi obrolan lain yang terdengar, jadi lebih privat.")

        // ----- Suara -----
        val vc = card("SUARA", "Cloud memutar kalimat pertama lebih dulu supaya jedanya pendek", pu)
        choice(vc, "Sumber suara", "voice_mode", arrayOf("Mati (teks saja)", "Suara HP", "ElevenLabs", "Fish Audio"), arrayOf("off", "phone", "cloud", "fish"), "cloud")
        toggle(vc, "Teks muncul bareng suara", "voice_sync", true)
        textField(vc, "Fish Audio API key", "fish_key", "Fish Audio API key", true)
        textField(vc, "Voice ID Fish Audio", "fish_voice", "Voice ID", false)
        textField(vc, "ElevenLabs API key", "eleven_key", "sk_...", true)
        textField(vc, "Voice ID ElevenLabs", "eleven_voice", "Voice ID", false)
        choice(vc, "Model ElevenLabs", "eleven_model", arrayOf("Default (eleven_v4_turbo)", "Flash v2.5 (lebih cepat)", "Multilingual v2"), arrayOf("eleven_v4_turbo", "eleven_flash_v2_5", "eleven_multilingual_v2"), "eleven_v4_turbo")
        val pct = { s: String? -> s?.toFloatOrNull()?.let { (it * 100).roundToInt() } }
        val frac = { v: Int -> String.format(Locale.US, "%.2f", v / 100f) }
        slider(vc, "Stability (kecil = ekspresif)", "eleven_stab", 0, 100, Cfg.STAB, pu, fmt = { String.format(Locale.US, "%.2f", it / 100f) }, load = pct, store = frac)
        slider(vc, "Similarity", "eleven_sim", 0, 100, Cfg.SIM, pu, fmt = { String.format(Locale.US, "%.2f", it / 100f) }, load = pct, store = frac)
        slider(vc, "Kecepatan bicara", "eleven_speed", 70, 120, Cfg.SPEED, pu, fmt = { String.format(Locale.US, "%.2fx", it / 100f) }, load = pct, store = frac)
        slider(vc, "Volume", "volume", 0, 150, Cfg.VOLUME, pu, fmt = { "$it%" })
        slider(vc, "Pitch (suara HP)", "pitch_pct", 50, 160, Cfg.PITCH, pu, fmt = { "$it%" })

        // ----- Efek suara -----
        val fx = card("EFEK SUARA", "Dikit-dikit aja biar nggak datar", ye)
        slider(fx, "Robot", "robot", 0, 100, Cfg.ROBOT, ye, fmt = { if (it == 0) "Mati" else "$it%" })
        slider(fx, "Glitch suara", "glitch", 0, 100, Cfg.GLITCH, ye, fmt = { if (it == 0) "Mati" else "$it%" })
        slider(fx, "Volume bunyi efek", "sounds", 0, 100, Cfg.SOUNDS, ye, fmt = { "$it%" })
        slider(fx, "Glitch layar (area yang kena)", "glitch_area", 0, 100, 50, ye, fmt = { if (it == 0) "Mati" else "$it% layar" })
        toggle(fx, "Bunyi saat mengetik", "typingsound", false)
        toggle(fx, "Efek spawn dan suara ikonik saat Capri muncul", "spawn", Cfg.SPAWN)
        button(fx, "Tes efek spawn") {
            startService(Intent(this, CapriService::class.java).setAction("spawn"))
        }

        // ----- Tampilan dan gerak -----
        val look = card("TAMPILAN & GERAK", "Ukuran, cara duduk, dan tingkah Capri", lm)
        slider(look, "Ukuran Capri (Mulai ulang untuk menerapkan)", "size_pct", 50, 150, Cfg.SIZE, lm, fmt = { "$it%" })
        slider(look, "Tidur kalau nggak diajak ngobrol", "sleep", 0, 30, 0, lm, fmt = { if (it == 0) "Mati" else "$it menit" })
        slider(look, "Gelembung bicara tampil", "bubble_sec", 3, 40, Cfg.BUBBLE_SEC, lm, fmt = { "$it detik" })
        toggle(look, "Hologram", "hologram", false)
        toggle(look, "Elus kepala (ketuk bagian atas Capri)", "headpats", true)
        toggle(look, "Jalan-jalan sendiri di layar", "wander", false)
        toggle(look, "Duduk di tepi bawah layar", "perch", false)
        toggle(look, "Mode mini (chat cuma kirim dan tutup)", "mini", false)
        note(look, "Saat chat terbuka, Capri duduk di atas panel. Seret Capri untuk mengatur tempat duduknya, dan seret bagian atas panel untuk memindahkan panelnya.")
        button(look, "Reset posisi duduk dan panel") {
            for (k in listOf("perch_dx", "perch_dy", "panel_x", "panel_y")) Cfg.prefs(this).edit().remove(k).apply()
            Toast.makeText(this, "Posisi direset.", Toast.LENGTH_SHORT).show()
        }

        // ----- Lihat layar -----
        val scr = card("LIHAT LAYAR", "Gambar layar terbaru hanya dikirim ke otak saat kamu kirim pesan", cy)
        toggle(scr, "Capri lihat layar kamu", "watch", false)
        toggle(scr, "Ingat apa yang dilihat", "remember", false)
        slider(scr, "Perbarui lihat layar", "watch_every", 5, 60, Cfg.WATCH_EVERY, cy, fmt = { "$it detik" })
        slider(scr, "Jumlah catatan layar yang diingat", "watchmem", 10, 96, Cfg.WATCHMEM, cy, fmt = { "$it catatan" })
        slider(scr, "Lama tangkapan layar disimpan", "watchsave", 5, 60, Cfg.WATCHSAVE, cy, fmt = { "$it detik" })
        button(scr, "Mulai lihat layar (Android minta izin)", primary = true) {
            val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), 42)
        }
        button(scr, "Stop lihat layar") {
            startService(Intent(this, CapriService::class.java).setAction("screen_stop"))
        }

        // ----- Musik -----
        val mus = card("MUSIK", "Cari dan putar lagu di app musik apa pun", pu)
        toggle(mus, "Pemutar mini di atas app lain", "miniplayer", true)
        choice(mus, "App musik utama", "music_app", MusicSearch.appChoiceLabels(this), MusicSearch.appChoiceValues(this), "auto")
        statusRow(mus, "Akses notifikasi (baca lagu yang diputar)", { MediaWatcher.hasAccess(this) }, "Atur") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        note(mus, "Pemutar mini hanya muncul kalau akses notifikasi aktif. Tanpa akses, Capri tetap bisa membuka app dan mencari lagu.")
        note(mus, "Perintah: \"putar lagu ...\", \"cari lagu ...\", \"jeda\", \"lanjut\", \"lagu berikutnya\", \"lagu sebelumnya\", \"lagu apa ini\".")

        // ----- Bantuan -----
        val help = card("BANTUAN", "", muted)
        button(help, "Hapus ingatan chat", danger = true) {
            Brain.clear(this)
            Toast.makeText(this, "Ingatan Capri dihapus.", Toast.LENGTH_SHORT).show()
        }
        button(help, "Bantuan (buka panduan)") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lachydotmcg/capri-desktop#readme")))
        }
        button(help, "Cek update") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lachydotmcg/capri-desktop/releases")))
        }
        button(help, "Dukung proyek Capri") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lachydotmcg/capri-desktop")))
        }

        col.addView(TextView(this).apply {
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(18), 0, 0)
            text = "Karakter, sprite, dan persona berasal dari proyek Capri (github.com/lachydotmcg/capri-desktop), " +
                "© 2026 Lachy, lisensi PolyForm Noncommercial 1.0.0. Seni dan animasi oleh artisnya Capri. " +
                "Proyek fan-made nonkomersial, bukan produk resmi CD PROJEKT RED."
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(col)
        })
        for (r in refreshers) r()
    }
}
