package app.baleoqab

import java.util.Locale

/** تشخیص کشور از اسم سرور (پرچم، اسم کشور یا کد دو حرفی). */
object Geo {
    private val valid: Set<String> = Locale.getISOCountries().toSet()
    private val blocked = setOf("SS", "VL", "VM", "TG", "XX", "HY", "WS")
    private val codeRegex = Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])")

    private val names: List<Pair<String, String>> = listOf(
        "germany" to "DE", "deutschland" to "DE", "frankfurt" to "DE", "spain" to "ES", "madrid" to "ES",
        "finland" to "FI", "helsinki" to "FI", "netherlands" to "NL", "amsterdam" to "NL", "holland" to "NL",
        "united states" to "US", "usa" to "US", "united kingdom" to "GB", "london" to "GB", "britain" to "GB",
        "france" to "FR", "paris" to "FR", "canada" to "CA", "japan" to "JP", "tokyo" to "JP",
        "singapore" to "SG", "hong kong" to "HK", "hongkong" to "HK", "turkey" to "TR", "turkiye" to "TR",
        "istanbul" to "TR", "russia" to "RU", "moscow" to "RU", "sweden" to "SE", "norway" to "NO",
        "poland" to "PL", "italy" to "IT", "switzerland" to "CH", "austria" to "AT", "ukraine" to "UA",
        "romania" to "RO", "lithuania" to "LT", "latvia" to "LV", "estonia" to "EE", "bulgaria" to "BG",
        "czech" to "CZ", "ireland" to "IE", "denmark" to "DK", "belgium" to "BE", "portugal" to "PT",
        "india" to "IN", "korea" to "KR", "taiwan" to "TW", "australia" to "AU", "brazil" to "BR",
        "uae" to "AE", "emirates" to "AE", "dubai" to "AE", "israel" to "IL", "armenia" to "AM",
        "kazakhstan" to "KZ", "china" to "CN", "indonesia" to "ID", "thailand" to "TH", "vietnam" to "VN",
        "malaysia" to "MY", "serbia" to "RS", "hungary" to "HU", "moldova" to "MD", "cyprus" to "CY",
        "iceland" to "IS"
    )

    private val nameRegexes: List<Pair<Regex, String>> =
        names.map { Regex("(?<![a-z])" + Regex.escape(it.first) + "(?![a-z])") to it.second }

    fun codeOf(name: String): String? {
        flagCode(name)?.let { return it }
        val low = name.lowercase(Locale.ROOT)
        for ((re, code) in nameRegexes) {
            if (re.containsMatchIn(low)) return code
        }
        for (m in codeRegex.findAll(name)) {
            val c = m.groupValues[1]
            if (c in valid && c !in blocked) return c
        }
        return null
    }

    private fun flagCode(s: String): String? {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (cp in 0x1F1E6..0x1F1FF && i + n < s.length) {
                val cp2 = s.codePointAt(i + n)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    val a = 'A' + (cp - 0x1F1E6)
                    val b = 'A' + (cp2 - 0x1F1E6)
                    val code = "$a$b"
                    if (code in valid) return code
                }
            }
            i += n
        }
        return null
    }

    fun flag(code: String): String {
        if (code.length != 2) return "🌐"
        val a = Character.toChars(0x1F1E6 + (code[0] - 'A'))
        val b = Character.toChars(0x1F1E6 + (code[1] - 'A'))
        return String(a) + String(b)
    }

    fun nameFa(code: String): String {
        val n = Locale("", code).getDisplayCountry(Locale("fa"))
        return if (n.isNullOrBlank()) code else n
    }
}
