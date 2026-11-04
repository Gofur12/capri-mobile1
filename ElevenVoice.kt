package app.capri.mobile

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Suara Capri lewat ElevenLabs. Pengaturannya sama dengan versi PC: model, voice ID,
// stability, similarity, dan speed. Stability rendah bikin suara lebih hidup (nggak monoton).
object ElevenVoice {
    const val DEFAULT_MODEL = "eleven_v4_turbo"
    const val FALLBACK_MODEL = "eleven_flash_v2_5"
    const val DEFAULT_STABILITY = 0.4f
    const val DEFAULT_SIMILARITY = 0.75f
    const val DEFAULT_SPEED = 1.0f

    class VoiceError(val status: Int, msg: String) : Exception(msg)

    private fun post(key: String, voiceId: String, body: JSONObject): ByteArray {
        val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=wav_24000")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("xi-api-key", key)
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "audio/wav")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code != 200) {
            val err = (c.errorStream?.bufferedReader()?.use { it.readText() } ?: "").take(200)
            throw VoiceError(code, "ElevenLabs $code $err")
        }
        return c.inputStream.use { it.readBytes() }
    }

    // Mengembalikan audio WAV. Kalau model/pengaturan ditolak akun, coba ulang dengan model cadangan, sama seperti versi PC.
    fun speak(key: String, voiceId: String, model: String, stability: Float, similarity: Float, speed: Float, text: String): ByteArray {
        val m = model.ifBlank { DEFAULT_MODEL }
        val full = JSONObject()
            .put("text", text)
            .put("model_id", m)
            .put("voice_settings", JSONObject()
                .put("stability", stability.toDouble())
                .put("similarity_boost", similarity.toDouble())
                .put("speed", speed.toDouble()))
        return try {
            post(key, voiceId, full)
        } catch (e: VoiceError) {
            if (e.status != 400 && e.status != 404 && e.status != 422) throw e
            val plain = JSONObject().put("text", text).put("model_id", FALLBACK_MODEL)
            post(key, voiceId, plain)
        }
    }
}
