package app.capri.mobile

import android.service.notification.NotificationListenerService

// Izin "Akses notifikasi" dipakai hanya untuk membaca sesi musik dari Spotify, YouTube, GoTube,
// YouTube Music, pemutar lokal, dan app lain. Tidak ada isi notifikasi yang dikirim ke mana pun.
class CapriNotifications : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaWatcher.attach(this)
    }

    override fun onListenerDisconnected() {
        MediaWatcher.detach(this)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        MediaWatcher.detach(this)
        super.onDestroy()
    }
}
