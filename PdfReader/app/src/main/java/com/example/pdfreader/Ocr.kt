package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.pdfreader.core.OcrGeometry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.OutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** What an OCR run did. */
data class OcrResult(val pagesRecognized: Int, val pagesSkipped: Int, val words: Int)

/**
 * Text recognition (ML Kit, on the phone, Latin script: English, German and other European
 * languages). Recognized words are added as an invisible text layer exactly over the scanned
 * words, so the page looks the same but can be searched, selected and copied.
 */
object Ocr {

    /** Resolution pages are recognized at. */
    private const val DPI = 250f
    private const val MAX_PIXELS = 3300

    /** A recognized word in page points: upright box, plus the turn that made it upright. */
    private class Word(val text: String, val box: Rect, val turn: Int)

    /**
     * Recognizes [pages] (0-based; null = all) of [source] and writes the result to [output].
     * Pages that already have text are left alone when [skipPagesWithText]. Pages scanned
     * sideways or upside down are detected and recognized the right way up.
     */
    fun recognize(
        context: Context,
        source: File,
        pages: Set<Int>?,
        skipPagesWithText: Boolean,
        output: OutputStream,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): OcrResult {
        PDFBoxResourceLoader.init(context.applicationContext)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var recognized = 0
        var skipped = 0
        var words = 0
        try {
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        val targets = (0 until minOf(doc.numberOfPages, renderer.pageCount)).filter { pages == null || it in pages }
                        val stripper = PDFTextStripper()
                        targets.forEachIndexed { done, index ->
                            if (isCancelled()) throw java.util.concurrent.CancellationException()
                            progress(done, targets.size)
                            if (skipPagesWithText) {
                                stripper.startPage = index + 1
                                stripper.endPage = index + 1
                                if (runCatching { stripper.getText(doc) }.getOrDefault("").count { it.isLetterOrDigit() } > 20) {
                                    skipped++
                                    return@forEachIndexed
                                }
                            }
                            val (bitmap, scale) = renderer.openPage(index).use { page -> render(page) }
                            val found = recognizePage(recognizer, bitmap)
                            words += addTextLayer(doc, index, found, bitmap.width, bitmap.height, scale)
                            bitmap.recycle()
                            recognized++
                        }
                        progress(targets.size, targets.size)
                    }
                }
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                doc.save(output)
            }
        } finally {
            recognizer.close()
        }
        return OcrResult(recognized, skipped, words)
    }

    private fun render(page: PdfRenderer.Page): Pair<Bitmap, Float> {
        var scale = DPI / 72f
        val longest = max(page.width, page.height) * scale
        if (longest > MAX_PIXELS) scale *= MAX_PIXELS / longest
        val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1), (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap to scale
    }

    /** Recognizes upright first; if little is found, tries the other three orientations. */
    private fun recognizePage(recognizer: com.google.mlkit.vision.text.TextRecognizer, bitmap: Bitmap): List<Word> {
        fun run(turn: Int): List<Word> {
            val text: Text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, turn)))
            return text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { e ->
                val box = e.boundingBox ?: return@mapNotNull null
                if (e.text.isBlank()) null else Word(e.text, box, turn)
            }
        }
        fun score(words: List<Word>) = words.filter { it.text.length >= 2 }.sumOf { w -> w.text.count { it.isLetter() } }
        var best = run(0)
        if (score(best) >= 40) return best
        for (turn in listOf(90, 270, 180)) {
            val other = run(turn)
            if (score(other) > score(best) * 1.5) best = other
        }
        return best
    }

    /** Writes [words] (pixels of a [width] × [height] rendering at [scale] px/pt) as invisible text. Returns the count. */
    private fun addTextLayer(doc: PDDocument, index: Int, words: List<Word>, width: Int, height: Int, scale: Float): Int {
        if (words.isEmpty()) return 0
        val page = doc.getPage(index)
        val crop = page.cropBox
        val rotation = page.rotation
        val font = PDType1Font.HELVETICA
        var written = 0
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { s ->
            s.beginText()
            s.setRenderingMode(RenderingMode.NEITHER)
            for (w in words) {
                val text = encodable(font, w.text)
                if (text.isBlank()) continue
                // Box in the upright picture; ML Kit reports boxes in the turned (upright) image.
                val turnedW = if (w.turn % 180 == 90) height.toFloat() else width.toFloat()
                val turnedH = if (w.turn % 180 == 90) width.toFloat() else height.toFloat()
                val box = w.box
                val boxHeight = (box.bottom - box.top).toFloat()
                val baseline = box.bottom - boxHeight * 0.22f
                // Start of the baseline, back in the rendering, then on the unrotated page.
                val start = OcrGeometry.unturn(box.left.toFloat().coerceIn(0f, turnedW), baseline.coerceIn(0f, turnedH), w.turn, width.toFloat(), height.toFloat())
                val onPage = OcrGeometry.displayToPage(start.x / scale, start.y / scale, rotation, crop.width, crop.height)
                val x = crop.lowerLeftX + onPage.x
                val y = crop.upperRightY - onPage.y
                val size = (boxHeight / scale * 0.85f).coerceAtLeast(1f)
                val natural = font.getStringWidth(text) / 1000f * size
                val stretch = ((box.right - box.left) / scale / natural.coerceAtLeast(0.01f)).coerceIn(0.2f, 5f)
                val angle = Math.toRadians(OcrGeometry.textAngle(w.turn, rotation).toDouble())
                val c = cos(angle).toFloat()
                val sn = sin(angle).toFloat()
                s.setFont(font, size)
                s.setTextMatrix(Matrix(c * stretch, sn * stretch, -sn, c, x, y))
                s.showText(text)
                written++
            }
            s.endText()
        }
        return written
    }

    /** [text] with characters the standard font can't encode left out. */
    private fun encodable(font: PDType1Font, text: String): String = buildString {
        for (ch in text) if (runCatching { font.encode(ch.toString()) }.isSuccess) append(ch)
    }
}
