package app.capri.mobile

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// Bunyi pendek: note block (nada sinus meluruh cepat) dan zap glitch (derau pecah yang meluruh).
object Chime {
    private fun playBuf(buf: ShortArray, rate: Int) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(buf, 0, buf.size)
        track.play()
        Thread.sleep(buf.size * 1000L / rate + 250L)
        track.release()
    }

    fun play(volumePct: Int, notes: IntArray = intArrayOf(659, 784, 988)) {
        if (volumePct <= 0) return
        thread(name = "capri-chime") {
            try {
                val rate = 22050
                val per = rate * 140 / 1000
                val buf = ShortArray(per * notes.size)
                for (n in notes.indices) {
                    for (i in 0 until per) {
                        val t = i.toDouble() / rate
                        val env = exp(-t * 18.0)
                        val v = (sin(2.0 * PI * notes[n] * t) * 0.7 + sin(2.0 * PI * notes[n] * 2 * t) * 0.25) * env
                        buf[n * per + i] = (v * 9000.0 * volumePct / 100.0).toInt().coerceIn(-32768, 32767).toShort()
                    }
                }
                playBuf(buf, rate)
            } catch (_: Exception) {
            }
        }
    }

    fun zap(volumePct: Int) {
        if (volumePct <= 0) return
        thread(name = "capri-zap") {
            try {
                val rate = 22050
                val n = rate * 280 / 1000
                val buf = ShortArray(n)
                var held = 0
                for (i in 0 until n) {
                    if (i % 36 == 0) held = Random.nextInt(-12000, 12000)
                    val env = 1.0 - i.toDouble() / n
                    buf[i] = (held * env * 0.6 * volumePct / 100.0).toInt().coerceIn(-32768, 32767).toShort()
                }
                playBuf(buf, rate)
            } catch (_: Exception) {
            }
        }
    }
}
