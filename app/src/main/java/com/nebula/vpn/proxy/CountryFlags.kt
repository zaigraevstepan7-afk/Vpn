package com.nebula.vpn.proxy

import java.util.Locale

/**
 * Best-effort "which country is this server in" → flag emoji, fully offline.
 *
 * Strategy, in order:
 *   1. If the remark already contains a flag emoji (most subscriptions add one),
 *      reuse it verbatim.
 *   2. Otherwise scan the remark for a country name (RU/EN) or a 2-letter code
 *      and map it to a flag.
 *   3. Fall back to a globe.
 */
object CountryFlags {

    private const val GLOBE = "🌐"

    fun flagFor(server: ServerConfig): String =
        extractFlagEmoji(server.remark)
            ?: flagFromText(server.remark)
            ?: GLOBE

    /** Pull an existing regional-indicator flag (two indicators in a row) out of a string. */
    private fun extractFlagEmoji(text: String): String? {
        val cps = text.codePoints().toArray()
        for (i in 0 until cps.size - 1) {
            if (cps[i] in 0x1F1E6..0x1F1FF && cps[i + 1] in 0x1F1E6..0x1F1FF) {
                return String(cps, i, 2)
            }
        }
        return null
    }

    /** Map a country name / ISO code found in [text] to a flag emoji. */
    private fun flagFromText(text: String): String? {
        val lower = " ${text.lowercase(Locale.ROOT)} "
        // Longer / more specific names first to avoid partial clashes.
        for ((needle, code) in NAME_TO_CODE) {
            if (lower.contains(needle)) return flagFromCode(code)
        }
        // Whole-token 2-letter codes (e.g. "DE", "RU").
        val tokens = text.uppercase(Locale.ROOT).split(Regex("[^A-Z]+"))
        for (t in tokens) {
            if (t.length == 2 && t in ISO_CODES) return flagFromCode(t)
        }
        return null
    }

    /** ISO-3166 alpha-2 → flag emoji (regional indicators). */
    fun flagFromCode(cc: String): String {
        if (cc.length != 2) return GLOBE
        val sb = StringBuilder()
        for (ch in cc.uppercase(Locale.ROOT)) {
            if (ch !in 'A'..'Z') return GLOBE
            sb.appendCodePoint(0x1F1E6 + (ch - 'A'))
        }
        return sb.toString()
    }

    // Country-name keywords (Russian + English) → ISO code. Ordered specific-first.
    private val NAME_TO_CODE: List<Pair<String, String>> = listOf(
        "россия" to "RU", "russia" to "RU", "russian" to "RU",
        "германия" to "DE", "germany" to "DE", "german" to "DE", "deutschland" to "DE",
        "нидерланды" to "NL", "netherlands" to "NL", "holland" to "NL", "amsterdam" to "NL",
        "сша" to "US", "америк" to "US", "united states" to "US", "usa" to "US",
        "великобритан" to "GB", "англия" to "GB", "united kingdom" to "GB", "britain" to "GB", "london" to "GB",
        "франция" to "FR", "france" to "FR", "paris" to "FR",
        "финлянди" to "FI", "finland" to "FI", "helsinki" to "FI",
        "швеци" to "SE", "sweden" to "SE",
        "норвеги" to "NO", "norway" to "NO",
        "польша" to "PL", "poland" to "PL", "warsaw" to "PL",
        "украин" to "UA", "ukraine" to "UA",
        "турци" to "TR", "turkey" to "TR", "türkiye" to "TR", "istanbul" to "TR",
        "япони" to "JP", "japan" to "JP", "tokyo" to "JP",
        "сингапур" to "SG", "singapore" to "SG",
        "гонконг" to "HK", "hong kong" to "HK", "hongkong" to "HK",
        "корея" to "KR", "korea" to "KR", "seoul" to "KR",
        "канада" to "CA", "canada" to "CA",
        "бразили" to "BR", "brazil" to "BR", "brasil" to "BR",
        "индия" to "IN", "india" to "IN",
        "оаэ" to "AE", "эмират" to "AE", "emirates" to "AE", "dubai" to "AE",
        "швейцар" to "CH", "switzerland" to "CH", "zurich" to "CH",
        "австри" to "AT", "austria" to "AT",
        "испани" to "ES", "spain" to "ES",
        "итали" to "IT", "italy" to "IT",
        "латви" to "LV", "latvia" to "LV",
        "литва" to "LT", "lithuania" to "LT",
        "эстони" to "EE", "estonia" to "EE",
        "казахстан" to "KZ", "kazakhstan" to "KZ",
        "молдов" to "MD", "moldova" to "MD",
        "румыни" to "RO", "romania" to "RO",
        "болгари" to "BG", "bulgaria" to "BG",
        "чехи" to "CZ", "czech" to "CZ",
        "ирланди" to "IE", "ireland" to "IE",
        "австрали" to "AU", "australia" to "AU",
        "китай" to "CN", "china" to "CN",
        "тайвань" to "TW", "taiwan" to "TW",
        "вьетнам" to "VN", "vietnam" to "VN",
        "индонези" to "ID", "indonesia" to "ID",
        "израил" to "IL", "israel" to "IL",
        "арген" to "AR", "argentina" to "AR",
        "мексик" to "MX", "mexico" to "MX",
        "венгри" to "HU", "hungary" to "HU",
        "дани" to "DK", "denmark" to "DK",
        "бельги" to "BE", "belgium" to "BE",
        "сербия" to "RS", "serbia" to "RS",
        "хорват" to "HR", "croatia" to "HR",
        "грузия" to "GE", "georgia" to "GE",
        "армени" to "AM", "armenia" to "AM",
        "азербайджан" to "AZ", "azerbaijan" to "AZ",
        "белорус" to "BY", "belarus" to "BY"
    )

    private val ISO_CODES: Set<String> = NAME_TO_CODE.map { it.second }.toSet()
}
