package app.capri.mobile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// Halaman log: lihat, salin, bagikan, atau hapus catatan kejadian app.
class LogActivity : Activity() {
    private lateinit var tv: TextView
    private lateinit var sv: ScrollView

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(0xFF05D9E8.toInt())
        minWidth = 0
        minimumWidth = 0
        background = GradientDrawable().apply {
            setColor(0xFF101820.toInt())
            setStroke(dp(1.5f), 0xFF05D9E8.toInt())
            cornerRadius = dp(14f).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        tv.text = AppLog.read().ifBlank { "(log masih kosong)" }
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Log Capri"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1117.toInt())
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        }
        val head = TextView(this).apply {
            text = "LOG CAPRI  (build ${AppLog.BUILD})"
            setTextColor(0xFFFF2A6D.toInt())
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8f))
        }
        val row = LinearLayout(this)
        val buttons = listOf(
            btn("Segarkan") { refresh() },
            btn("Salin") {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("log capri", AppLog.read()))
                Toast.makeText(this, "Log disalin.", Toast.LENGTH_SHORT).show()
            },
            btn("Bagikan") {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AppLog.read())
                startActivity(Intent.createChooser(send, "Bagikan log"))
            },
            btn("Hapus") { AppLog.clear(); refresh() }
        )
        for (b in buttons) row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        tv = TextView(this).apply {
            setTextColor(0xFFE8F7FF.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, dp(8f), 0, 0)
        }
        sv = ScrollView(this).apply { addView(tv) }

        root.addView(head)
        root.addView(row)
        root.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
    }
}
