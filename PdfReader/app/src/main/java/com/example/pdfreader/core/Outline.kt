package com.example.pdfreader.core

/** One entry of a PDF's table of contents. [page] is null when the entry has no usable target. */
data class OutlineNode(val title: String, val page: Int?, val children: List<OutlineNode> = emptyList())

/** A visible line of the outline panel. [id] is the node's path, e.g. "0/2/1". */
data class OutlineRow(val node: OutlineNode, val depth: Int, val id: String, val expanded: Boolean) {
    val hasChildren: Boolean get() = node.children.isNotEmpty()
}

object OutlineTree {

    /** Flattens the tree into rows, showing children only of nodes whose id is in [expanded]. */
    fun visibleRows(roots: List<OutlineNode>, expanded: Set<String>): List<OutlineRow> {
        val rows = mutableListOf<OutlineRow>()
        fun visit(nodes: List<OutlineNode>, depth: Int, prefix: String) {
            nodes.forEachIndexed { index, node ->
                val id = if (prefix.isEmpty()) "$index" else "$prefix/$index"
                val isExpanded = id in expanded && node.children.isNotEmpty()
                rows += OutlineRow(node, depth, id, isExpanded)
                if (isExpanded) visit(node.children, depth + 1, id)
            }
        }
        visit(roots, 0, "")
        return rows
    }

    /**
     * The id of the deepest entry at or before [page] — the section the reader is in — or null.
     * Entries are assumed to be in reading order, as they are in almost all PDFs.
     */
    fun sectionFor(roots: List<OutlineNode>, page: Int): String? {
        var best: String? = null
        fun visit(nodes: List<OutlineNode>, prefix: String) {
            nodes.forEachIndexed { index, node ->
                val id = if (prefix.isEmpty()) "$index" else "$prefix/$index"
                val target = node.page
                if (target != null && target <= page) best = id
                visit(node.children, id)
            }
        }
        visit(roots, "")
        return best
    }

    /** Ids of all ancestors of [id], so the section can be revealed. "0/2/1" → ["0", "0/2"]. */
    fun ancestors(id: String): List<String> {
        val parts = id.split('/')
        return (1 until parts.size).map { parts.take(it).joinToString("/") }
    }
}

/** A user's bookmark on a page. */
data class Bookmark(val page: Int, val title: String, val createdAt: Long)

object Bookmarks {

    /** Adds a bookmark for [page] or removes the existing one; the list stays sorted by page. */
    fun toggle(bookmarks: List<Bookmark>, page: Int, title: String, now: Long): List<Bookmark> =
        if (bookmarks.any { it.page == page }) {
            bookmarks.filter { it.page != page }
        } else {
            (bookmarks + Bookmark(page, title, now)).sortedBy { it.page }
        }

    fun rename(bookmarks: List<Bookmark>, page: Int, title: String): List<Bookmark> =
        bookmarks.map { if (it.page == page) it.copy(title = title.ifBlank { it.title }) else it }
}
