package app.capri.mobile

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

// Suara Capri lewat Fish Audio, mengikuti cara versi PC (model gratis di header, WAV 24 kHz).
// Satu panggilan = satu potongan teks; hasilnya PCM 16-bit mono 24 kHz.
object FishVoice {
    private const val ENDPOINT = "https://api.fish.audio/v1/tts"
    private const val FREE_MODEL = "s2.1-pro-free"

    fun piece(key: String, voiceId: String, speed: Float, text: String): ByteArray {
        val body = JSONObject()
            .put("text", text)
            .put("reference_id", voiceId)
            .put("format", "wav")
            .put("sample_rate", 24000)
        if (abs(speed - 1f) > 0.01f) body.put("prosody", JSONObject().put("speed", speed.toDouble()).put("volume", 0))
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("model", FREE_MODEL)
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
                    402 -> "kredit Fish habis (402)"
                    404 -> "voice Fish nggak ketemu (404), cek Voice ID"
                    429 -> "Fish sibuk (429), coba lagi sebentar"
                    else -> "Fish $code $err"
                }
            )
        }
        val bytes = c.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) throw Exception("Fish mengirim data kosong")
        if (type.contains("json", true) || type.contains("text", true)) {
            throw Exception("Fish mengirim teks, bukan suara: " + String(bytes).take(100))
        }
        val audio = Wav.parse(bytes)
            ?: throw Exception("format suara Fish nggak dikenal (awal data: " + bytes.take(4).joinToString("") { "%02x".format(it) } + ")")
        return Wav.toMono24k(audio) ?: throw Exception("audio Fish ${audio.bits} bit belum didukung")
    }
}
