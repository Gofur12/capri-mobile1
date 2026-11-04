package app.capri.mobile

import kotlin.math.sin
import kotlin.random.Random

// Efek suara Capri: robot (ring modulation) dan glitch (potongan sunyi acak), untuk WAV 16-bit PCM.
object Effects {
    fun process(wav: ByteArray, robot: Int, glitch: Int): ByteArray {
        if (robot <= 0 && glitch <= 0) return wav
        val data = findData(wav) ?: return wav
        val rate = le32(wav, 24).coerceAtLeast(8000)
        val out = wav.copyOf()
        val n = (wav.size - data) / 2
        val mix = robot.coerceIn(0, 100) / 100f
        val hz = 40.0 + robot.coerceIn(0, 100) * 0.6
        val chance = glitch.coerceIn(0, 100) / 100f * 0.35f
        val block = rate / 4
        var muteFrom = -1
        var muteTo = -1
        for (s in 0 until n) {
            if (s % block == 0) {
                muteFrom = -1
                muteTo = -1
                if (glitch > 0 && Random.nextFloat() < chance) {
                    val len = rate * (20 + Random.nextInt(40)) / 1000
                    muteFrom = s + Random.nextInt((block - len).coerceAtLeast(1))
                    muteTo = muteFrom + len
                }
            }
            var v = le16(out, data + 2 * s).toDouble()
            if (mix > 0f) {
                val t = s.toDouble() / rate
                v = v * (1 - mix) + v * sin(2 * Math.PI * hz * t) * mix
            }
            if (s >= muteFrom && s < muteTo) v = 0.0
            put16(out, data + 2 * s, v.coerceIn(-32768.0, 32767.0).toInt())
        }
        return out
    }

    private fun findData(b: ByteArray): Int? {
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val size = le32(b, i + 4)
            if (size < 0) return null
            if (id == "data") return i + 8
            i += 8 + size + (size and 1)
        }
        return null
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun le16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)).toShort().toInt()

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xff).toByte()
        b[o + 1] = ((v shr 8) and 0xff).toByte()
    }
}
