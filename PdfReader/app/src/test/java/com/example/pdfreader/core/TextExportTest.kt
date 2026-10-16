package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class TextExportTest {

    private val page = "The quick brown fox jumps over the lazy dog and keeps run-\nning across the field until the end of the line.\nShort ending.\n\nNew paragraph here."

    @Test
    fun linesBecomeParagraphs() {
        assertEquals(
            listOf("The quick brown fox jumps over the lazy dog and keeps running across the field until the end of the line. Short ending.", "New paragraph here."),
            TextExport.paragraphs(page),
        )
    }

    @Test
    fun markdownAndHtmlEscapeTheirSpecialCharacters() {
        val md = TextExport.markdown("Report", listOf("Use *stars* & #tags", "1. Not a list"))
        assertTrue(md, md.contains("# Report\n\n## Page 1\n\nUse \\*stars\\* & \\#tags"))
        assertTrue(md, md.contains("## Page 2\n\n1\\. Not a list"))
        val html = TextExport.html("A < B", listOf("x & y"))
        assertTrue(html.contains("<title>A &lt; B</title>"))
        assertTrue(html.contains("<p>x &amp; y</p>"))
    }

    @Test
    fun docxIsAZipWithTheTextInDocumentXml() {
        val out = ByteArrayOutputStream()
        TextExport.docx("Letter", listOf("Dear <Sir>,", "Page two"), out)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels", "word/styles.xml"), entries.keys)
        val doc = entries.getValue("word/document.xml")
        assertTrue(doc.contains("Dear &lt;Sir&gt;,"))
        assertTrue(doc.contains("<w:br w:type=\"page\"/>"))
    }

    @Test
    fun tableColumnsComeFromGapsNoRowCrosses() {
        fun w(text: String, left: Float, top: Float) = PositionedWord(text, left, top, left + text.length * 5f, top + 10f)
        val words = listOf(
            w("Date", 10f, 10f), w("Item", 100f, 10f), w("Amount", 200f, 10f),
            w("01.02.", 10f, 25f), w("Coffee", 100f, 25f), w("beans", 133f, 25f), w("12,50", 200f, 25f),
            w("03.02.", 10f, 40f), w("Tea", 100f, 40f), w("4,00", 200f, 40f),
        )
        val rows = TableExtraction.rows(words)
        assertEquals(listOf(listOf("Date", "Item", "Amount"), listOf("01.02.", "Coffee beans", "12,50"), listOf("03.02.", "Tea", "4,00")), rows)
        assertEquals("Date,Item,Amount\r\n01.02.,Coffee beans,\"12,50\"\r\n03.02.,Tea,\"4,00\"\r\n", TableExtraction.csv(rows))
    }
}
