package app.capri.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread

// Pemutar file suara pendek dari assets (dipakai untuk sapaan ikonik Capri saat spawn).
object Sfx {
    fun playAsset(ctx: Context, names: List<String>, volumePct: Int): Boolean {
        var raw: ByteArray? = null
        for (name in names) {
            try {
                raw = ctx.assets.open(name).use { it.readBytes() }
                break
            } catch (_: Exception) {
            }
        }
        val bytes = raw ?: return false
        val audio = Wav.parse(bytes) ?: return false
        val pcm = Wav.toMono24k(audio) ?: return false
        if (pcm.isEmpty()) return false
        val gain = (volumePct.coerceIn(0, 150) / 100f)
        thread(name = "capri-spawn-voice") {
            var track: AudioTrack? = null
            try {
                val rate = 24000
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
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
                    .setBufferSizeInBytes(pcm.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                if (gain != 1f) {
                    var i = 0
                    while (i + 1 < pcm.size) {
                        val v = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                        val o = (v * gain).toInt().coerceIn(-32768, 32767)
                        pcm[i] = o.toByte()
                        pcm[i + 1] = (o shr 8).toByte()
                        i += 2
                    }
                }
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / 2L / 24000L + 250L)
            } catch (_: Exception) {
            } finally {
                try { track?.stop(); track?.release() } catch (_: Exception) {}
            }
        }
        return true
    }
}
