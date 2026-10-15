package com.example.pdfreader.core

/** Moves and scales annotations by mapping one box onto another. */
object AnnotationTransform {

    /** Smallest size an annotation can be resized to, in points. */
    const val MIN_SIZE = 8f

    /** Whether an annotation can be resized (text markup follows its text; notes are icons). */
    fun canResize(a: Annotation) = a !is Annotation.Markup && a !is Annotation.Note

    /** [a] with its [Annotation.bounds] mapped onto [to]; non-resizable ones are only moved. */
    fun resized(a: Annotation, to: Box): Annotation {
        val from = a.bounds
        if (!canResize(a)) return a.moved(to.left - from.left, to.top - from.top)
        val target = Box(to.left, to.top, maxOf(to.right, to.left + MIN_SIZE), maxOf(to.bottom, to.top + MIN_SIZE))
        val sx = target.width / from.width.coerceAtLeast(0.001f)
        val sy = target.height / from.height.coerceAtLeast(0.001f)
        fun pt(p: Pt) = Pt(target.left + (p.x - from.left) * sx, target.top + (p.y - from.top) * sy)
        fun box(b: Box) = Box(target.left + (b.left - from.left) * sx, target.top + (b.top - from.top) * sy, target.left + (b.right - from.left) * sx, target.top + (b.bottom - from.top) * sy)
        return when (a) {
            is Annotation.FreeText -> a.copy(box = box(a.box))
            is Annotation.Stamp -> a.copy(box = box(a.box))
            is Annotation.Image -> a.copy(box = box(a.box))
            is Annotation.Shape -> a.copy(points = a.points.map(::pt))
            is Annotation.Ink -> a.copy(strokes = a.strokes.map { it.map(::pt) })
            else -> a
        }
    }

    /** A box from two corner points in any order. */
    fun boxOf(a: Pt, b: Pt) = Box(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
}

/** What kind of pointer produced a touch, for palm rejection. */
enum class PointerKind { FINGER, STYLUS, STYLUS_ERASER, MOUSE }

/**
 * Decides which touches draw. Once a stylus has been used, fingers (and palms resting on the
 * screen) no longer draw — they scroll and zoom instead — unless finger drawing is forced on.
 */
class PalmRejection {
    var stylusSeen = false
        private set

    /** Draw with fingers even after a stylus was used. */
    var fingerDrawing = false

    fun draws(kind: PointerKind): Boolean {
        if (kind == PointerKind.STYLUS || kind == PointerKind.STYLUS_ERASER) stylusSeen = true
        return when (kind) {
            PointerKind.STYLUS, PointerKind.STYLUS_ERASER, PointerKind.MOUSE -> true
            PointerKind.FINGER -> fingerDrawing || !stylusSeen
        }
    }
}

/** Stroke width from pen pressure: light strokes are thinner, firm ones thicker. */
object PenPressure {
    /** [base] scaled by the average pressure (0..1, 1 = normal), clamped to 0.4x–1.8x. */
    fun width(base: Float, pressures: List<Float>): Float {
        if (pressures.isEmpty()) return base
        val average = pressures.average().toFloat()
        // Devices without pressure report a constant 1.0: keep the chosen width.
        if (pressures.all { it == pressures[0] } && (average == 1f || average == 0f)) return base
        return base * (0.4f + 1.4f * average.coerceIn(0f, 1f)).coerceIn(0.4f, 1.8f)
    }
}

/** Which annotations the panel shows. Empty sets mean "all". */
data class AnnotationFilter(val types: Set<String> = emptySet(), val authors: Set<String> = emptySet()) {
    fun matches(a: Annotation) = (types.isEmpty() || a.typeName() in types) && (authors.isEmpty() || a.author in authors)
    fun apply(items: List<Annotation>) = items.filter(::matches)
}

/** Ordering and plain-text export of a document's annotations. */
object AnnotationSummary {

    /** Reading order: by page, then top to bottom, then left to right. */
    fun sorted(items: List<Annotation>) = items.sortedWith(compareBy({ it.page }, { it.bounds.top }, { it.bounds.left }))

    /** The text an annotation is about: the marked-up text, the box's text or the stamp. */
    fun content(a: Annotation): String = when (a) {
        is Annotation.Markup -> a.text
        is Annotation.FreeText -> a.text
        is Annotation.Stamp -> a.label
        else -> ""
    }.trim()

    /**
     * A readable summary, e.g. to share or paste into notes:
     * the document name, then per page each annotation's type, quoted text and comment.
     */
    fun text(documentName: String, items: List<Annotation>, formatDate: (Long) -> String): String = buildString {
        append("Annotations — ").append(documentName).append('\n')
        append(items.size).append(if (items.size == 1) " annotation" else " annotations").append('\n')
        var page = -1
        for (a in sorted(items)) {
            if (a.page != page) {
                page = a.page
                append("\nPage ").append(page + 1).append('\n')
            }
            append("• ").append(a.typeName())
            val by = listOfNotNull(a.author.takeIf { it.isNotBlank() }, a.modified.takeIf { it > 0 }?.let(formatDate))
            if (by.isNotEmpty()) append(" (").append(by.joinToString(", ")).append(')')
            val content = content(a)
            if (content.isNotEmpty()) append(": “").append(content.replace('\n', ' ')).append('”')
            append('\n')
            if (a.note.isNotBlank()) append("    ").append(a.note.trim().replace("\n", "\n    ")).append('\n')
        }
    }
}
