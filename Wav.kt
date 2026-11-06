package app.capri.mobile

// Pembaca WAV tahan banting (header dari server kadang berisi ukuran palsu) dan pemecah teks untuk suara.
object Wav {
    class Audio(val rate: Int, val channels: Int, val bits: Int, val pcm: ByteArray)

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int): Int = le16(b, o) or (le16(b, o + 2) shl 16)

    fun parse(b: ByteArray): Audio? {
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var rate = 24000
        var ch = 1
        var bits = 16
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val size = le32(b, i + 4)
            val start = i + 8
            if (id == "fmt " && start + 16 <= b.size) {
                ch = le16(b, start + 2).coerceAtLeast(1)
                rate = le32(b, start + 4)
                bits = le16(b, start + 14)
            } else if (id == "data") {
                val len = if (size <= 0 || size > b.size - start) b.size - start else size
                return Audio(rate, ch, bits, b.copyOfRange(start, start + len))
            }
            if (size < 0 || start + size > b.size) return null
            i = start + size + (size and 1)
        }
        return null
    }

    // Ubah ke PCM 16-bit mono 24 kHz, satu-satunya format yang diputar VoicePlayer.
    fun toMono24k(a: Audio): ByteArray? {
        if (a.bits != 16 || a.channels < 1) return null
        val n = a.pcm.size / 2 / a.channels
        val mono = ShortArray(n)
        for (i in 0 until n) {
            var sum = 0
            for (c in 0 until a.channels) sum += le16(a.pcm, (i * a.channels + c) * 2).toShort().toInt()
            mono[i] = (sum / a.channels).toShort()
        }
        val src = if (a.rate == 24000 || a.rate <= 0) mono else resample(mono, a.rate, 24000)
        val out = ByteArray(src.size * 2)
        for (i in src.indices) {
            val v = src[i].toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun resample(a: ShortArray, from: Int, to: Int): ShortArray {
        if (a.isEmpty()) return a
        val n = (a.size.toLong() * to / from).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val pos = i.toDouble() * from / to
            val i0 = pos.toInt().coerceAtMost(a.size - 1)
            val i1 = (i0 + 1).coerceAtMost(a.size - 1)
            val f = pos - i0
            out[i] = (a[i0] * (1 - f) + a[i1] * f).toInt().toShort()
        }
        return out
    }
}

object Speech {
    private fun hardSplit(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val res = ArrayList<String>()
        var rest = s
        while (rest.length > max) {
            var cut = rest.lastIndexOf(' ', max)
            if (cut < max / 2) cut = max
            res.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) res.add(rest)
        return res
    }

    // Potongan pertama = satu kalimat saja, supaya suara mulai cepat. Sisanya digabung sampai kira-kira 200 huruf.
    fun pieces(text: String): List<String> {
        val parts = text.split(Regex("(?<=[.!?…])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { hardSplit(it, 200) }
        if (parts.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        out.add(parts[0])
        var cur = StringBuilder()
        for (i in 1 until parts.size) {
            if (cur.isNotEmpty() && cur.length + parts[i].length > 200) {
                out.add(cur.toString().trim())
                cur = StringBuilder()
            }
            cur.append(parts[i]).append(' ')
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }
}
