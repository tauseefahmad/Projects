package com.example.pdfreader.core

/** Kinds of documents recognized from their wording (English and German). */
enum class DocumentKind(val label: String) {
    INVOICE("Invoice"), RECEIPT("Receipt"), CONTRACT("Contract"), PAYSLIP("Payslip"), LETTER("Letter"), OTHER("Document")
}

/** A value found in a document. */
data class ExtractedField(val name: String, val value: String, val page: Int)

data class ExtractedData(val kind: DocumentKind, val fields: List<ExtractedField>) {

    fun json(): String = buildString {
        append("{\n  \"type\": \"").append(escape(kind.label)).append("\",\n  \"fields\": [")
        fields.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append("\n    {\"name\": \"").append(escape(f.name)).append("\", \"value\": \"").append(escape(f.value))
                .append("\", \"page\": ").append(f.page + 1).append('}')
        }
        append(if (fields.isEmpty()) "]\n}\n" else "\n  ]\n}\n")
    }

    fun csv(): String = TableExtraction.csv(listOf(listOf("Field", "Value", "Page")) + fields.map { listOf(it.name, it.value, (it.page + 1).toString()) })

    private fun escape(s: String) = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }
}

/**
 * Pulls key data out of invoices, receipts, contracts and payslips: amounts, dates, IBANs,
 * invoice numbers, VAT IDs, emails and phone numbers. Rules, not AI: values are only
 * reported where they literally appear.
 */
object DataExtraction {

    private val kinds = mapOf(
        DocumentKind.INVOICE to listOf("invoice", "rechnung", "rechnungsnummer", "invoice number", "amount due", "zahlbar", "ust-idnr", "vat"),
        DocumentKind.RECEIPT to listOf("receipt", "quittung", "kassenbon", "beleg", "bar bezahlt", "paid in cash", "change", "rückgeld"),
        DocumentKind.CONTRACT to listOf("contract", "agreement", "vertrag", "vereinbarung", "parties", "vertragsparteien", "kündigung", "termination", "§"),
        DocumentKind.PAYSLIP to listOf("payslip", "pay slip", "gehaltsabrechnung", "lohnabrechnung", "entgeltabrechnung", "brutto", "netto", "gross pay", "net pay", "lohnsteuer"),
        DocumentKind.LETTER to listOf("sehr geehrte", "dear ", "mit freundlichen grüßen", "kind regards", "sincerely", "betreff"),
    )

    fun kindOf(text: String): DocumentKind {
        val lower = text.lowercase()
        val scores = kinds.mapValues { (_, words) -> words.sumOf { w -> Regex(Regex.escape(w)).findAll(lower).count().coerceAtMost(3) } }
        val best = scores.maxByOrNull { it.value } ?: return DocumentKind.OTHER
        return if (best.value >= 2) best.key else DocumentKind.OTHER
    }

    private const val MONTHS_EN = "January|February|March|April|May|June|July|August|September|October|November|December"
    private const val MONTHS_DE = "Januar|Februar|März|Maerz|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember"
    val datePattern = Regex(
        """\b(\d{1,2}\.\s?\d{1,2}\.\s?(?:\d{4}|\d{2})|\d{4}-\d{2}-\d{2}|\d{1,2}/\d{1,2}/\d{4}|\d{1,2}\.?\s(?:$MONTHS_DE|$MONTHS_EN)\s\d{4}|(?:$MONTHS_EN)\s\d{1,2},\s\d{4})\b""",
    )
    private val amountPattern = Regex("""(?:(€|EUR|USD|\$|£|CHF)\s?)?(-?\d{1,3}(?:[.,\s]\d{3})*(?:[.,]\d{2}))(?:\s?(€|EUR|USD|\$|£|CHF))?""")
    private val ibanPattern = Regex("""\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?\b""")
    private val bicPattern = Regex("""(?i)(?:BIC|SWIFT)[:\s]*([A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)\b""")
    private val vatPattern = Regex("""\b(?:DE|AT|FR|NL|BE|IT|ES|PL|LU)\s?\d{8,11}\b|\bATU\d{8}\b""")
    private val emailPattern = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    private val invoiceNumberPattern = Regex("""(?i)(?:rechnungs-?\s?(?:nr|nummer)|invoice\s?(?:no|number|#)|beleg-?\s?nr)\.?\s*[:#]?\s*([A-Z0-9][A-Z0-9/\-]{2,})""")
    private val totalWords = Regex("""(?i)\b(total|gesamt\w*|summe|endbetrag|rechnungsbetrag|amount due|zu zahlen|zahlbetrag|netto|brutto|net pay|auszahlung\w*)\b""")
    private val dueWords = Regex("""(?i)(due|fällig|zahlbar bis|payable by|zahlungsziel|spätestens)""")

