package com.example.pdfreader.core

/** Paper sizes offered for new blank pages, in PDF points. */
enum class BlankSize(val width: Float, val height: Float) {
    A4(595.28f, 841.89f),
    LETTER(612f, 792f),
}

/**
 * One page of the document being organized: page [page] of source document [source]
 * (0 = the open file, others = inserted files), turned by an extra [rotation] degrees, or a
 * new blank page of [blankWidth]×[blankHeight] points when [page] is -1.
 */
data class PageRef(
    val source: Int,
    val page: Int,
    val rotation: Int = 0,
    val blankWidth: Float = 0f,
    val blankHeight: Float = 0f,
) {
    val isBlank: Boolean get() = page < 0

    companion object {
        fun blank(width: Float, height: Float) = PageRef(source = -1, page = -1, blankWidth = width, blankHeight = height)
    }
}

/**
 * The page list of the organizer with undo/redo. Positions are indices into [pages];
 * selections are sets of positions.
 */
class PageListEditor(initial: List<PageRef>) {

    var pages: List<PageRef> = initial
        private set

    private val undoStack = ArrayDeque<List<PageRef>>()
    private val redoStack = ArrayDeque<List<PageRef>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** True when the list differs from how it started (or was last marked saved). */
    var isModified = false
        private set

    private fun change(newPages: List<PageRef>) {
        if (newPages == pages) return
        undoStack.addLast(pages)
        redoStack.clear()
        pages = newPages
        isModified = true
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(pages)
        pages = previous
        isModified = true
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(pages)
        pages = next
        isModified = true
    }

    fun markSaved() {
        isModified = false
    }

    /**
     * Moves the selected pages, keeping their order, so the first of them lands at [target]
     * in the resulting list (clamped to the valid range).
     */
    fun move(selection: Set<Int>, target: Int) {
        val chosen = selection.filter { it in pages.indices }.sorted()
        if (chosen.isEmpty()) return
        val moving = chosen.map { pages[it] }
        val rest = pages.filterIndexed { i, _ -> i !in selection }
        val at = target.coerceIn(0, rest.size)
        change(rest.take(at) + moving + rest.drop(at))
    }

    fun rotate(selection: Set<Int>, degrees: Int) =
        change(pages.mapIndexed { i, ref -> if (i in selection) ref.copy(rotation = PageRotation.normalize(ref.rotation + degrees)) else ref })

    fun delete(selection: Set<Int>) = change(pages.filterIndexed { i, _ -> i !in selection })

    /** Inserts a copy of each selected page right after it. */
    fun duplicate(selection: Set<Int>) =
        change(pages.flatMapIndexed { i, ref -> if (i in selection) listOf(ref, ref) else listOf(ref) })

    fun insert(position: Int, refs: List<PageRef>) {
        if (refs.isEmpty()) return
        val at = position.coerceIn(0, pages.size)
        change(pages.take(at) + refs + pages.drop(at))
    }

    /** The selected pages in document order, e.g. to extract them. */
    fun selected(selection: Set<Int>): List<PageRef> = pages.filterIndexed { i, _ -> i in selection }
}
