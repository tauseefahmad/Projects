package com.example.pdfreader

import android.content.Context
import android.net.Uri
import com.example.pdfreader.core.StampMath
import com.example.pdfreader.core.StampPosition
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Text added to pages: optional header and footer text and a page number template. */
data class StampSpec(
    val header: String = "",
    val footer: String = "",
    val pageNumber: String = "Page {n} of {total}",
    val pageNumberPosition: StampPosition = StampPosition.BOTTOM_CENTER,
    val startNumber: Int = 1,
    val fontSize: Float = 10f,
    val date: String = "",
    val fileName: String = "",
    /** 0-based pages to stamp; null = all. */
    val pages: Set<Int>? = null,
)

/** A diagonal text watermark. */
data class WatermarkSpec(
    val text: String,
    val opacity: Float = 0.25f,
    val angle: Float = 45f,
    val fontSize: Float = 64f,
    val pages: Set<Int>? = null,
)

/**
 * Whole-document tools from roadmap phase 3: merge, split, crop, resize, page numbers and
 * headers/footers, and watermarks. All work on the phone with PdfBox; content stays as it is
 * (text remains selectable).
 */
object DocumentTools {

    fun load(context: Context, uri: Uri): PDDocument {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
        return input.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
    }

    /**
     * Combines [files] (uri to display name) in order. Each file's bookmarks are kept under a
     * parent bookmark named after the file.
     */
    fun merge(context: Context, files: List<Pair<Uri, String>>, output: OutputStream) {
        val sources = mutableListOf<PDDocument>()
        try {
            PDDocument().use { target ->
                val outline = PDDocumentOutline()
                for ((uri, name) in files) {
                    val source = load(context, uri).also(sources::add)
                    val newPages = source.pages.map { target.importPage(it).also { copy -> copy.rotation = it.rotation } }
                    val parent = PDOutlineItem().apply { title = name.removeSuffix(".pdf").removeSuffix(".PDF") }
                    newPages.firstOrNull()?.let { first -> parent.destination = PDPageFitDestination().apply { page = first } }
                    source.documentCatalog.documentOutline?.let { copyOutline(source, it, parent, newPages, 0) }
                    outline.addLast(parent)
                }
                target.documentCatalog.documentOutline = outline
                target.save(output)
            }
        } finally {
            sources.forEach { runCatching { it.close() } }
        }
    }

    private fun copyOutline(from: PDDocument, source: PDOutlineNode, target: PDOutlineNode, newPages: List<PDPage>, depth: Int) {
        if (depth > 16) return
        for (item in source.children()) {
            val index = runCatching { item.findDestinationPage(from) }.getOrNull()?.let { from.pages.indexOf(it) } ?: -1
            val entry = PDOutlineItem().apply { title = item.title }
            newPages.getOrNull(index)?.let { page -> entry.destination = PDPageFitDestination().apply { this.page = page } }
            copyOutline(from, item, entry, newPages, depth + 1)
            target.addLast(entry)
        }
    }

    /** Splits [source] into one PDF per range; returns the files, named "<base>-<n>.pdf", in [dir]. */
    fun split(context: Context, source: Uri, ranges: List<IntRange>, baseName: String, dir: File): List<File> {
        dir.mkdirs()
        val files = mutableListOf<File>()
        load(context, source).use { doc ->
            ranges.forEachIndexed { i, range ->
                val file = File(dir, "${DocumentExport.safeFileName(baseName).removeSuffix(".pdf")}-${i + 1}.pdf")
                PDDocument().use { part ->
                    for (p in range) part.importPage(doc.getPage(p)).rotation = doc.getPage(p).rotation
                    part.save(file)
                }
                files += file
            }
        }
        return files
    }

