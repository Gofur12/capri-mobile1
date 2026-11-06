package app.capri.mobile

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

// Efek suara Capri untuk PCM 16-bit mono: robot (ring modulation) dan glitch (putus, gagap, suara pecah).
object Effects {
    fun pcm(src: ByteArray, rate: Int, robot: Int, glitch: Int, offset: Long): ByteArray {
        if ((robot <= 0 && glitch <= 0) || src.size < 4) return src
        val n = src.size / 2
        val a = ShortArray(n)
        for (i in 0 until n) a[i] = ((src[2 * i].toInt() and 0xff) or (src[2 * i + 1].toInt() shl 8)).toShort()

        if (robot > 0) {
            val mix = robot.coerceIn(0, 100) / 100f * 0.85f
            val hz = 55.0 + robot.coerceIn(0, 100) * 0.5
            for (i in 0 until n) {
                val t = (offset + i).toDouble() / rate
                val m = sin(2.0 * PI * hz * t)
                val x = a[i].toInt()
                a[i] = (x * (1 - mix) + x * m * mix).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        if (glitch > 0) glitchify(a, rate, glitch)

        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = a[i].toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun glitchify(a: ShortArray, rate: Int, level: Int) {
        val n = a.size
        val block = rate / 3
        val chance = level.coerceIn(0, 100) / 100f * 0.55f
        var s = 0
        while (s < n) {
            val end = minOf(n, s + block)
            val len = end - s
            if (len > rate / 10 && Random.nextFloat() < chance) {
                when (Random.nextInt(3)) {
                    0 -> { // putus sebentar
                        val d = rate * (25 + Random.nextInt(45)) / 1000
                        val st = s + Random.nextInt((len - d).coerceAtLeast(1))
                        for (i in st until minOf(n, st + d)) a[i] = 0
                    }
                    1 -> { // gagap: potongan pendek diulang
                        val l = rate * (35 + Random.nextInt(40)) / 1000
                        val st = s + Random.nextInt((len - 3 * l).coerceAtLeast(1))
                        for (r in 1..2) {
                            for (i in 0 until l) {
                                val from = st + i
                                val to = st + r * l + i
                                if (from < n && to < n) a[to] = a[from]
                            }
                        }
                    }
                    else -> { // suara pecah (bitcrush) sebentar
                        val d = rate * (70 + Random.nextInt(90)) / 1000
                        val st = s + Random.nextInt((len - d).coerceAtLeast(1))
                        for (i in st until minOf(n, st + d)) a[i] = ((a[i].toInt() shr 9) shl 9).toShort()
                    }
                }
            }
            s = end
        }
    }
}
