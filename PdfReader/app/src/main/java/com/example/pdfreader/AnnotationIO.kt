package com.example.pdfreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.pdfreader.core.Annotation
import com.example.pdfreader.core.Box
import com.example.pdfreader.core.Geometry
import com.example.pdfreader.core.MarkupKind
import com.example.pdfreader.core.Pt
import com.example.pdfreader.core.ShapeKind
import com.example.pdfreader.core.StampKind
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLine
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * Converts between [Annotation]s and real PDF annotation objects. Annotations are written as
 * standard types (Highlight, Ink, FreeText, Square, …) with their data entries and an
 * appearance stream drawn here, so Acrobat, Preview and browsers show them the same way and can
 * still edit them. Annotations this app doesn't model (links, form fields, foreign stamps…)
 * are never touched.
 */
object AnnotationIO {

    private val KEY_LABEL = COSName.getPDFName("PdfReaderLabel")
    private val KEY_SUBTITLE = COSName.getPDFName("PdfReaderSubtitle")
    private val KEY_IMAGE = COSName.getPDFName("PdfReaderImage")
    private val KEY_NOTE = COSName.getPDFName("PdfReaderNote")
    private val KEY_STAMP_KIND = COSName.getPDFName("PdfReaderStamp")
    private val KEY_TEXT = COSName.getPDFName("PdfReaderText")

    /** Maps page coordinates (top-left origin) to PDF space for one page. */
    private class Space(page: PDPage) {
        private val crop = page.cropBox
        fun x(px: Float) = crop.lowerLeftX + px
        fun y(py: Float) = crop.upperRightY - py
        fun pageX(x: Float) = x - crop.lowerLeftX
        fun pageY(y: Float) = crop.upperRightY - y
        fun rect(box: Box) = PDRectangle(x(box.left), y(box.bottom), box.width, box.height)
        fun box(rect: PDRectangle) = Box(pageX(rect.lowerLeftX), pageY(rect.upperRightY), pageX(rect.upperRightX), pageY(rect.lowerLeftY))
    }

    // --- Reading --------------------------------------------------------------------------------

    /** Whether this app models (and therefore may rewrite) an annotation. */
    fun isManaged(annotation: PDAnnotation): Boolean = when (annotation) {
        is PDAnnotationLink, is PDAnnotationWidget, is PDAnnotationPopup -> false
        is PDAnnotationTextMarkup, is PDAnnotationText, is PDAnnotationSquareCircle, is PDAnnotationLine -> true
        is PDAnnotationRubberStamp -> annotation.cosObject.containsKey(KEY_LABEL) || annotation.cosObject.containsKey(KEY_IMAGE)
        is PDAnnotationMarkup -> annotation.subtype in setOf(
            PDAnnotationMarkup.SUB_TYPE_INK, PDAnnotationMarkup.SUB_TYPE_FREETEXT, PDAnnotationMarkup.SUB_TYPE_POLYGON,
        )
        else -> false
    }

    /** Reads the managed annotations of every page; images are extracted into [imageDir]. */
    fun read(doc: PDDocument, imageDir: File): List<Annotation> {
        val result = mutableListOf<Annotation>()
        doc.pages.forEachIndexed { index, page ->
            val space = Space(page)
            for (annotation in page.annotations) {
                if (!isManaged(annotation)) continue
                runCatching { readOne(annotation, index, space, imageDir) }.getOrNull()?.let(result::add)
            }
        }
        return result
    }

