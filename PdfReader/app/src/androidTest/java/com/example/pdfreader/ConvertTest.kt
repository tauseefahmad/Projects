package com.example.pdfreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import com.example.pdfreader.core.TextFormat
import com.google.android.material.appbar.MaterialToolbar
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/** Phase 5: compression, export, pictures to PDF and OCR. */
@RunWith(AndroidJUnit4::class)
class ConvertTest {

    @Before
    fun setUp() {
        ReaderTestSupport.resetAppState()
        PDFBoxResourceLoader.init(context)
    }

    private fun shared(name: String) = File(File(context.cacheDir, "shared").apply { mkdirs() }, name)

    private fun uriOf(f: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)

    /** A PDF whose only page is [bitmap], stored losslessly. */
    private fun pdfWithPicture(name: String, bitmap: Bitmap): File {
        val f = shared(name)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val image = LosslessFactory.createFromImage(doc, bitmap)
            PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height) }
            doc.save(f)
        }
        return f
    }

    /** A white page picture with black text lines, as a scanner would produce. */
    private fun scannedText(lines: List<String>, width: Int = 1654, height: Int = 2339): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 64f
        }
        lines.forEachIndexed { i, line -> canvas.drawText(line, 120f, 300f + i * 120f, paint) }
        return bitmap
    }

    private fun images(f: File): List<PDImageXObject> = PDDocument.load(f).use { doc ->
        val resources = doc.getPage(0).resources
        resources.xObjectNames.mapNotNull { resources.getXObject(it) as? PDImageXObject }.map { it }
    }

    @Test
    fun compressShrinksBigPicturesAndReplacesTheFile() {
        // A large photo-like picture stored without compression losses.
        val photo = Bitmap.createBitmap(2400, 3200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(photo)
        val paint = Paint()
        for (y in 0 until 3200 step 16) for (x in 0 until 2400 step 16) {
            paint.color = Color.rgb((x * 7 + y) % 256, (y * 3) % 256, (x + y * 5) % 256)
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 16f, y + 16f, paint)
        }
        val f = pdfWithPicture("big.pdf", photo)
        val before = f.length()
        openUri(uriOf(f)).use { scenario ->
            waitForPageLabel("1 / 1")
            scenario.onActivity { it.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.tool_compress, 0) }
            waitFor { onView(withText(R.string.compress_action)).inRoot(isDialog()).perform(click()) }
            waitFor(30_000) { onView(withText(R.string.compress_replace)).inRoot(isDialog()).perform(click()) }
            waitFor(20_000) { assertTrue("${f.length()} < $before", f.length() < before / 2) }
            val image = images(f).single()
            assertEquals(1200, image.width)
            assertEquals(1600, image.height)
            waitForPageLabel("1 / 1")
        }
    }

    @Test
    fun pagesExportAsPicturesAndText() {
        val f = File(context.cacheDir, "shared/export.pdf")
        TestPdfs.createWithText(context, "export.pdf", listOf("First page words", "Second page words"))
        val dir = File(context.cacheDir, "export-test").apply { deleteRecursively(); mkdirs() }
        val pictures = ConvertTools.exportImages(f, listOf(0, 1), 72, ImageFormat.PNG, "export", dir)
        assertEquals(listOf("export-1.png", "export-2.png"), pictures.map { it.name })
        val decoded = android.graphics.BitmapFactory.decodeFile(pictures[0].path)
        assertEquals(595, decoded.width)
        assertEquals(842, decoded.height)

        val zip = File(dir, "all.zip")
        zip.outputStream().use { ConvertTools.zip(pictures, it) }
        val names = ZipInputStream(zip.inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("export-1.png", "export-2.png"), names)

        PDDocument.load(f).use { doc ->
            val md = java.io.ByteArrayOutputStream().also { ConvertTools.exportText(doc, "export", TextFormat.MARKDOWN, it) }.toString("UTF-8")
            assertTrue(md, md.contains("## Page 2\n\nSecond page words"))
            val docx = java.io.ByteArrayOutputStream().also { ConvertTools.exportText(doc, "export", TextFormat.WORD, it) }.toByteArray()
            val entries = ZipInputStream(docx.inputStream()).use { z -> generateSequence { z.nextEntry?.let { e -> e.name to z.readBytes().toString(Charsets.UTF_8) } }.toMap() }
            assertTrue(entries.getValue("word/document.xml").contains("First page words"))
        }
    }

    @Test
    fun picturesBecomeOnePagePerPicture() {
        fun picture(name: String, w: Int, h: Int): Uri {
            val f = File(context.cacheDir, name)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return Uri.fromFile(f)
        }
        val out = File(context.cacheDir, "pictures.pdf")
        out.outputStream().use { ConvertTools.imagesToPdf(context, listOf(picture("p1.png", 600, 800), picture("p2.png", 1600, 900)), it) }
        PDDocument.load(out).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val first = doc.getPage(0).mediaBox
            val second = doc.getPage(1).mediaBox
            assertTrue(first.height > first.width)
            assertTrue(second.width > second.height)
        }
    }

    @Test
    fun ocrMakesAScannedPageSearchable() {
        val f = pdfWithPicture("scan.pdf", scannedText(listOf("INVOICE NUMBER 12345", "Hello World from the scanner")))
        PDDocument.load(f).use { assertEquals("", PDFTextStripper().getText(it).trim()) }
        openUri(uriOf(f)).use { scenario ->
            waitForPageLabel("1 / 1")
            scenario.onActivity { it.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.tool_ocr, 0) }
            waitFor { onView(withText(R.string.ocr_action)).inRoot(isDialog()).perform(click()) }
            waitFor(60_000) {
                val text = PDDocument.load(f).use { PDFTextStripper().getText(it) }
                assertTrue(text, text.contains("INVOICE") && text.contains("12345") && text.contains("Hello"))
            }
            waitForPageLabel("1 / 1")
        }
        // The text sits over the scanned words: "INVOICE" is near the top left.
        PDDocument.load(f).use { doc ->
            val text = PageTextProbe.positions(doc, "INVOICE")
            assertTrue("$text", text != null && text.first in 30f..90f && text.second in 50f..150f)
        }
    }

    @Test
    fun sidewaysScanIsRecognizedTheRightWayUp() {
        val upright = scannedText(listOf("SIDEWAYS DOCUMENT TITLE", "Second line of text here"))
        val turned = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, Matrix().apply { postRotate(90f) }, true)
        val f = pdfWithPicture("sideways.pdf", turned)
        val out = File(context.cacheDir, "sideways-ocr.pdf")
        val result = out.outputStream().use { Ocr.recognize(context, f, null, true, it) }
        assertEquals(1, result.pagesRecognized)
        val text = PDDocument.load(out).use { PDFTextStripper().getText(it) }
        assertTrue(text, text.contains("SIDEWAYS") && text.contains("DOCUMENT"))
    }
}

/** Finds where a word starts on page 1 (x, y from the top-left, in points). */
object PageTextProbe {
    fun positions(doc: PDDocument, word: String): Pair<Float, Float>? {
        var found: Pair<Float, Float>? = null
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, positions: List<com.tom_roush.pdfbox.text.TextPosition>) {
                val i = text.indexOf(word)
                if (i >= 0 && found == null && i < positions.size) found = positions[i].xDirAdj to positions[i].yDirAdj
                super.writeString(text, positions)
            }
        }
        stripper.startPage = 1
        stripper.endPage = 1
        stripper.getText(doc)
        return found
    }
}
