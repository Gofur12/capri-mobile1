package app.capri.mobile

// Otak boleh menitipkan satu aksi tersembunyi di ujung balasannya. Tag ini dibuang dari
// teks yang ditampilkan/dibacakan, lalu aplikasi menjalankan aksinya sendiri.
object Actions {
    class Act(val kind: String, val arg: String)
    class Parsed(val text: String, val action: Act?)

    private val tag = Regex("<(play|search|music|open|close)>(.*?)</\\1>", RegexOption.IGNORE_CASE)

    fun extract(reply: String): Parsed {
        val matches = tag.findAll(reply).toList()
        if (matches.isEmpty()) return Parsed(reply.trim(), null)
        val first = matches.first()
        val action = Act(first.groupValues[1].lowercase(), first.groupValues[2].trim())
        var clean = reply
        for (m in matches.reversed()) clean = clean.removeRange(m.range)
        return Parsed(clean.replace(Regex("\\s{2,}"), " ").trim(), action)
    }

    fun strip(reply: String): String = extract(reply).text
}
