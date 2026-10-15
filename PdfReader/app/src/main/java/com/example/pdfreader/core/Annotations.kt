package com.example.pdfreader.core

/** A point in page coordinates: PDF points, top-left origin, unrotated. */
data class Pt(val x: Float, val y: Float)

/** A rectangle in page coordinates. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float, slop: Float = 0f) =
        x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
    fun union(other: Box) = Box(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
    fun offset(dx: Float, dy: Float) = Box(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        fun around(points: List<Pt>, pad: Float = 0f): Box = Box(
            points.minOf { it.x } - pad, points.minOf { it.y } - pad,
            points.maxOf { it.x } + pad, points.maxOf { it.y } + pad,
        )
    }
}

enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY }

enum class ShapeKind { RECTANGLE, ELLIPSE, LINE, ARROW, POLYGON }

/** Built-in stamps; CUSTOM uses the stamp's own text. */
enum class StampKind(val label: String) {
    APPROVED("APPROVED"), DRAFT("DRAFT"), CONFIDENTIAL("CONFIDENTIAL"), PAID("PAID"), CUSTOM("")
}

/**
 * An annotation on a page. Colors are ARGB ints. [id] is stable across saves (the PDF /NM
 * entry), [note] is the comment shown in its pop-up.
 */
sealed class Annotation {
    abstract val id: String
    abstract val page: Int
    abstract val color: Int
    abstract val author: String
    abstract val modified: Long
    abstract val note: String
    abstract val bounds: Box

    abstract fun withColor(color: Int): Annotation
    abstract fun withNote(note: String): Annotation
    abstract fun moved(dx: Float, dy: Float): Annotation

    /** Highlight, underline, strikeout or squiggly over text; one [Box] per line. */
    data class Markup(
        override val id: String, override val page: Int, override val color: Int,
        override val author: String, override val modified: Long, override val note: String = "",
        val kind: MarkupKind, val lines: List<Box>, val text: String = "",
    ) : Annotation() {
        override val bounds get() = lines.reduce(Box::union)
        override fun withColor(color: Int) = copy(color = color)
        override fun withNote(note: String) = copy(note = note)
        override fun moved(dx: Float, dy: Float) = this // tied to the text underneath
    }

    /** A sticky note icon at [at]. */
    data class Note(
        override val id: String, override val page: Int, override val color: Int,
        override val author: String, override val modified: Long, override val note: String,
        val at: Pt,
    ) : Annotation() {
        override val bounds get() = Box(at.x, at.y, at.x + ICON_SIZE, at.y + ICON_SIZE)
        override fun withColor(color: Int) = copy(color = color)
        override fun withNote(note: String) = copy(note = note)
        override fun moved(dx: Float, dy: Float) = copy(at = Pt(at.x + dx, at.y + dy))

        companion object {
            const val ICON_SIZE = 20f
        }
    }

    /** A text box. */
    data class FreeText(
        override val id: String, override val page: Int, override val color: Int,
        override val author: String, override val modified: Long, override val note: String = "",
        val box: Box, val text: String, val fontSize: Float = 12f,
    ) : Annotation() {
        override val bounds get() = box
        override fun withColor(color: Int) = copy(color = color)
        override fun withNote(note: String) = copy(note = note)
        override fun moved(dx: Float, dy: Float) = copy(box = box.offset(dx, dy))
    }

    /** Freehand strokes (pen or highlighter). [opacity] < 1 for the highlighter. */
    data class Ink(
        override val id: String, override val page: Int, override val color: Int,
        override val author: String, override val modified: Long, override val note: String = "",
        val strokes: List<List<Pt>>, val width: Float, val opacity: Float = 1f,
    ) : Annotation() {
        override val bounds get() = Box.around(strokes.flatten(), width)
        override fun withColor(color: Int) = copy(color = color)
        override fun withNote(note: String) = copy(note = note)
        override fun moved(dx: Float, dy: Float) = copy(strokes = strokes.map { s -> s.map { Pt(it.x + dx, it.y + dy) } })
    }

    /** Rectangle, ellipse (both inside [points] 0..1), line, arrow (2 points) or polygon (≥3). */
    data class Shape(
        override val id: String, override val page: Int, override val color: Int,
        override val author: String, override val modified: Long, override val note: String = "",
        val kind: ShapeKind, val points: List<Pt>, val width: Float = 2f, val fill: Int? = null, val opacity: Float = 1f,
    ) : Annotation() {
        override val bounds get() = Box.around(points, width)
        override fun withColor(color: Int) = copy(color = color)
        override fun withNote(note: String) = copy(note = note)
        override fun moved(dx: Float, dy: Float) = copy(points = points.map { Pt(it.x + dx, it.y + dy) })
    }

    /** A stamp such as APPROVED, with a "date · user" line. */
    data class Stamp(
        override val id: String, override val page: Int, override val color: Int,
        override val author: String, override val modified: Long, override val note: String = "",
        val box: Box, val kind: StampKind, val label: String, val subtitle: String,
    ) : Annotation() {
        override val bounds get() = box
        override fun withColor(color: Int) = copy(color = color)
        override fun withNote(note: String) = copy(note = note)
        override fun moved(dx: Float, dy: Float) = copy(box = box.offset(dx, dy))
    }

