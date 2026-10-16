package com.example.pdfreader.core

/** A run of words that differs between two versions of a document. */
data class Change(val kind: Kind, val text: String, val oldPage: Int, val newPage: Int) {
    enum class Kind { ADDED, REMOVED, CHANGED }

    /** For CHANGED: the old words; [text] holds the new ones. */
    var before: String = ""
        internal set
}

/** Compares the text of two documents word by word (longest common subsequence). */
object Compare {

    private data class Word(val text: String, val page: Int)

    private fun words(pages: List<String>) = pages.flatMapIndexed { page, text ->
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { Word(it, page) }
    }

    /** Changes from [old] to [new], in reading order. Neighbouring removals and additions become one change. */
    fun diff(old: List<String>, new: List<String>): List<Change> {
        val a = words(old)
        val b = words(new)
        // Skip the common start and end; diff only the middle (keeps big documents fast).
        var start = 0
        while (start < a.size && start < b.size && a[start].text == b[start].text) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1].text == b[endB - 1].text) {
            endA--
            endB--
        }
        val x = a.subList(start, endA)
        val y = b.subList(start, endB)
        val ops = if (x.size.toLong() * y.size > 25_000_000L) coarse(x, y) else lcs(x, y)
        // Group consecutive edits.
        val changes = mutableListOf<Change>()
        var i = 0
        while (i < ops.size) {
            if (ops[i].first == ' ') {
                i++
                continue
            }
            val removed = mutableListOf<Word>()
            val added = mutableListOf<Word>()
            while (i < ops.size && ops[i].first != ' ') {
                if (ops[i].first == '-') removed += ops[i].second else added += ops[i].second
                i++
            }
            val oldPage = removed.firstOrNull()?.page ?: nearestPage(ops, i, a.getOrNull(start)?.page ?: 0, old = true)
            val newPage = added.firstOrNull()?.page ?: nearestPage(ops, i, b.getOrNull(start)?.page ?: 0, old = false)
            changes += when {
                removed.isEmpty() -> Change(Change.Kind.ADDED, added.joinToString(" ") { it.text }, oldPage, newPage)
                added.isEmpty() -> Change(Change.Kind.REMOVED, removed.joinToString(" ") { it.text }, oldPage, newPage)
                else -> Change(Change.Kind.CHANGED, added.joinToString(" ") { it.text }, oldPage, newPage).also { c ->
                    c.before = removed.joinToString(" ") { it.text }
                }
            }
        }
        return changes
    }

    private fun nearestPage(ops: List<Pair<Char, Word>>, from: Int, fallback: Int, old: Boolean): Int {
        // Words kept (' ') carry the old page; good enough for placing an insertion.
        for (k in from - 1 downTo 0) if (ops[k].first == ' ' || ops[k].first == (if (old) '-' else '+')) return ops[k].second.page
        return fallback
    }

    private fun lcs(x: List<Word>, y: List<Word>): List<Pair<Char, Word>> {
        val n = x.size
        val m = y.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            table[i][j] = if (x[i].text == y[j].text) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
        }
        val ops = mutableListOf<Pair<Char, Word>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                x[i].text == y[j].text -> { ops += ' ' to x[i]; i++; j++ }
                table[i + 1][j] >= table[i][j + 1] -> { ops += '-' to x[i]; i++ }
                else -> { ops += '+' to y[j]; j++ }
            }
        }
        while (i < n) ops += '-' to x[i++]
        while (j < m) ops += '+' to y[j++]
        return ops
    }

    /** For very long differing middles: compare line-sized chunks instead of words. */
    private fun coarse(x: List<Word>, y: List<Word>): List<Pair<Char, Word>> {
        fun chunks(w: List<Word>) = w.chunked(12).map { c -> Word(c.joinToString(" ") { it.text }, c.first().page) }
        return lcs(chunks(x), chunks(y))
    }

    /** A plain-text report of [changes]. */
    fun report(oldName: String, newName: String, changes: List<Change>): String = buildString {
        append("Comparison: ").append(oldName).append(" → ").append(newName).append('\n')
        append(changes.size).append(if (changes.size == 1) " change\n\n" else " changes\n\n")
        for (c in changes) {
            when (c.kind) {
                Change.Kind.ADDED -> append("+ (p. ").append(c.newPage + 1).append(") ").append(c.text)
                Change.Kind.REMOVED -> append("− (p. ").append(c.oldPage + 1).append(") ").append(c.text)
                Change.Kind.CHANGED -> append("~ (p. ").append(c.newPage + 1).append(") “").append(c.before).append("” → “").append(c.text).append('”')
            }
            append('\n')
        }
    }
}
