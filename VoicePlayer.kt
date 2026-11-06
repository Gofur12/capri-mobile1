package app.capri.mobile

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Memutar suara cloud bertahap: potongan pertama diputar begitu jadi, sementara potongan berikutnya masih diambil.
// Dengan begitu jeda sebelum suara keluar jauh lebih pendek daripada menunggu semua teks selesai dibuat.
class VoicePlayer {
    private class Item(val pcm: ByteArray?, val error: String?)

    @Volatile private var stopped = false
    @Volatile var active = false
        private set
    private var track: AudioTrack? = null

    fun stop() {
        stopped = true
        active = false
        val t = track
        track = null
        try { t?.pause(); t?.flush(); t?.release() } catch (_: Exception) {}
    }

    private fun build(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun gain(a: ByteArray, g: Float) {
        if (g in 0.99f..1.01f) return
        var i = 0
        while (i + 1 < a.size) {
            val v = ((a[i].toInt() and 0xff) or (a[i + 1].toInt() shl 8)).toShort().toInt()
            val o = (v * g).toInt().coerceIn(-32768, 32767)
            a[i] = o.toByte()
            a[i + 1] = (o shr 8).toByte()
            i += 2
        }
    }

    fun play(
        pieces: List<String>,
        fetch: (String) -> ByteArray,
        robot: Int,
        glitch: Int,
        volume: Float,
        onFirstAudio: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        stopped = false
        active = true
        val rate = 24000
        val queue = LinkedBlockingQueue<Item>()

        thread(name = "capri-voice-fetch") {
            for (p in pieces) {
                if (stopped) return@thread
                try {
                    queue.put(Item(fetch(p), null))
                } catch (e: Exception) {
                    queue.put(Item(null, e.message ?: e.javaClass.simpleName))
                    return@thread
                }
            }
            queue.put(Item(null, null))
        }

        thread(name = "capri-voice-play") {
            var tr: AudioTrack? = null
            var written = 0L
            var first = true
            var failed: String? = null
            try {
                while (true) {
                    if (stopped) return@thread
                    val item = queue.poll(25, TimeUnit.SECONDS)
                    if (item == null) { failed = "suara terlalu lama"; break }
                    if (item.error != null) { failed = item.error; break }
                    val raw = item.pcm ?: break
                    val data = Effects.pcm(raw, rate, robot, glitch, written)
                    gain(data, volume)
                    if (tr == null) {
                        tr = build(rate)
                        track = tr
                        tr.play()
                    }
                    if (first) { first = false; onFirstAudio() }
                    tr.write(data, 0, data.size)
                    written += data.size / 2
                }
            } catch (e: Exception) {
                failed = failed ?: (e.message ?: e.javaClass.simpleName)
            }
            val t = tr
            if (t != null && !stopped) {
                try {
                    val deadline = System.currentTimeMillis() + 60_000L
                    while (!stopped && t.playbackHeadPosition.toLong() < written && System.currentTimeMillis() < deadline) {
                        Thread.sleep(40)
                    }
                } catch (_: Exception) {}
            }
            if (!stopped) {
                try { t?.stop(); t?.release() } catch (_: Exception) {}
                if (track === t) track = null
                active = false
                if (failed != null && first) onError(failed) else onDone()
            }
        }
    }
}