    private fun readOne(a: PDAnnotation, page: Int, space: Space, imageDir: File): Annotation? {
        val id = a.annotationName?.takeIf { it.isNotBlank() } ?: newId()
        val color = a.color?.let { runCatching { 0xFF000000.toInt() or it.toRGB() }.getOrNull() } ?: 0xFFFFEB3B.toInt()
        val author = (a as? PDAnnotationMarkup)?.titlePopup.orEmpty()
        val modified = runCatching { com.tom_roush.pdfbox.util.DateConverter.toCalendar(a.modifiedDate)?.timeInMillis }.getOrNull() ?: 0L
        val note = a.contents.orEmpty()
        val rect = a.rectangle?.let(space::box) ?: Box(0f, 0f, 0f, 0f)
        val width = (a.cosObject.getDictionaryObject(COSName.BS) as? com.tom_roush.pdfbox.cos.COSDictionary)
            ?.getFloat(COSName.W, 1f) ?: 1f
        val opacity = (a as? PDAnnotationMarkup)?.constantOpacity ?: 1f
        return when (a) {
            is PDAnnotationTextMarkup -> {
                val quads = a.quadPoints ?: return null
                val lines = quads.toList().chunked(8).filter { it.size == 8 }.map { q ->
                    val xs = listOf(q[0], q[2], q[4], q[6])
                    val ys = listOf(q[1], q[3], q[5], q[7])
                    Box(space.pageX(xs.min()), space.pageY(ys.max()), space.pageX(xs.max()), space.pageY(ys.min()))
                }
                if (lines.isEmpty()) return null
                val kind = when (a.subtype) {
                    PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE -> MarkupKind.UNDERLINE
                    PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT -> MarkupKind.STRIKEOUT
                    PDAnnotationTextMarkup.SUB_TYPE_SQUIGGLY -> MarkupKind.SQUIGGLY
                    else -> MarkupKind.HIGHLIGHT
                }
                // Other apps show /Contents as the comment; when it only repeats the marked text, it isn't one.
                val text = a.cosObject.getString(KEY_TEXT).orEmpty()
                Annotation.Markup(id, page, color, author, modified, if (note == text) "" else note, kind, lines, text)
            }
            is PDAnnotationText -> Annotation.Note(id, page, color, author, modified, note, Pt(rect.left, rect.top))
            is PDAnnotationSquareCircle -> {
                val inset = width / 2
                val kind = if (a.subtype == PDAnnotationSquareCircle.SUB_TYPE_CIRCLE) ShapeKind.ELLIPSE else ShapeKind.RECTANGLE
                val fill = a.interiorColor?.let { runCatching { 0xFF000000.toInt() or it.toRGB() }.getOrNull() }
                Annotation.Shape(
                    id, page, color, author, modified, note, kind,
                    listOf(Pt(rect.left + inset, rect.top + inset), Pt(rect.right - inset, rect.bottom - inset)), width, fill, opacity,
                )
            }
            is PDAnnotationLine -> {
                val l = a.line ?: return null
                val arrow = a.endPointEndingStyle?.contains("Arrow") == true || a.startPointEndingStyle?.contains("Arrow") == true
                Annotation.Shape(
                    id, page, color, author, modified, note, if (arrow) ShapeKind.ARROW else ShapeKind.LINE,
                    listOf(Pt(space.pageX(l[0]), space.pageY(l[1])), Pt(space.pageX(l[2]), space.pageY(l[3]))), width, null, opacity,
                )
            }
            is PDAnnotationRubberStamp -> {
                val cos = a.cosObject
                if (cos.containsKey(KEY_IMAGE)) {
                    val image = (a.normalAppearanceStream?.resources?.let { res ->
                        res.xObjectNames.firstNotNullOfOrNull { res.getXObject(it) as? PDImageXObject }
                    }) ?: return null
                    val file = File(imageDir.apply { mkdirs() }, "$id.png")
                    file.outputStream().use { image.image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Annotation.Image(id, page, color, author, modified, note, rect, file.path)
                } else {
                    val kind = runCatching { StampKind.valueOf(cos.getString(KEY_STAMP_KIND) ?: "") }.getOrDefault(StampKind.CUSTOM)
                    Annotation.Stamp(id, page, color, author, modified, note, rect, kind, cos.getString(KEY_LABEL).orEmpty(), cos.getString(KEY_SUBTITLE).orEmpty())
                }
            }
            is PDAnnotationMarkup -> when (a.subtype) {
                PDAnnotationMarkup.SUB_TYPE_INK -> {
                    val list = a.cosObject.getDictionaryObject(COSName.INKLIST) as? COSArray ?: return null
                    val strokes = (0 until list.size()).mapNotNull { i ->
                        (list.getObject(i) as? COSArray)?.toFloatArray()?.toList()?.chunked(2)?.filter { it.size == 2 }
                            ?.map { (x, y) -> Pt(space.pageX(x), space.pageY(y)) }
                    }.filter { it.isNotEmpty() }
                    if (strokes.isEmpty()) return null
                    Annotation.Ink(id, page, color, author, modified, note, strokes, width, opacity)
                }
                PDAnnotationMarkup.SUB_TYPE_POLYGON -> {
                    val vertices = (a.cosObject.getDictionaryObject(COSName.VERTICES) as? COSArray)?.toFloatArray() ?: return null
                    val points = vertices.toList().chunked(2).filter { it.size == 2 }.map { (x, y) -> Pt(space.pageX(x), space.pageY(y)) }
                    if (points.size < 3) return null
                    val fill = a.interiorColor?.let { runCatching { 0xFF000000.toInt() or it.toRGB() }.getOrNull() }
                    Annotation.Shape(id, page, color, author, modified, note, ShapeKind.POLYGON, points, width, fill, opacity)
                }
                PDAnnotationMarkup.SUB_TYPE_FREETEXT -> {
                    val size = Regex("([0-9.]+)\\s+Tf").find(a.defaultAppearance.orEmpty())?.groupValues?.get(1)?.toFloatOrNull() ?: 12f
                    val textColor = Regex("([0-9.]+)\\s+([0-9.]+)\\s+([0-9.]+)\\s+rg").find(a.defaultAppearance.orEmpty())?.groupValues
                        ?.let { (_, r, g, b) -> argb(r.toFloat(), g.toFloat(), b.toFloat()) } ?: color
                    Annotation.FreeText(id, page, textColor, author, modified, a.cosObject.getString(KEY_NOTE).orEmpty(), rect, a.contents.orEmpty(), size)
                }
                else -> null
            }
            else -> null
        }
    }

    // --- Writing --------------------------------------------------------------------------------

    /**
     * Replaces the managed annotations of [doc] with [annotations]. Unmanaged annotations stay.
     * Images are read from their private files.
     */
    fun write(doc: PDDocument, annotations: List<Annotation>) {
        // New form fields are written by FormIO.create, as real fields.
        val byPage = annotations.filter { it !is Annotation.Field && it !is Annotation.Redaction }.groupBy { it.page }
        doc.pages.forEachIndexed { index, page ->
            val kept = page.annotations.filterNot(::isManaged).toMutableList()
            // Pop-ups of rewritten annotations would dangle; keep only those of untouched ones.
            kept.removeAll { it is PDAnnotationPopup && runCatching { it.parent?.let(::isManaged) }.getOrNull() != false }
            val space = Space(page)
            for (annotation in byPage[index].orEmpty()) {
                runCatching { toPdf(doc, page, space, annotation) }.getOrNull()?.let(kept::add)
            }
            page.annotations = kept
        }
    }

    /** Removes the managed annotations, e.g. to render pages while they are drawn by the app instead. */
    fun stripManaged(doc: PDDocument) {
        for (page in doc.pages) page.annotations = page.annotations.filterNot(::isManaged)
    }

    /** Draws every annotation's appearance into the page content and removes the annotations. */
    fun flatten(doc: PDDocument) {
        for (page in doc.pages) flattenPage(doc, page, widgets = false)
    }

    /**
     * Draws [redactions] as black boxes into their pages, after drawing in every annotation
     * and form field on those pages (so that the page, turned into a picture afterwards,
     * still shows them). Returns the redacted pages.
     */
    fun burnRedactions(doc: PDDocument, redactions: List<Annotation.Redaction>): Set<Int> {
        val pages = redactions.map { it.page }.filter { it in 0 until doc.numberOfPages }.toSet()
        for (index in pages) {
            val page = doc.getPage(index)
            flattenPage(doc, page, widgets = true)
            val space = Space(page)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { s ->
                s.setNonStrokingColor(0f, 0f, 0f)
                for (r in redactions.filter { it.page == index }) {
                    val rect = space.rect(r.box)
                    s.addRect(rect.lowerLeftX, rect.lowerLeftY, rect.width, rect.height)
                }
                s.fill()
            }
        }
        return pages
    }

    /** Draws the annotations of [page] into its content; links (and widgets unless [widgets]) stay. */
    private fun flattenPage(doc: PDDocument, page: PDPage, widgets: Boolean) {
        run {
            val remaining = mutableListOf<PDAnnotation>()
            for (annotation in page.annotations) {
                val appearance = annotation.normalAppearanceStream
                if (annotation is PDAnnotationLink || (!widgets && annotation is PDAnnotationWidget) || appearance == null || annotation.isHidden) {
                    if (annotation !is PDAnnotationPopup) remaining += annotation
                    continue
                }
                val rect = annotation.rectangle ?: continue
                val box = appearance.bBox ?: rect
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    stream.saveGraphicsState()
                    // Map the appearance's bounding box onto the annotation rectangle.
                    val sx = rect.width / box.width.coerceAtLeast(0.001f)
                    val sy = rect.height / box.height.coerceAtLeast(0.001f)
                    stream.transform(Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - box.lowerLeftX * sx, rect.lowerLeftY - box.lowerLeftY * sy))
                    stream.drawForm(appearance)
                    stream.restoreGraphicsState()
                }
            }
            page.annotations = remaining
        }
    }

    private fun toPdf(doc: PDDocument, page: PDPage, space: Space, a: Annotation): PDAnnotation {
        val (r, g, b) = rgb(a.color)
        val color = PDColor(floatArrayOf(r, g, b), PDDeviceRGB.INSTANCE)
        val now = Calendar.getInstance().apply { timeInMillis = a.modified }
        val annotation: PDAnnotationMarkup = when (a) {
            is Annotation.Markup -> PDAnnotationTextMarkup(
                when (a.kind) {
                    MarkupKind.HIGHLIGHT -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
                    MarkupKind.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
                    MarkupKind.STRIKEOUT -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
                    MarkupKind.SQUIGGLY -> PDAnnotationTextMarkup.SUB_TYPE_SQUIGGLY
                }
            ).apply {
                if (a.text.isNotEmpty()) cosObject.setString(KEY_TEXT, a.text)
                quadPoints = a.lines.flatMap { line ->
                    listOf(space.x(line.left), space.y(line.top), space.x(line.right), space.y(line.top),
                        space.x(line.left), space.y(line.bottom), space.x(line.right), space.y(line.bottom))
                }.toFloatArray()
                rectangle = space.rect(a.bounds)
                appearance = appearance(doc, rectangle) { s -> drawMarkup(s, space, a) }
            }
            is Annotation.Note -> PDAnnotationText().apply {
                setName(PDAnnotationText.NAME_NOTE)
                setOpen(false)
                rectangle = space.rect(a.bounds)
                appearance = appearance(doc, rectangle) { s -> drawNoteIcon(s, rectangle, r, g, b) }
            }
            is Annotation.FreeText -> PDAnnotationMarkup().apply {
                cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_FREETEXT)
                defaultAppearance = "/Helv ${a.fontSize} Tf $r $g $b rg"
                cosObject.setString(KEY_NOTE, a.note)
                rectangle = space.rect(a.box)
                appearance = appearance(doc, rectangle) { s -> drawFreeText(s, rectangle, a, r, g, b) }
            }
            is Annotation.Ink -> PDAnnotationMarkup().apply {
                cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK)
                val list = COSArray()
                for (stroke in a.strokes) {
                    list.add(COSArray().apply { setFloatArray(stroke.flatMap { listOf(space.x(it.x), space.y(it.y)) }.toFloatArray()) })
                }
                cosObject.setItem(COSName.INKLIST, list)
                borderStyle = PDBorderStyleDictionary().apply { this.width = a.width }
                constantOpacity = a.opacity
                rectangle = space.rect(a.bounds)
                appearance = appearance(doc, rectangle) { s -> drawInk(s, space, a, r, g, b) }
            }
            is Annotation.Shape -> shapeAnnotation(doc, space, a, r, g, b)
            is Annotation.Stamp -> PDAnnotationRubberStamp().apply {
                setName(
                    when (a.kind) {
                        StampKind.APPROVED -> PDAnnotationRubberStamp.NAME_APPROVED
                        StampKind.DRAFT -> PDAnnotationRubberStamp.NAME_DRAFT
                        StampKind.CONFIDENTIAL -> "Confidential"
                        else -> "Custom"
                    }
                )
                cosObject.setString(KEY_LABEL, a.label)
                cosObject.setString(KEY_SUBTITLE, a.subtitle)
                cosObject.setString(KEY_STAMP_KIND, a.kind.name)
                rectangle = space.rect(a.box)
                appearance = appearance(doc, rectangle) { s -> drawStamp(s, rectangle, a, r, g, b) }
            }
            is Annotation.Image -> PDAnnotationRubberStamp().apply {
                setName("Image")
                cosObject.setItem(KEY_IMAGE, COSBoolean.TRUE)
                rectangle = space.rect(a.box)
                val bitmap = BitmapFactory.decodeFile(a.imagePath) ?: throw IllegalStateException("Missing image ${a.imagePath}")
                val image = LosslessFactory.createFromImage(doc, bitmap)
                appearance = appearance(doc, rectangle) { s -> s.drawImage(image, rectangle.lowerLeftX, rectangle.lowerLeftY, rectangle.width, rectangle.height) }
            }
            is Annotation.Field -> throw IllegalArgumentException("Form fields are written by FormIO")
            is Annotation.Redaction -> throw IllegalArgumentException("Redactions are applied, not stored")
        }
        // Common entries every viewer understands.
        annotation.annotationName = a.id
        annotation.titlePopup = a.author
        annotation.creationDate = now
        annotation.setModifiedDate(now)
        if (a !is Annotation.FreeText) annotation.contents = a.note.ifEmpty { if (a is Annotation.Markup) a.text.ifEmpty { null } else null }
        else annotation.contents = a.text
        if (a !is Annotation.Image) annotation.color = color
        annotation.setPrinted(true)
        annotation.page = page
        return annotation
    }

    private fun shapeAnnotation(doc: PDDocument, space: Space, a: Annotation.Shape, r: Float, g: Float, b: Float): PDAnnotationMarkup {
        val fill = a.fill?.let { rgb(it) }?.let { (fr, fg, fb) -> PDColor(floatArrayOf(fr, fg, fb), PDDeviceRGB.INSTANCE) }
        val border = PDBorderStyleDictionary().apply { this.width = a.width }
        return when (a.kind) {
            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> PDAnnotationSquareCircle(
                if (a.kind == ShapeKind.RECTANGLE) PDAnnotationSquareCircle.SUB_TYPE_SQUARE else PDAnnotationSquareCircle.SUB_TYPE_CIRCLE
            ).apply {
                borderStyle = border
                fill?.let { interiorColor = it }
                constantOpacity = a.opacity
                // The border is centered on the shape's edge, so the rectangle reaches half a line width out.
                rectangle = space.rect(Box.around(a.points, a.width / 2))
                appearance = appearance(doc, rectangle) { s -> drawShape(s, space, a, r, g, b) }
            }
            ShapeKind.LINE, ShapeKind.ARROW -> PDAnnotationLine().apply {
                line = floatArrayOf(space.x(a.points[0].x), space.y(a.points[0].y), space.x(a.points[1].x), space.y(a.points[1].y))
                if (a.kind == ShapeKind.ARROW) endPointEndingStyle = PDAnnotationLine.LE_OPEN_ARROW
                borderStyle = border
                constantOpacity = a.opacity
                rectangle = space.rect(Box.around(a.points, a.width * 4 + 6f))
                appearance = appearance(doc, rectangle) { s -> drawShape(s, space, a, r, g, b) }
            }
            ShapeKind.POLYGON -> PDAnnotationMarkup().apply {
                cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_POLYGON)
                cosObject.setItem(COSName.VERTICES, COSArray().apply { setFloatArray(a.points.flatMap { listOf(space.x(it.x), space.y(it.y)) }.toFloatArray()) })
                borderStyle = border
                fill?.let { interiorColor = it }
                constantOpacity = a.opacity
                rectangle = space.rect(a.bounds)
                appearance = appearance(doc, rectangle) { s -> drawShape(s, space, a, r, g, b) }
            }
        }
    }

    // --- Appearance drawing (PDF space) -----------------------------------------------------

    private fun appearance(doc: PDDocument, rect: PDRectangle, draw: (PDPageContentStream) -> Unit): PDAppearanceDictionary {
        val stream = PDAppearanceStream(doc).apply {
            bBox = rect
            resources = PDResources()
        }
        PDPageContentStream(doc, stream).use(draw)
        return PDAppearanceDictionary().apply { setNormalAppearance(stream) }
    }

    private fun opacity(s: PDPageContentStream, alpha: Float, multiply: Boolean = false) {
        if (alpha >= 1f && !multiply) return
        s.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
            setNonStrokingAlphaConstant(alpha)
            setStrokingAlphaConstant(alpha)
            if (multiply) setBlendMode(BlendMode.MULTIPLY)
        })
    }

    private fun drawMarkup(s: PDPageContentStream, space: Space, a: Annotation.Markup) {
        val (r, g, b) = rgb(a.color)
        when (a.kind) {
            MarkupKind.HIGHLIGHT -> {
                opacity(s, 1f, multiply = true)
                s.setNonStrokingColor(r, g, b)
                for (line in a.lines) s.addRect(space.x(line.left), space.y(line.bottom), line.width, line.height)
                s.fill()
            }
            MarkupKind.UNDERLINE, MarkupKind.STRIKEOUT -> {
                s.setStrokingColor(r, g, b)
                for (line in a.lines) {
                    val y = if (a.kind == MarkupKind.UNDERLINE) line.bottom - line.height * 0.08f else line.top + line.height * 0.55f
                    s.setLineWidth((line.height / 14).coerceIn(0.75f, 3f))
                    s.moveTo(space.x(line.left), space.y(y))
                    s.lineTo(space.x(line.right), space.y(y))
                }
                s.stroke()
            }
            MarkupKind.SQUIGGLY -> {
                s.setStrokingColor(r, g, b)
                s.setLineWidth(0.8f)
                for (line in a.lines) {
                    val amplitude = (line.height / 10).coerceIn(1f, 3f)
                    val step = amplitude * 2
                    var x = line.left
                    var up = true
                    s.moveTo(space.x(x), space.y(line.bottom))
                    while (x < line.right) {
                        x = minOf(x + step, line.right)
                        s.lineTo(space.x(x), space.y(line.bottom - if (up) amplitude else 0f))
                        up = !up
                    }
                }
                s.stroke()
            }
        }
    }

    private fun drawNoteIcon(s: PDPageContentStream, rect: PDRectangle, r: Float, g: Float, b: Float) {
        s.setNonStrokingColor(r, g, b)
        s.setStrokingColor(0.3f, 0.3f, 0.3f)
        s.setLineWidth(0.6f)
        s.addRect(rect.lowerLeftX + 1, rect.lowerLeftY + 1, rect.width - 2, rect.height - 2)
        s.fillAndStroke()
        s.setStrokingColor(0.25f, 0.25f, 0.25f)
        for (i in 1..3) {
            val y = rect.upperRightY - 1 - i * (rect.height - 2) / 4
            s.moveTo(rect.lowerLeftX + 4, y)
            s.lineTo(rect.upperRightX - 4, y)
        }
        s.stroke()
    }

    private fun drawFreeText(s: PDPageContentStream, rect: PDRectangle, a: Annotation.FreeText, r: Float, g: Float, b: Float) {
        val font = PDType1Font.HELVETICA
        val lines = wrap(a.text, font, a.fontSize, rect.width - 4)
        s.beginText()
        s.setFont(font, a.fontSize)
        s.setNonStrokingColor(r, g, b)
        s.newLineAtOffset(rect.lowerLeftX + 2, rect.upperRightY - a.fontSize)
        lines.forEachIndexed { i, line ->
            if (i > 0) s.newLineAtOffset(0f, -a.fontSize * 1.2f)
            s.showText(line)
        }
        s.endText()
    }

    /** Word-wraps [text] to [width] points, keeping Latin-1 characters only (standard fonts). */
    fun wrap(text: String, font: PDType1Font, size: Float, width: Float): List<String> {
        val clean = text.map { c -> if (c == '\n' || c.code in 32..255) c else '?' }.joinToString("")
        val result = mutableListOf<String>()
        for (paragraph in clean.split('\n')) {
            var line = ""
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && font.getStringWidth(candidate) / 1000 * size > width) {
                    result += line
                    line = word
                } else {
                    line = candidate
                }
            }
            result += line
        }
        return result
    }

    private fun drawInk(s: PDPageContentStream, space: Space, a: Annotation.Ink, r: Float, g: Float, b: Float) {
        opacity(s, a.opacity, multiply = a.opacity < 1f)
        s.setStrokingColor(r, g, b)
        s.setLineWidth(a.width)
        s.setLineCapStyle(1)
        s.setLineJoinStyle(1)
        for (stroke in a.strokes) {
            val first = stroke.first()
            s.moveTo(space.x(first.x), space.y(first.y))
            if (stroke.size == 1) s.lineTo(space.x(first.x) + 0.01f, space.y(first.y))
            for (p in stroke.drop(1)) s.lineTo(space.x(p.x), space.y(p.y))
        }
        s.stroke()
    }

    private fun drawShape(s: PDPageContentStream, space: Space, a: Annotation.Shape, r: Float, g: Float, b: Float) {
        opacity(s, a.opacity)
        s.setStrokingColor(r, g, b)
        s.setLineWidth(a.width)
        s.setLineJoinStyle(1)
        a.fill?.let { rgb(it) }?.let { (fr, fg, fb) -> s.setNonStrokingColor(fr, fg, fb) }
        val p = a.points
        when (a.kind) {
            ShapeKind.RECTANGLE -> {
                val box = Box.around(p)
                s.addRect(space.x(box.left), space.y(box.bottom), box.width, box.height)
            }
            ShapeKind.ELLIPSE -> {
                val box = Box.around(p)
                ellipse(s, space.x(box.left), space.y(box.bottom), box.width, box.height)
            }
            ShapeKind.LINE, ShapeKind.ARROW -> {
                s.moveTo(space.x(p[0].x), space.y(p[0].y))
                s.lineTo(space.x(p[1].x), space.y(p[1].y))
                if (a.kind == ShapeKind.ARROW) {
                    val (w1, w2) = Geometry.arrowHead(p[0], p[1], a.width * 4 + 6f)
                    s.moveTo(space.x(w1.x), space.y(w1.y))
                    s.lineTo(space.x(p[1].x), space.y(p[1].y))
                    s.lineTo(space.x(w2.x), space.y(w2.y))
                }
                s.stroke()
                return
            }
            ShapeKind.POLYGON -> {
                s.moveTo(space.x(p[0].x), space.y(p[0].y))
                for (q in p.drop(1)) s.lineTo(space.x(q.x), space.y(q.y))
                s.closePath()
            }
        }
        if (a.fill != null) s.fillAndStroke() else s.stroke()
    }

    private fun ellipse(s: PDPageContentStream, x: Float, y: Float, w: Float, h: Float) {
        val k = 0.5523f
        val cx = x + w / 2
        val cy = y + h / 2
        val rx = w / 2
        val ry = h / 2
        s.moveTo(cx + rx, cy)
        s.curveTo(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry)
        s.curveTo(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy)
        s.curveTo(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry)
        s.curveTo(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy)
        s.closePath()
    }

    private fun drawStamp(s: PDPageContentStream, rect: PDRectangle, a: Annotation.Stamp, r: Float, g: Float, b: Float) {
        val inset = 2f
        s.setStrokingColor(r, g, b)
        s.setLineWidth(2.5f)
        s.addRect(rect.lowerLeftX + inset, rect.lowerLeftY + inset, rect.width - 2 * inset, rect.height - 2 * inset)
        s.stroke()
        val font = PDType1Font.HELVETICA_BOLD
        val label = a.label.ifBlank { a.kind.label }.map { if (it.code in 32..255) it else '?' }.joinToString("")
        val hasSubtitle = a.subtitle.isNotBlank()
        var size = rect.height * (if (hasSubtitle) 0.45f else 0.6f)
        val width = font.getStringWidth(label) / 1000 * size
        if (width > rect.width - 12) size *= (rect.width - 12) / width
        val labelWidth = font.getStringWidth(label) / 1000 * size
        s.beginText()
        s.setFont(font, size)
        s.setNonStrokingColor(r, g, b)
        val baseline = if (hasSubtitle) rect.upperRightY - inset - 4 - size * 0.8f else rect.lowerLeftY + (rect.height - size * 0.7f) / 2
        s.newLineAtOffset(rect.lowerLeftX + (rect.width - labelWidth) / 2, baseline)
        s.showText(label)
        s.endText()
        if (hasSubtitle) {
            val small = PDType1Font.HELVETICA
            val subSize = (rect.height * 0.2f).coerceAtMost(10f)
            val sub = a.subtitle.map { if (it.code in 32..255) it else '?' }.joinToString("")
            val subWidth = small.getStringWidth(sub) / 1000 * subSize
            s.beginText()
            s.setFont(small, subSize)
            s.newLineAtOffset(rect.lowerLeftX + (rect.width - subWidth) / 2, rect.lowerLeftY + inset + 4)
            s.showText(sub)
            s.endText()
        }
    }

    // --- Helpers ----------------------------------------------------------------------------

    fun newId(): String = "pdfreader-" + UUID.randomUUID().toString()

    private fun rgb(color: Int) = Triple(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f)

    private fun argb(r: Float, g: Float, b: Float) =
        (0xFF shl 24) or ((r * 255).toInt().coerceIn(0, 255) shl 16) or ((g * 255).toInt().coerceIn(0, 255) shl 8) or (b * 255).toInt().coerceIn(0, 255)
}
