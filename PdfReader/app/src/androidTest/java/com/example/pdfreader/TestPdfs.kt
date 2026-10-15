package com.example.pdfreader

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Builds small PDFs on the device for tests, shared through the app's FileProvider. */
object TestPdfs {

    fun create(context: Context, name: String, pageCount: Int, width: Int = 595, height: Int = 842): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, name)
        val document = android.graphics.pdf.PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 48f
        }
        repeat(pageCount) { index ->
            val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, index + 1).create())
            page.canvas.drawText("Page ${index + 1}", 72f, 144f, paint)
            document.finishPage(page)
        }
        file.outputStream().use { document.writeTo(it) }
        document.close()
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** A PDF with real (extractable) text: one entry of [pages] per page, one line each. */
    fun createWithText(context: Context, name: String, pages: List<String>): Uri {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, name)
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
            for (line in pages) {
                val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)
                document.addPage(page)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 18f)
                    stream.newLineAtOffset(72f, 700f)
                    stream.showText(line)
                    stream.endText()
                }
            }
            document.save(file)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** An outline entry for [createWithOutline]: [page] is 0-based. */
    class Entry(val title: String, val page: Int, val children: List<Entry> = emptyList())

    /** A PDF with a built-in table of contents, written with PdfBox. */
    fun createWithOutline(context: Context, name: String, pageCount: Int, outline: List<Entry>): Uri {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, name)
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
            val pages = List(pageCount) {
                com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4).also(document::addPage)
            }
            val root = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
            document.documentCatalog.documentOutline = root
            fun add(parent: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode, entries: List<Entry>) {
                for (entry in entries) {
                    val item = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem()
                    item.title = entry.title
                    item.setDestination(pages[entry.page])
                    parent.addLast(item)
                    add(item, entry.children)
                }
            }
            add(root, outline)
            document.save(file)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}
