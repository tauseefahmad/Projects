package com.example.pdfreader.core

import kotlin.math.ln

/** A sentence and the page (0-based) it is on. */
data class Sentence(val text: String, val page: Int, val index: Int)

/** Splits page texts into sentences, keeping common abbreviations (z. B., e.g., Dr., Nr. …) together. */
object Sentences {

    private val abbreviations = setOf(
        "z", "b", "bzw", "ca", "dr", "prof", "nr", "str", "abs", "art", "ggf", "inkl", "usw", "vgl", "etc", "e", "g", "i", "e.g", "i.e",
        "mr", "mrs", "ms", "no", "vs", "fig", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec", "st", "u", "a", "s", "d", "h",
    )

    fun split(pages: List<String>): List<Sentence> {
        val result = mutableListOf<Sentence>()
        pages.forEachIndexed { page, text ->
            for (paragraph in TextExport.paragraphs(text)) {
                val current = StringBuilder()
                val words = paragraph.split(Regex("\\s+")).filter { it.isNotEmpty() }
                for ((i, word) in words.withIndex()) {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(word)
                    val next = words.getOrNull(i + 1)
                    if (endsSentence(word, next)) {
                        result += Sentence(current.toString(), page, result.size)
                        current.clear()
                    }
                }
                if (current.isNotBlank()) result += Sentence(current.toString(), page, result.size)
            }
        }
        return result
    }

    private fun endsSentence(word: String, next: String?): Boolean {
        val last = word.trimEnd('"', '“', '”', '»', '«', ')', '\'').lastOrNull() ?: return false
        if (last == '!' || last == '?') return true
        if (last != '.') return false
        val stem = word.trimEnd('.').trimStart('(', '"', '„').lowercase()
        if (stem in abbreviations) return false
        // "1." "12." as in dates and lists, single letters as in initials.
        if ((stem.all { it.isDigit() } && stem.length <= 2) || stem.length == 1) return false
        return next == null || next.first().isUpperCase() || next.first().isDigit() || next.first() in "\"„“("
    }
}

/** Words for ranking: lower case, without stop words, with a light English/German stemming. */
object Terms {

    private val stop = setOf(
        // English
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were", "be", "been", "by", "with", "as", "at",
        "this", "that", "it", "its", "from", "which", "will", "shall", "can", "may", "not", "no", "if", "any", "all", "has", "have", "had",
        "their", "they", "we", "you", "your", "our", "he", "she", "his", "her", "there", "these", "those", "such", "than", "then", "also",
        "what", "when", "where", "who", "how", "does", "do", "is", "into", "about", "per",
        // German
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "eines", "einem", "einen", "und", "oder", "zu", "im", "in", "am",
        "an", "auf", "für", "mit", "von", "vom", "zum", "zur", "ist", "sind", "war", "wird", "werden", "wurde", "nicht", "kein", "keine",
        "als", "auch", "bei", "nach", "aus", "es", "er", "sie", "wir", "ihr", "ihre", "ihren", "sich", "dass", "wenn", "so", "wie", "was",
        "noch", "nur", "über", "unter", "durch", "bis", "dieser", "diese", "dieses", "welche", "welcher", "kann", "muss", "soll", "hat", "haben",
    )

    fun of(text: String): List<String> = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase())
        .map { it.value }
        .filter { it.length > 1 && it !in stop && !it.all(Char::isDigit) }
        .map(::stem)
        .toList()

    fun stem(word: String): String {
        if (word.length <= 4) return word
        for (suffix in listOf("ungen", "ung", "ingly", "ing", "ies", "ed", "es", "en", "er", "em", "e", "s", "n")) {
            if (word.endsWith(suffix) && word.length - suffix.length >= 4) return word.dropLast(suffix.length)
        }
        return word
    }
}

/** How long a summary should be. */
enum class SummaryLength { PARAGRAPH, PAGE, BY_SECTION }

/**
 * Extractive summaries: the document's most representative sentences, in their original
 * order. Works offline; no text is invented.
 */
object Summarizer {

