package app.capri.mobile

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Suara Capri lewat ElevenLabs. Satu panggilan = satu potongan teks; hasilnya PCM 16-bit mono 24 kHz.
object ElevenVoice {
    const val DEFAULT_MODEL = "eleven_v4_turbo"
    const val FALLBACK_MODEL = "eleven_flash_v2_5"
    const val DEFAULT_STABILITY = 0.4f
    const val DEFAULT_SIMILARITY = 0.75f
    const val DEFAULT_SPEED = 1.0f

    class VoiceError(val status: Int, msg: String) : Exception(msg)

    private fun post(key: String, voiceId: String, body: JSONObject, format: String): ByteArray {
        val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=$format")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("xi-api-key", key)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code != 200) {
            val err = (c.errorStream?.bufferedReader()?.use { it.readText() } ?: "").take(200)
            AppLog.add("SUARA", "ElevenLabs HTTP $code ($format): $err")
            val msg = when {
                err.contains("quota_exceeded") -> "kuota ElevenLabs habis ($code)"
                err.contains("invalid_api_key") -> "key ElevenLabs ditolak ($code)"
                else -> "ElevenLabs $code $err"
            }
            throw VoiceError(code, msg)
        }
        val bytes = c.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) throw VoiceError(0, "ElevenLabs mengirim data kosong")
        return bytes
    }

    private fun pcmOf(key: String, voiceId: String, body: JSONObject): ByteArray {
        var last: VoiceError? = null
        for (fmt in listOf("pcm_24000", "wav_24000")) {
            try {
                val raw = post(key, voiceId, body, fmt)
                if (fmt.startsWith("pcm")) return raw
                val a = Wav.parse(raw) ?: throw VoiceError(0, "format WAV ElevenLabs nggak dikenal")
                return Wav.toMono24k(a) ?: throw VoiceError(0, "WAV ElevenLabs bukan 16 bit")
            } catch (e: VoiceError) {
                last = e
                if (e.status == 401 || e.status == 429) break // key atau kuota: format lain tidak membantu
            }
        }
        throw last ?: VoiceError(0, "ElevenLabs gagal")
    }

    fun piece(key: String, voiceId: String, model: String, stability: Float, similarity: Float, speed: Float, text: String): ByteArray {
        val full = JSONObject()
            .put("text", text)
            .put("model_id", model.ifBlank { DEFAULT_MODEL })
            .put(
                "voice_settings", JSONObject()
                    .put("stability", stability.toDouble())
                    .put("similarity_boost", similarity.toDouble())
                    .put("speed", speed.toDouble())
            )
        return try {
            pcmOf(key, voiceId, full)
        } catch (e: VoiceError) {
            if (e.status != 400 && e.status != 404 && e.status != 422) throw e
            pcmOf(key, voiceId, JSONObject().put("text", text).put("model_id", FALLBACK_MODEL))
        }
    }
}
