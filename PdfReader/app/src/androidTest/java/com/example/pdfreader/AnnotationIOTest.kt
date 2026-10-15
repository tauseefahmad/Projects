package com.example.pdfreader

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.core.Annotation
import com.example.pdfreader.core.Box
import com.example.pdfreader.core.MarkupKind
import com.example.pdfreader.core.Pt
import com.example.pdfreader.core.ShapeKind
import com.example.pdfreader.core.StampKind
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Annotations survive a save and reload as standard PDF annotations, and flattening bakes them in. */
@RunWith(AndroidJUnit4::class)
class AnnotationIOTest {

    private val file = File(context.cacheDir, "annotation-io.pdf")
    private val imageDir = File(context.cacheDir, "annotation-io-images")

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        file.delete()
        imageDir.deleteRecursively()
    }

    private fun image(): String {
        val f = File(context.cacheDir, "annotation-io.png")
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.path
    }

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun all(): List<Annotation> = listOf(
        Annotation.Markup("pdfreader-m", 0, 0xFFFFFF00.toInt(), "Ann", 0, "", MarkupKind.HIGHLIGHT, listOf(Box(72f, 100f, 300f, 118f)), "marked text"),
        Annotation.Note("pdfreader-n", 0, 0xFFFFFF00.toInt(), "Ann", 0, "a comment", Pt(400f, 100f)),
        Annotation.FreeText("pdfreader-t", 0, blue, "Ann", 0, "text note", Box(72f, 200f, 272f, 240f), "Hello box", 14f),
        Annotation.Ink("pdfreader-i", 0, red, "Ann", 0, "", listOf(listOf(Pt(100f, 300f), Pt(150f, 320f), Pt(200f, 300f))), 3f),
        Annotation.Shape("pdfreader-r", 1, red, "Bob", 0, "", ShapeKind.RECTANGLE, listOf(Pt(100f, 100f), Pt(200f, 150f)), 4f),
        Annotation.Shape("pdfreader-a", 1, red, "Bob", 0, "", ShapeKind.ARROW, listOf(Pt(100f, 300f), Pt(250f, 350f)), 2f),
        Annotation.Stamp("pdfreader-s", 1, 0xFF00FF00.toInt(), "Bob", 0, "", Box(300f, 400f, 450f, 450f), StampKind.APPROVED, "APPROVED", "today"),
        Annotation.Image("pdfreader-p", 1, 0, "Bob", 0, "", Box(100f, 500f, 180f, 540f), image()),
    )

    private fun writeAndRead(items: List<Annotation>, flatten: Boolean = false): Pair<List<Annotation>, PDDocument> {
        PDDocument().use { doc ->
            repeat(2) { doc.addPage(PDPage(PDRectangle.A4)) }
            // An annotation this app doesn't manage must survive untouched.
            doc.getPage(0).annotations = listOf(PDAnnotationLink().apply { rectangle = PDRectangle(10f, 10f, 20f, 20f) })
            AnnotationIO.write(doc, items)
            if (flatten) AnnotationIO.flatten(doc)
            doc.save(file)
        }
        val doc = PDDocument.load(file)
        return AnnotationIO.read(doc, imageDir) to doc
    }

    private fun assertBoxNear(expected: Box, actual: Box) {
        val tolerance = 0.6f
        assertEquals(expected.left, actual.left, tolerance)
        assertEquals(expected.top, actual.top, tolerance)
        assertEquals(expected.right, actual.right, tolerance)
        assertEquals(expected.bottom, actual.bottom, tolerance)
    }

    @Test
    fun everyTypeRoundTrips() {
        val items = all()
        val (read, doc) = writeAndRead(items)
        doc.use {
            assertEquals(items.map { it.id }.sorted(), read.map { it.id }.sorted())
            for (original in items) {
                val back = read.first { it.id == original.id }
                assertEquals(original::class, back::class)
                assertEquals(original.page, back.page)
                assertEquals(original.author, back.author)
                assertBoxNear(original.bounds, back.bounds)
                if (original !is Annotation.Image) assertEquals(original.id, original.color, back.color)
            }
            val markup = read.first { it.id == "pdfreader-m" } as Annotation.Markup
            assertEquals("marked text", markup.text)
            assertEquals("", markup.note)
            assertEquals("a comment", (read.first { it.id == "pdfreader-n" } as Annotation.Note).note)
            val text = read.first { it.id == "pdfreader-t" } as Annotation.FreeText
            assertEquals("Hello box", text.text)
            assertEquals("text note", text.note)
            assertEquals(14f, text.fontSize, 0.01f)
            assertEquals(ShapeKind.ARROW, (read.first { it.id == "pdfreader-a" } as Annotation.Shape).kind)
            val stamp = read.first { it.id == "pdfreader-s" } as Annotation.Stamp
            assertEquals(StampKind.APPROVED, stamp.kind)
            assertEquals("today", stamp.subtitle)
            assertTrue(File((read.first { it.id == "pdfreader-p" } as Annotation.Image).imagePath).exists())
            // Standard subtypes that other PDF apps understand, plus the untouched link.
            val subtypes = doc.pages.flatMap { page -> page.annotations.map { it.subtype } }.sorted()
            assertEquals(listOf("FreeText", "Highlight", "Ink", "Line", "Link", "Square", "Stamp", "Stamp", "Text"), subtypes)
            doc.pages.flatMap { it.annotations }.filter { it !is PDAnnotationLink }.forEach {
                assertTrue("${it.subtype} needs an appearance", it.normalAppearanceStream != null)
            }
        }
    }

    @Test
    fun rewritingKeepsGeometryStable() {
        val (first, doc1) = writeAndRead(all())
        doc1.close()
        val (second, doc2) = writeAndRead(first)
        doc2.use {
            for (a in first) assertBoxNear(a.bounds, second.first { it.id == a.id }.bounds)
        }
    }

    @Test
    fun flatteningRemovesAnnotationsButKeepsTheirLook() {
        val (read, doc) = writeAndRead(all(), flatten = true)
        doc.use {
            assertTrue(read.isEmpty())
            // Only the link (not an annotation we draw) is left.
            assertEquals(listOf("Link"), doc.pages.flatMap { page -> page.annotations.map { it.subtype } })
            for (page in doc.pages) assertTrue(page.resources.xObjectNames.iterator().hasNext())
        }
    }
}