    fun extract(pages: List<String>): ExtractedData {
        val all = pages.joinToString("\n")
        val kind = kindOf(all)
        val fields = mutableListOf<ExtractedField>()
        fun add(name: String, value: String, page: Int) {
            if (fields.none { it.name == name && it.value == value }) fields += ExtractedField(name, value, page)
        }
        pages.forEachIndexed { page, text ->
            invoiceNumberPattern.findAll(text).forEach { add("Invoice number", it.groupValues[1], page) }
            for (line in text.lines()) {
                val dates = datePattern.findAll(line).map { it.value }.toList()
                if (dueWords.containsMatchIn(line)) dates.firstOrNull()?.let { add("Due date", it, page) }
                val amounts = amountPattern.findAll(line).filter { m -> m.groupValues[1].isNotEmpty() || m.groupValues[3].isNotEmpty() || totalWords.containsMatchIn(line) }
                    .filterNot { m -> datePattern.findAll(line).any { d -> d.range.first <= m.range.first && m.range.last <= d.range.last } }
                    .toList()
                val totalLabel = totalWords.find(line)
                if (totalLabel != null) amounts.lastOrNull()?.let { add(label(totalLabel.value), amountText(it), page) }
            }
            datePattern.findAll(text).forEach { add("Date", it.value, page) }
            ibanPattern.findAll(text).filter { Redactions.isValidIban(it.value) }.forEach { add("IBAN", it.value, page) }
            bicPattern.findAll(text).forEach { add("BIC", it.groupValues[1], page) }
            vatPattern.findAll(text).filterNot { m -> ibanPattern.findAll(text).any { it.range.first <= m.range.first && m.range.last <= it.range.last } }
                .forEach { add("VAT ID", it.value.replace(" ", ""), page) }
            emailPattern.findAll(text).forEach { add("Email", it.value, page) }
            Redactions.find(text, setOf(SensitiveKind.PHONE)).forEach { add("Phone", text.substring(it), page) }
        }
        return ExtractedData(kind, fields)
    }

    private fun label(word: String): String = when (word.lowercase()) {
        "netto" -> "Net amount"
        "brutto" -> "Gross amount"
        "net pay", "auszahlung", "auszahlungsbetrag" -> "Net pay"
        else -> "Total"
    }

    private fun amountText(m: MatchResult): String {
        val currency = m.groupValues[1].ifEmpty { m.groupValues[3] }
        return if (currency.isEmpty()) m.groupValues[2] else "${m.groupValues[2]} $currency".trim()
    }
}

/** A clause worth reading, with an optional warning why it stands out. */
data class ClauseFinding(val category: String, val text: String, val page: Int, val warning: String?)

/**
 * Points out key clauses of a contract (termination, liability, payment, renewal, penalties,
 * confidentiality, jurisdiction, duration) and flags unusual ones. Assistance only, not legal
 * advice.
 */
object ContractReview {

    // Specific kinds first: a sentence counts for the first category it matches.
    private val categories = linkedMapOf(
        "Automatic renewal" to Regex("""(?i)verlängert sich (?:jeweils )?(?:automatisch|stillschweigend)|automatic(?:ally)? renew|auto-renew|renews automatically"""),
        "Penalty" to Regex("""(?i)vertragsstrafe|penalty|liquidated damages"""),
        "Termination" to Regex("""(?i)kündig|termination|terminate|notice period|cancel"""),
        "Liability" to Regex("""(?i)haft(?:ung|et|en)|liabilit|liable|indemnif|schadensersatz|damages"""),
        "Confidentiality" to Regex("""(?i)vertraulich|geheimhaltung|confidential|non-disclosure"""),
        "Jurisdiction" to Regex("""(?i)gerichtsstand|governing law|jurisdiction|anwendbares recht"""),
        "Duration" to Regex("""(?i)laufzeit|vertragsdauer|term of (?:this|the) (?:agreement|contract)|mindestlaufzeit|minimum term"""),
        "Payment" to Regex("""(?i)zahlung|zahlbar|vergütung|payment|payable|fee|invoice|fällig"""),
    )

