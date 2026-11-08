package app.capri.mobile

import java.util.Locale

// Daftar bahasa Capri. "auto" berarti ikut bahasa yang dipakai user.
object Lang {
    class Opt(val code: String, val label: String, val locale: Locale?, val english: String)

    val all = listOf(
        Opt("auto", "Ikut bahasa kamu", null, ""),
        Opt("id", "Bahasa Indonesia", Locale("id", "ID"), "Bahasa Indonesia"),
        Opt("en", "English", Locale.US, "English"),
        Opt("ms", "Bahasa Melayu", Locale("ms", "MY"), "Bahasa Melayu"),
        Opt("jv", "Basa Jawa", Locale("jv", "ID"), "Javanese"),
        Opt("su", "Basa Sunda", Locale("su", "ID"), "Sundanese"),
        Opt("zh", "中文 (Mandarin)", Locale.SIMPLIFIED_CHINESE, "Simplified Chinese"),
        Opt("ja", "日本語", Locale.JAPANESE, "Japanese"),
        Opt("ko", "한국어", Locale.KOREAN, "Korean"),
        Opt("es", "Español", Locale("es", "ES"), "Spanish"),
        Opt("pt", "Português", Locale("pt", "BR"), "Portuguese"),
        Opt("fr", "Français", Locale.FRENCH, "French"),
        Opt("de", "Deutsch", Locale.GERMAN, "German"),
        Opt("it", "Italiano", Locale.ITALIAN, "Italian"),
        Opt("ru", "Русский", Locale("ru", "RU"), "Russian"),
        Opt("ar", "العربية", Locale("ar", "SA"), "Arabic"),
        Opt("hi", "हिन्दी", Locale("hi", "IN"), "Hindi"),
        Opt("th", "ไทย", Locale("th", "TH"), "Thai"),
        Opt("vi", "Tiếng Việt", Locale("vi", "VN"), "Vietnamese"),
        Opt("tr", "Türkçe", Locale("tr", "TR"), "Turkish"),
        Opt("pl", "Polski", Locale("pl", "PL"), "Polish"),
        Opt("nl", "Nederlands", Locale("nl", "NL"), "Dutch"),
        Opt("uk", "Українська", Locale("uk", "UA"), "Ukrainian"),
        Opt("fil", "Filipino", Locale("fil", "PH"), "Filipino")
    )

    fun get(code: String): Opt = all.firstOrNull { it.code == code } ?: all[1]

    fun labels(): Array<String> = all.map { it.label }.toTypedArray()
    fun codes(): Array<String> = all.map { it.code }.toTypedArray()

    fun locale(code: String): Locale? = get(code).locale

    fun instruction(code: String): String {
        val o = get(code)
        return if (o.code == "auto") " Always reply in the language the user writes in."
        else " Always reply in ${o.english}, even if the user writes in another language."
    }
}
