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

    /**
     * A 3-page PDF whose first page has two links: an internal one to page 3 covering
     * (72, 100)–(272, 140) pt from the top-left, and one to https://example.com/ at (72, 200)–(272, 240).
     */
    fun createWithLinks(context: Context, name: String): Uri {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, name)
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
            val a4 = com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4
            val pages = List(3) { com.tom_roush.pdfbox.pdmodel.PDPage(a4).also(document::addPage) }
            val height = a4.height
            fun rect(top: Float, bottom: Float) = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(72f, height - bottom, 200f, bottom - top)

            val internal = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink()
            internal.rectangle = rect(100f, 140f)
            internal.action = com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo().apply {
                destination = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination().apply { page = pages[2] }
            }
            val external = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink()
            external.rectangle = rect(200f, 240f)
            external.action = com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI().apply { uri = "https://example.com/" }
            pages[0].annotations = listOf(internal, external)
            document.save(file)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** A 3-page A4 PDF with document information (title, author, subject) set. */
    fun createWithInfo(context: Context, name: String, title: String, author: String, subject: String): Uri {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, name)
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
            repeat(3) { document.addPage(com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)) }
            document.documentInformation.apply {
                this.title = title
                this.author = author
                this.subject = subject
            }
            document.save(file)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** An encrypted PDF. With an empty [userPassword] it opens without asking (owner-only protection). */
    fun createEncrypted(context: Context, name: String, pageCount: Int, userPassword: String, ownerPassword: String = "owner-secret"): Uri {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, name)
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
            repeat(pageCount) { document.addPage(com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)) }
            val policy = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                ownerPassword, userPassword, com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission(),
            )
            policy.encryptionKeyLength = 128
            document.protect(policy)
            document.save(file)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** Bytes of a plain generated PDF, e.g. to serve over HTTP in tests. */
    fun bytes(context: Context, pageCount: Int): ByteArray {
        create(context, "bytes-$pageCount.pdf", pageCount)
        return File(context.cacheDir, "shared/bytes-$pageCount.pdf").readBytes()
    }

    /** Each page's extracted text (trimmed), read back with PdfBox. */
    fun pageTexts(context: Context, uri: Uri): List<String> {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        return context.contentResolver.openInputStream(uri)!!.use { input ->
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
                (1..doc.numberOfPages).map { n ->
                    com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = n; endPage = n }.getText(doc).trim()
                }
            }
        }
    }

    /** Each page's crop box size (width, height) in points. */
    fun pageSizes(context: Context, uri: Uri): List<Pair<Float, Float>> {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        return context.contentResolver.openInputStream(uri)!!.use { input ->
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc -> doc.pages.map { it.cropBox.width to it.cropBox.height } }
        }
    }

    /** Top-level outline titles. */
    fun outlineTitles(context: Context, uri: Uri): List<String> {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        return context.contentResolver.openInputStream(uri)!!.use { input ->
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
                doc.documentCatalog.documentOutline?.children()?.map { it.title } ?: emptyList()
            }
        }
    }

    /** Each page's /Rotate value. */
    fun pageRotations(context: Context, uri: Uri): List<Int> {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        return context.contentResolver.openInputStream(uri)!!.use { input ->
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc -> doc.pages.map { it.rotation } }
        }
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
