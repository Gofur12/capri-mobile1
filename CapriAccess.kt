package app.capri.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Kontrol HP untuk Capri: geser layar, ketuk teks, kembali, beranda, dan mencari app dengan menggulir.
// Aktif hanya kalau abang menyalakannya sendiri di Pengaturan > Aksesibilitas.
class CapriAccess : AccessibilityService() {
    companion object {
        @Volatile var instance: CapriAccess? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.add("KONTROL", "aksesibilitas tersambung")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun foregroundPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (_: Exception) {
        null
    }

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // up = true: jari bergerak ke atas, isi layar bergulir ke bawah.
    fun swipe(up: Boolean, span: Float = 0.42f): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val from = dm.heightPixels * (if (up) 0.72f else 0.28f)
        val to = dm.heightPixels * (if (up) 0.72f - span else 0.28f + span)
        return gesture(x, from, x, to, 380L)
    }

    fun tap(x: Float, y: Float): Boolean = gesture(x, y, x, y, 60L)

    // Harus dipanggil dari thread latar, bukan thread utama.
    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path()
        path.moveTo(x1, y1)
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, ms))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return false
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var c = n
        var depth = 0
        while (c != null && depth < 6) {
            if (c.isClickable) return c
            c = c.parent
            depth++
        }
        return null
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val hits = root.findAccessibilityNodeInfosByText(text) ?: return false
        for (h in hits) {
            val c = clickableAncestor(h)
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        for (h in hits) {
            val r = Rect()
            h.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) return tap(r.exactCenterX(), r.exactCenterY())
        }
        return false
    }

    fun findAndClick(text: String, maxScrolls: Int): Boolean {
        for (i in 0..maxScrolls) {
            if (clickText(text)) return true
            if (i == maxScrolls) break
            swipe(true)
            Thread.sleep(700L)
        }
        return false
    }

    // Cadangan kalau app tidak ketemu lewat daftar app: ke beranda, buka laci app, lalu gulir sambil mencari namanya.
    fun openByScrolling(label: String): Boolean {
        home()
        Thread.sleep(700L)
        swipe(true, 0.5f)
        Thread.sleep(800L)
        return findAndClick(label, 8)
    }
}
