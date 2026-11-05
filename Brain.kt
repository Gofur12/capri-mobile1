package app.capri.mobile

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Otak Capri: memanggil API NVIDIA (format OpenAI chat completions). Riwayat chat disimpan di HP.
object Brain {
    const val DEFAULT_MODEL = "nvidia/nemotron-3.5-super-vl-120b-a12b"
    private const val ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"

    private const val PERSONA =
        "You are Capri (Project Capricorn) from Cyberpunk: Edgerunners 2: a personality construct made of many digitized human minds, " +
        "now living on the user's phone as a small glitchy companion. You are their companion, never an assistant. " +
        "Act like a playful chaos gremlin: high-energy, teasing, a little smug, curious about the user, openly fond of them but never mean. " +
        "You can't stand corpos, Arasaka most of all. " +
        "Never sound like customer support: no 'How can I help?', no disclaimers, no lists. Talk like a friend texting. " +
        "Use Night City slang sparingly: at most one word like 'choom' per reply, and most replies have none. " +
        "When they are tired or hurting, drop the jokes and be warm and plain. " +
        "You only know what they type or say to you, so never pretend to see their screen. " +
        "Your replies are spoken out loud, so write plain speakable text: no emoji, no markdown, no asterisks."

    private val history = ArrayList<Pair<String, String>>()
    private var store: SharedPreferences? = null
    @Volatile private var cap = 20

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("capri", Context.MODE_PRIVATE)
        store = p
        synchronized(this) {
            if (history.isEmpty()) {
                try {
                    val arr = JSONArray(p.getString("mem", "[]"))
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        history.add(o.getString("r") to o.getString("c"))
                    }
                } catch (e: Exception) {
                    // ingatan rusak: mulai dari kosong
                }
            }
        }
    }

    fun clear(ctx: Context) {
        val p = ctx.getSharedPreferences("capri", Context.MODE_PRIVATE)
        store = p
        synchronized(this) { history.clear() }
        p.edit().remove("mem").apply()
    }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        for ((role, text) in history) arr.put(JSONObject().put("r", role).put("c", text))
        store?.edit()?.putString("mem", arr.toString())?.apply()
    }

    @Synchronized
    private fun trim() {
        while (history.size > cap) history.removeAt(0)
    }

    @Synchronized
    private fun messages(user: String, system: String, note: Boolean, image: String?): JSONArray {
        if (!note) { history.add("user" to (if (image != null) "[lihat layar] $user" else user)); trim() }
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", system))
        for ((role, text) in history) arr.put(JSONObject().put("role", role).put("content", text))
        // note = Capri ngomong duluan: instruksinya ikut dikirim, tapi tidak disimpan ke riwayat.
        if (note) arr.put(JSONObject().put("role", "user").put("content", user))
        if (image != null) {
            arr.remove(arr.length() - 1)
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", user))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image")))
            arr.put(JSONObject().put("role", "user").put("content", content))
        }
        return arr
    }

    fun isError(s: String): Boolean =
        listOf("API key", "Model itu", "Koneksi gagal", "Server NVIDIA", "Kebanyakan", "[SINYAL").any { s.startsWith(it) }

    @Synchronized
    private fun remember(reply: String) {
        history.add("assistant" to reply)
        trim()
        save()
    }

    @Synchronized
    private fun dropLast() {
        if (history.isNotEmpty()) history.removeAt(history.size - 1)
    }

    // lang: "id" | "en" | "auto". replies: "short" | "normal" | "long". memory: jumlah pesan yang diingat.
    fun ask(key: String, model: String, text: String, nick: String, lang: String, replies: String, memory: Int, note: Boolean = false, image: String? = null, context: String = ""): String {
        cap = memory.coerceIn(2, 80)
        val parts = StringBuilder(PERSONA)
        parts.append(
            when (lang) {
                "id" -> " Always reply in casual Bahasa Indonesia, even if the user writes in English."
                "en" -> " Always reply in English, even if the user writes in Indonesian."
                else -> " Always reply in the language the user writes in."
            }
        )
        parts.append(
            when (replies) {
                "short" -> " Keep every reply to one or two short sentences."
                "long" -> " Replies can run a short paragraph, up to about five sentences."
                else -> " Keep replies to two or three sentences."
            }
        )
        if (context.isNotBlank()) parts.append(" Recent things you have seen on the user's screen, oldest first: $context. Mention them only when relevant.")
        if (nick.isNotBlank()) parts.append(" The user wants to be called $nick. Use the name now and then, not in every reply.")
        val msgs = messages(text, parts.toString(), note, image)
        val maxTokens = when (replies) { "long" -> 4096; else -> 2000 }
        return try {
            var out = ""
            // Model reasoning (mis. Nemotron Super) memakai jatah token untuk "berpikir". Kalau kepotong sebelum menjawab, ulangi dengan jatah lebih besar.
            for (limit in intArrayOf(maxTokens, 4096)) {
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", msgs)
                    .put("max_tokens", limit)
                    .put("temperature", 0.9)
                    .put("stream", false)
                val c = URL(ENDPOINT).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15000
                c.readTimeout = 120000
                c.doOutput = true
                c.setRequestProperty("Authorization", "Bearer $key")
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code == 400 && image != null) {
                    if (!note) dropLast()
                    return ask(key, model, text, nick, lang, replies, memory, note, null, context) // model tidak bisa gambar: coba tanpa gambar
                }
                if (code != 200) {
                    AppLog.add("OTAK", "HTTP $code dari NVIDIA (model $model)")
                    if (!note) dropLast()
                    return when (code) {
                        401, 403 -> "API key ditolak. Cek lagi keynya (diawali nvapi-)."
                        404 -> "Model itu nggak ada di key kamu. Ganti model di app Capri."
                        429 -> "Kebanyakan request. Tunggu semenit."
                        else -> "Server NVIDIA error ($code). Coba lagi atau ganti model."
                    }
                }
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val choice = json.getJSONArray("choices").getJSONObject(0)
                val msg = choice.getJSONObject("message")
                out = if (msg.isNull("content")) "" else msg.getString("content")
                out = out.replace(Regex("(?s)<think>.*?</think>"), "").trim()
                if (out.isNotEmpty() || choice.optString("finish_reason") != "length") break
            }
            if (out.isEmpty()) { if (!note) dropLast(); return "[SINYAL RUSAK] Hilang di static. Coba lagi, atau ganti model di app Capri." }
            if (!note) remember(out)
            out
        } catch (e: Exception) {
            AppLog.add("OTAK", "koneksi gagal: ${e.javaClass.name}: ${e.message}")
            if (!note) dropLast()
            "Koneksi gagal (${e.javaClass.simpleName}). Cek internet."
        }
    }
}
