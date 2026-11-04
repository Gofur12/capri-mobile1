package app.capri.mobile

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Suara Capri lewat Fish Audio. reference_id = Voice ID dari dashboard Fish Audio.
object FishVoice {
    private const val ENDPOINT = "https://api.fish.audio/v1/tts"

    fun speak(key: String, voiceId: String, speed: Float, text: String): ByteArray {
        val body = JSONObject()
            .put("text", text)
            .put("reference_id", voiceId)
            .put("format", "wav")
            .put("prosody", JSONObject().put("speed", speed.toDouble()).put("volume", 0))
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        // Model gratis seperti di PC (tanpa kredit). Tanpa header ini Fish memakai model berbayar.
        c.setRequestProperty("model", "s2.1-pro-free")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code != 200) {
            val err = (c.errorStream?.bufferedReader()?.use { it.readText() } ?: "").take(120)
            throw Exception("Fish $code $err")
        }
        return c.inputStream.use { it.readBytes() }
    }
}
