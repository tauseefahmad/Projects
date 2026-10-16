package com.example.pdfreader.core

/** Kinds of personal data that can be found and redacted automatically. */
enum class SensitiveKind { IBAN, EMAIL, PHONE }

/** Finds text to redact: personal data patterns and the user's own search terms. */
object Redactions {

    private val iban = Regex("""\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?\b""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    private val phone = Regex("""(?<![\w+])(?:\+|00)?\d[\d ()/-]{5,}\d(?!\w)""")
    private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")

    /**
     * Character ranges of [text] to redact: every match of [kinds] and every occurrence of
     * [terms] (ignoring case; any whitespace between words). Overlapping ranges are merged.
     */
    fun find(text: String, kinds: Set<SensitiveKind>, terms: List<String> = emptyList()): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        if (SensitiveKind.IBAN in kinds) iban.findAll(text).filter { isValidIban(it.value) }.mapTo(ranges) { it.range }
        if (SensitiveKind.EMAIL in kinds) email.findAll(text).mapTo(ranges) { it.range }
        if (SensitiveKind.PHONE in kinds) {
            phone.findAll(text)
                .filter { m -> m.value.count(Char::isDigit) in 7..15 && !isoDate.matches(m.value.trim()) && !isValidIban(m.value) }
                .filterNot { m -> ranges.any { it.first <= m.range.first && m.range.last <= it.last } }
                .mapTo(ranges) { trimmed(text, it.range) }
        }
        for (term in terms.map { it.trim() }.filter { it.isNotEmpty() }) {
            val pattern = term.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(text).mapTo(ranges) { it.range }
        }
        return merge(ranges)
    }

    /** IBAN check digits (ISO 13616, mod 97). */
    fun isValidIban(value: String): Boolean {
        val iban = value.replace(" ", "").uppercase()
        if (iban.length !in 15..34 || !iban.all { it.isLetterOrDigit() }) return false
        if (!iban[0].isLetter() || !iban[1].isLetter() || !iban[2].isDigit() || !iban[3].isDigit()) return false
        val rearranged = iban.substring(4) + iban.substring(0, 4)
        var remainder = 0
        for (c in rearranged) {
            val digits = if (c.isDigit()) c.toString() else (c - 'A' + 10).toString()
            for (d in digits) remainder = (remainder * 10 + (d - '0')) % 97
        }
        return remainder == 1
    }

    private fun trimmed(text: String, range: IntRange): IntRange {
        var first = range.first
        var last = range.last
        while (first < last && !text[first].isLetterOrDigit() && text[first] != '+' && text[first] != '(') first++
        while (last > first && !text[last].isLetterOrDigit() && text[last] != ')') last--
        return first..last
    }

    fun merge(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.sortedBy { it.first }
        val result = mutableListOf<IntRange>()
        for (r in sorted) {
            val last = result.lastOrNull()
            if (last != null && r.first <= last.last + 1) result[result.lastIndex] = last.first..maxOf(last.last, r.last)
            else result += r
        }
        return result
    }
}