    private val months = Regex("""(?i)(\d+|drei|vier|fünf|sechs|zwölf|three|four|five|six|twelve)\s*(monat|month)""")
    private val numberWords = mapOf("drei" to 3, "vier" to 4, "fünf" to 5, "sechs" to 6, "zwölf" to 12, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "twelve" to 12)

    fun review(pages: List<String>): List<ClauseFinding> {
        val findings = mutableListOf<ClauseFinding>()
        for (s in Sentences.split(pages)) {
            for ((category, pattern) in categories) {
                if (!pattern.containsMatchIn(s.text)) continue
                findings += ClauseFinding(category, s.text, s.page, warningFor(category, s.text))
                break
            }
        }
        return findings
    }

    private fun warningFor(category: String, text: String): String? {
        val lower = text.lowercase()
        return when (category) {
            "Automatic renewal" -> "Renews automatically unless cancelled in time"
            "Termination" -> months.findAll(text).mapNotNull { m -> m.groupValues[1].toIntOrNull() ?: numberWords[m.groupValues[1].lowercase()] }
                .maxOrNull()?.takeIf { it > 3 }?.let { "Long notice period: $it months" }
            "Liability" -> when {
                "unbegrenzt" in lower || "unlimited" in lower || "unbeschränkt" in lower -> "Unlimited liability"
                "ausgeschlossen" in lower || "excluded" in lower || "no liability" in lower -> "Liability is excluded or limited"
                else -> null
            }
            "Penalty" -> "Contains a contractual penalty"
            "Duration" -> months.findAll(text).mapNotNull { m -> m.groupValues[1].toIntOrNull() ?: numberWords[m.groupValues[1].lowercase()] }
                .maxOrNull()?.takeIf { it >= 24 }?.let { "Long minimum term: $it months" }
            else -> null
        }
    }
}

/** A line of text with its font size, for finding headings. */
data class StyledLine(val text: String, val page: Int, val top: Float, val fontSize: Float, val bold: Boolean = false)

/** A bookmark to create: [level] 1 is top level. */
data class OutlineEntry(val title: String, val page: Int, val top: Float, val level: Int)

/** Builds a table of contents for documents without one, from heading sizes and numbering. */
object AutoOutline {

    private val numbered = Regex("""^(\d+(?:\.\d+){0,3})\.?\s+\p{L}""")

    fun build(lines: List<StyledLine>, pageCount: Int): List<OutlineEntry> {
        if (lines.isEmpty()) return emptyList()
        // Body text size: the size most characters are set in.
        val bySize = lines.groupBy { round(it.fontSize) }.mapValues { (_, l) -> l.sumOf { it.text.length } }
        val body = bySize.maxByOrNull { it.value }!!.key
        // Running headers and footers repeat on many pages.
        val repeated = lines.groupBy { it.text.trim().lowercase().replace(Regex("\\d+"), "#") }
            .filterValues { group -> group.map { it.page }.distinct().size >= maxOf(3, pageCount / 3) }.keys
        val candidates = lines.filter { l ->
            val t = l.text.trim()
            t.length in 2..90 && t.count { it.isLetter() } >= 2 && !t.endsWith(",") &&
                t.trim().lowercase().replace(Regex("\\d+"), "#") !in repeated &&
                (round(l.fontSize) >= body * 1.15f || (l.bold && numbered.containsMatchIn(t)) || (numbered.containsMatchIn(t) && round(l.fontSize) > body))
        }
        if (candidates.isEmpty()) return emptyList()
        val sizes = candidates.map { round(it.fontSize) }.distinct().sortedDescending().take(3)
        val result = mutableListOf<OutlineEntry>()
        for (l in candidates) {
            val t = l.text.trim()
            val number = numbered.find(t)?.groupValues?.get(1)
            val level = when {
                number != null -> number.count { it == '.' } + 1
                else -> (sizes.indexOf(round(l.fontSize)).takeIf { it >= 0 } ?: 2) + 1
            }.coerceIn(1, 3)
            // Headings broken over two lines become one.
            val previous = result.lastOrNull()
            if (previous != null && previous.page == l.page && previous.level == level && l.top - previous.top < l.fontSize * 1.6f && number == null) {
                result[result.lastIndex] = previous.copy(title = previous.title + " " + t)
            } else {
                result += OutlineEntry(t, l.page, l.top, level)
            }
        }
        // Too many "headings" means the sizes didn't tell headings apart.
        return if (result.size > pageCount * 12 + 20) emptyList() else result
    }

    private fun round(size: Float) = Math.round(size * 2) / 2f
}