    /** A picture placed on the page (logo, scanned signature); [imagePath] is a private copy. */
    data class Image(
        override val id: String, override val page: Int, override val color: Int,
        override val author: String, override val modified: Long, override val note: String = "",
        val box: Box, val imagePath: String,
    ) : Annotation() {
        override val bounds get() = box
        override fun withColor(color: Int) = this
        override fun withNote(note: String) = copy(note = note)
        override fun moved(dx: Float, dy: Float) = copy(box = box.offset(dx, dy))
    }
}

/** Human-readable type names for the annotation panel and filters. */
fun Annotation.typeName(): String = when (this) {
    is Annotation.Markup -> when (kind) {
        MarkupKind.HIGHLIGHT -> "Highlight"
        MarkupKind.UNDERLINE -> "Underline"
        MarkupKind.STRIKEOUT -> "Strikethrough"
        MarkupKind.SQUIGGLY -> "Squiggly"
    }
    is Annotation.Note -> "Note"
    is Annotation.FreeText -> "Text box"
    is Annotation.Ink -> if (opacity < 1f) "Highlighter" else "Drawing"
    is Annotation.Shape -> kind.name.lowercase().replaceFirstChar { it.uppercase() }
    is Annotation.Stamp -> "Stamp"
    is Annotation.Image -> "Image"
}

/**
 * The annotations of one document with full undo/redo. Every change is a snapshot of the
 * whole list, which keeps undo simple and exact.
 */
class AnnotationHistory(initial: List<Annotation> = emptyList()) {

    var items: List<Annotation> = initial
        private set

    private val undoStack = ArrayDeque<List<Annotation>>()
    private val redoStack = ArrayDeque<List<Annotation>>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** Changed since loading or the last save. */
    var isModified = false
        private set

    private fun commit(newItems: List<Annotation>) {
        if (newItems == items) return
        undoStack.addLast(items)
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        items = newItems
        isModified = true
    }

    fun add(annotation: Annotation) = commit(items + annotation)

    fun replace(annotation: Annotation) = commit(items.map { if (it.id == annotation.id) annotation else it })

    fun remove(id: String) = commit(items.filter { it.id != id })

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(items)
        items = previous
        isModified = true
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(items)
        items = next
        isModified = true
    }

    fun markSaved() {
        isModified = false
    }

    fun onPage(page: Int) = items.filter { it.page == page }

    fun find(id: String) = items.firstOrNull { it.id == id }

    /** The topmost annotation at a point (last drawn wins), within [slop] points. */
    fun hit(page: Int, x: Float, y: Float, slop: Float = 4f): Annotation? =
        items.lastOrNull { it.page == page && hitTest(it, x, y, slop) }

    private fun hitTest(a: Annotation, x: Float, y: Float, slop: Float): Boolean = when (a) {
        is Annotation.Markup -> a.lines.any { it.contains(x, y, slop) }
        is Annotation.Ink -> a.strokes.any { stroke -> stroke.zipWithNext().any { (p, q) -> Geometry.distanceToSegment(x, y, p, q) <= a.width / 2 + slop } || stroke.size == 1 && Geometry.distance(x, y, stroke[0]) <= a.width + slop }
        is Annotation.Shape -> when (a.kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> Geometry.distanceToSegment(x, y, a.points[0], a.points[1]) <= a.width / 2 + slop
            else -> a.bounds.contains(x, y, slop)
        }
        else -> a.bounds.contains(x, y, slop)
    }

    private companion object {
        const val MAX_HISTORY = 200
    }
}

object Geometry {
    fun distance(x: Float, y: Float, p: Pt) = kotlin.math.hypot(x - p.x, y - p.y)

    fun distanceToSegment(x: Float, y: Float, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0f) return distance(x, y, a)
        val t = (((x - a.x) * dx + (y - a.y) * dy) / lengthSq).coerceIn(0f, 1f)
        return kotlin.math.hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }

    /**
     * Smooths a freehand stroke and drops points closer than [minDistance]: a light moving
     * average that removes finger jitter while keeping corners recognisable.
     */
    fun smooth(points: List<Pt>, minDistance: Float = 0.75f): List<Pt> {
        if (points.size < 3) return points
        val thinned = mutableListOf(points.first())
        for (p in points.drop(1)) if (distance(p.x, p.y, thinned.last()) >= minDistance) thinned += p
        if (thinned.last() != points.last()) thinned += points.last()
        if (thinned.size < 3) return thinned
        return listOf(thinned.first()) + thinned.windowed(3).map { (a, b, c) ->
            Pt((a.x + 2 * b.x + c.x) / 4, (a.y + 2 * b.y + c.y) / 4)
        } + thinned.last()
    }

    /** Arrow head points for an arrow from [from] to [to], [size] long. */
    fun arrowHead(from: Pt, to: Pt, size: Float): Pair<Pt, Pt> {
        val angle = kotlin.math.atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
        val spread = Math.toRadians(25.0)
        fun wing(a: Double) = Pt((to.x - size * kotlin.math.cos(a)).toFloat(), (to.y - size * kotlin.math.sin(a)).toFloat())
        return wing(angle - spread) to wing(angle + spread)
    }
}
