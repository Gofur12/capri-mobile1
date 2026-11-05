package app.capri.mobile

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

// Suara Capri lewat Fish Audio, mengikuti cara versi PC: model gratis di header, WAV 24 kHz,
// teks panjang dipecah per beberapa kalimat, dan WAV dibaca manual karena header dari Fish bisa berukuran palsu.
object FishVoice {
    private const val ENDPOINT = "https://api.fish.audio/v1/tts"
    private const val FREE_MODEL = "s2.1-pro-free"
    private const val RATE = 24000

    private class Wave(val rate: Int, val channels: Int, val bits: Int, val pcm: ByteArray)

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int): Int = le16(b, o) or (le16(b, o + 2) shl 16)

    private fun parse(b: ByteArray): Wave? {
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var rate = RATE
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
                return Wave(rate, ch, bits, b.copyOfRange(start, start + len))
            }
            if (size < 0 || start + size > b.size) return null
            i = start + size + (size and 1)
        }
        return null
    }

    private fun wavOf(pcm: ByteArray, rate: Int, ch: Int, bits: Int): ByteArray {
        val out = ByteArray(44 + pcm.size)
        fun put32(o: Int, v: Int) {
            out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte()
        }
        fun put16(o: Int, v: Int) {
            out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte()
        }
        "RIFF".toByteArray().copyInto(out, 0)
        put32(4, 36 + pcm.size)
        "WAVEfmt ".toByteArray().copyInto(out, 8)
        put32(16, 16)
        put16(20, 1)
        put16(22, ch)
        put32(24, rate)
        put32(28, rate * ch * bits / 8)
        put16(32, ch * bits / 8)
        put16(34, bits)
        "data".toByteArray().copyInto(out, 36)
        put32(40, pcm.size)
        pcm.copyInto(out, 44)
        return out
    }

    // Teks panjang dikirim beberapa kalimat sekali jalan, supaya suara tidak terpotong di tengah.
    private fun pieces(text: String): List<String> {
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (s in text.split(Regex("(?<=[.!?…])\\s+"))) {
            if (cur.isNotEmpty() && cur.length + s.length > 180) {
                out.add(cur.toString().trim())
                cur = StringBuilder()
            }
            cur.append(s).append(' ')
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out.filter { it.isNotBlank() }
    }

    private fun request(key: String, voiceId: String, speed: Float, text: String): ByteArray {
        val body = JSONObject()
            .put("text", text)
            .put("reference_id", voiceId)
            .put("format", "wav")
            .put("sample_rate", RATE)
        if (abs(speed - 1f) > 0.01f) body.put("prosody", JSONObject().put("speed", speed.toDouble()).put("volume", 0))
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("model", FREE_MODEL) // model gratis, sama seperti di PC
        c.setRequestProperty("User-Agent", "Capri-Mobile")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val type = c.contentType ?: ""
        if (code != 200) {
            val err = (c.errorStream?.bufferedReader()?.use { it.readText() } ?: "").take(160)
            AppLog.add("SUARA", "Fish HTTP $code: $err")
            throw Exception(
                when (code) {
                    401, 403 -> "key Fish ditolak ($code)"
                    404 -> "voice Fish nggak ketemu (404), cek Voice ID"
                    429 -> "Fish sibuk (429), coba lagi sebentar"
                    else -> "Fish $code $err"
                }
            )
        }
        val bytes = c.inputStream.use { it.readBytes() }
        AppLog.add("SUARA", "Fish 200: ${bytes.size} byte, tipe=$type")
        if (bytes.isEmpty()) throw Exception("Fish mengirim data kosong")
        if (type.contains("json", true) || type.contains("text", true)) {
            throw Exception("Fish mengirim teks, bukan suara: " + String(bytes).take(100))
        }
        return bytes
    }

    fun speak(key: String, voiceId: String, speed: Float, text: String): ByteArray {
        val pcm = ByteArrayOutputStream()
        var rate = RATE
        var ch = 1
        var bits = 16
        for (piece in pieces(text)) {
            val raw = request(key, voiceId, speed, piece)
            val w = parse(raw)
                ?: throw Exception("format suara Fish nggak dikenal (awal data: " + raw.take(4).joinToString("") { "%02x".format(it) } + ")")
            rate = w.rate
            ch = w.channels
            bits = w.bits
            pcm.write(w.pcm)
        }
        if (pcm.size() == 0) throw Exception("Fish mengirim suara kosong")
        AppLog.add("SUARA", "Fish siap: ${pcm.size()} byte PCM, $rate Hz, $ch kanal, $bits bit")
        return wavOf(pcm.toByteArray(), rate, ch, bits)
    }
}