    /** Packs [files] into a ZIP archive. */
    fun zip(files: List<File>, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Trims margins (in points) from every page by setting its crop box. */
    fun crop(context: Context, source: Uri, left: Float, top: Float, right: Float, bottom: Float, output: OutputStream) {
        load(context, source).use { doc ->
            for (page in doc.pages) {
                val box = page.cropBox
                val c = StampMath.crop(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY, left, bottom, right, top)
                page.cropBox = PDRectangle(c[0], c[1], c[2] - c[0], c[3] - c[1])
            }
            doc.save(output)
        }
    }

    /** Scales every page onto a sheet of [width]×[height] points, centred (e.g. mixed sizes to A4). */
    fun resize(context: Context, source: Uri, width: Float, height: Float, output: OutputStream) {
        load(context, source).use { doc ->
            for (page in doc.pages) {
                val visible = page.cropBox
                // A page turned a quarter is shown sideways, so its sheet is turned too.
                val sideways = page.rotation % 180 != 0
                val sheetWidth = if (sideways) height else width
                val sheetHeight = if (sideways) width else height
                val (scale, dx, dy) = StampMath.fit(visible.width, visible.height, sheetWidth, sheetHeight)
                // Wrap the existing content in a scale-and-move, then give the page its new size.
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, false).use { stream ->
                    stream.saveGraphicsState()
                    stream.transform(Matrix(scale, 0f, 0f, scale, dx - visible.lowerLeftX * scale, dy - visible.lowerLeftY * scale))
                }
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, false).use { it.restoreGraphicsState() }
                page.mediaBox = PDRectangle(sheetWidth, sheetHeight)
                page.cropBox = PDRectangle(sheetWidth, sheetHeight)
            }
            doc.save(output)
        }
    }

    /** Adds header/footer text and page numbers on top of the existing content. */
    fun stamp(context: Context, source: Uri, spec: StampSpec, output: OutputStream) {
        load(context, source).use { doc ->
            val font = PDType1Font.HELVETICA
            val count = doc.numberOfPages
            for (i in 0 until count) {
                if (spec.pages != null && i !in spec.pages) continue
                val page = doc.getPage(i)
                val box = page.cropBox
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    val margin = maxOf(18f, spec.fontSize * 1.5f)
                    fun line(text: String, position: StampPosition) {
                        if (text.isBlank()) return
                        val filled = sanitize(StampMath.fill(text, i, count, spec.startNumber, spec.date, spec.fileName))
                        val width = font.getStringWidth(filled) / 1000 * spec.fontSize
                        val (x, y) = StampMath.place(position, box.width, box.height, width, spec.fontSize, margin)
                        stream.beginText()
                        stream.setFont(font, spec.fontSize)
                        stream.setNonStrokingColor(0.15f, 0.15f, 0.15f)
                        stream.newLineAtOffset(box.lowerLeftX + x, box.lowerLeftY + y)
                        stream.showText(filled)
                        stream.endText()
                    }
                    line(spec.header, StampPosition.TOP_CENTER)
                    // Footer text and the page number share the bottom edge, so put them apart.
                    val footerPosition = if (spec.pageNumberPosition == StampPosition.BOTTOM_CENTER) StampPosition.BOTTOM_LEFT else StampPosition.BOTTOM_CENTER
                    line(spec.footer, footerPosition)
                    line(spec.pageNumber, spec.pageNumberPosition)
                }
            }
            doc.save(output)
        }
    }

    /** Draws a semi-transparent, rotated text watermark across the middle of pages. */
    fun watermark(context: Context, source: Uri, spec: WatermarkSpec, output: OutputStream) {
        load(context, source).use { doc ->
            val font = PDType1Font.HELVETICA_BOLD
            val text = sanitize(spec.text)
            val state = PDExtendedGraphicsState().apply { setNonStrokingAlphaConstant(spec.opacity.coerceIn(0.05f, 1f)) }
            for (i in 0 until doc.numberOfPages) {
                if (spec.pages != null && i !in spec.pages) continue
                val page = doc.getPage(i)
                val box = page.cropBox
                val width = font.getStringWidth(text) / 1000 * spec.fontSize
                // Shrink long text so it fits the page diagonal.
                val diagonal = kotlin.math.hypot(box.width, box.height) * 0.85f
                val size = if (width > diagonal) spec.fontSize * diagonal / width else spec.fontSize
                val textWidth = font.getStringWidth(text) / 1000 * size
                val (x, y) = StampMath.centeredRotated(box.width, box.height, textWidth, size * 0.7f, spec.angle)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    stream.saveGraphicsState()
                    stream.setGraphicsStateParameters(state)
                    stream.setNonStrokingColor(0.8f, 0.1f, 0.1f)
                    stream.beginText()
                    stream.setFont(font, size)
                    val a = Math.toRadians(spec.angle.toDouble())
                    stream.setTextMatrix(Matrix.getRotateInstance(a, box.lowerLeftX + x, box.lowerLeftY + y))
                    stream.showText(text)
                    stream.endText()
                    stream.restoreGraphicsState()
                }
            }
            doc.save(output)
        }
    }

    /** The standard fonts only cover Latin-1; replace other characters instead of failing. */
    private fun sanitize(text: String): String = text.map { c -> if (c.code in 32..255) c else '?' }.joinToString("")
}