    fun summarize(pages: List<String>, length: SummaryLength): List<Sentence> {
        val sentences = Sentences.split(pages).filter { s -> s.text.length in 30..400 && s.text.count { it.isLetter() } > 20 }
        if (sentences.isEmpty()) return emptyList()
        val frequency = HashMap<String, Int>()
        for (s in sentences) for (t in Terms.of(s.text).toSet()) frequency[t] = (frequency[t] ?: 0) + 1
        val top = frequency.values.maxOrNull()?.toFloat() ?: 1f
        fun score(s: Sentence): Float {
            val terms = Terms.of(s.text)
            if (terms.isEmpty()) return 0f
            val content = terms.sumOf { (frequency[it] ?: 0).toDouble() / top }.toFloat() / terms.size.coerceAtLeast(8)
            // Opening sentences tend to say what a document is about.
            val position = if (s.index < 3) 0.15f else 0f
            return content + position
        }
        val scored = sentences.map { it to score(it) }
        return when (length) {
            SummaryLength.PARAGRAPH -> pick(scored, 3)
            SummaryLength.PAGE -> pick(scored, 12)
            SummaryLength.BY_SECTION -> scored.groupBy { it.first.page }.values.flatMap { pick(it, 2) }
        }
    }

    private fun pick(scored: List<Pair<Sentence, Float>>, count: Int): List<Sentence> {
        val chosen = mutableListOf<Sentence>()
        for ((s, _) in scored.sortedByDescending { it.second }) {
            if (chosen.size >= count) break
            // Skip near-duplicates (repeated headers, boilerplate).
            val terms = Terms.of(s.text).toSet()
            if (chosen.any { c -> overlap(terms, Terms.of(c.text).toSet()) > 0.7f }) continue
            chosen += s
        }
        return chosen.sortedBy { it.index }
    }

    private fun overlap(a: Set<String>, b: Set<String>): Float =
        if (a.isEmpty() || b.isEmpty()) 0f else a.intersect(b).size.toFloat() / minOf(a.size, b.size)

    fun markdown(title: String, sentences: List<Sentence>, length: SummaryLength): String = buildString {
        append("# Summary — ").append(title).append("\n\n")
        if (length == SummaryLength.BY_SECTION) {
            for ((page, list) in sentences.groupBy { it.page }) {
                append("## Page ").append(page + 1).append("\n\n")
                for (s in list) append("- ").append(s.text).append('\n')
                append('\n')
            }
        } else {
            for (s in sentences) append("- ").append(s.text).append(" *(p. ").append(s.page + 1).append(")*\n")
        }
    }.trimEnd() + "\n"
}

/** A passage that answers a question, with the page it is on. */
data class Passage(val text: String, val page: Int, val score: Float)

/** Finds the passages that best match a question (BM25 over two-sentence windows). */
object PassageSearch {

    fun search(pages: List<String>, question: String, limit: Int = 3): List<Passage> {
        val sentences = Sentences.split(pages)
        if (sentences.isEmpty()) return emptyList()
        // Windows of two sentences on the same page give an answer some context.
        val passages = sentences.mapIndexed { i, s ->
            val next = sentences.getOrNull(i + 1)?.takeIf { it.page == s.page }
            Sentence(if (next != null) s.text + " " + next.text else s.text, s.page, i)
        }
        val docs = passages.map { Terms.of(it.text) }
        val query = Terms.of(question).toSet()
        if (query.isEmpty()) return emptyList()
        val n = docs.size.toDouble()
        val avg = docs.sumOf { it.size }.toDouble() / n
        val df = query.associateWith { t -> docs.count { t in it } }
        val k1 = 1.4
        val b = 0.75
        val scored = passages.mapIndexed { i, p ->
            val terms = docs[i]
            var score = 0.0
            for (t in query) {
                val f = terms.count { it == t }
                if (f == 0) continue
                val idf = ln(1 + (n - df.getValue(t) + 0.5) / (df.getValue(t) + 0.5))
                score += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * terms.size / avg))
            }
            Passage(p.text, p.page, score.toFloat())
        }.filter { it.score > 0f }.sortedByDescending { it.score }
        // Overlapping windows: keep the best of neighbours.
        val result = mutableListOf<Passage>()
        for (p in scored) {
            if (result.size >= limit) break
            if (result.none { it.page == p.page && (it.text.contains(p.text.take(40)) || p.text.contains(it.text.take(40))) }) result += p
        }
        return result
    }
}
