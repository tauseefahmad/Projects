package com.example.pdfreader

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.pdfreader.core.BlankSize
import com.example.pdfreader.core.PageRef
import com.example.pdfreader.core.PageRotation
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Builds a PDF from a list of [PageRef]s taken from one or more source files (page management:
 * reorder, rotate, delete, insert, duplicate, extract, merge, split). Pages are copied as they
 * are — text stays text — and the first source's outline and metadata are carried over.
 */
object PageAssembler {

    private fun load(context: Context, uri: Uri): PDDocument {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
        return input.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
    }

    /**
     * Writes [pages] (from [sources], indexed by [PageRef.source]) to [output]. Blank pages are
     * created at their size. Outline entries of source 0 follow their page if it is kept.
     */
    fun assemble(context: Context, sources: List<Uri>, pages: List<PageRef>, output: OutputStream) {
        val opened = HashMap<Int, PDDocument>()
        fun source(index: Int) = opened.getOrPut(index) { load(context, sources[index]) }
        try {
            PDDocument().use { target ->
                // New page for the first copy of each original page of source 0, for the outline.
                val firstCopyOf = HashMap<Int, PDPage>()
                for (ref in pages) {
                    if (ref.isBlank) {
                        target.addPage(PDPage(PDRectangle(ref.blankWidth, ref.blankHeight)))
                        continue
                    }
                    val original = source(ref.source).getPage(ref.page)
                    val copy = target.importPage(original)
                    copy.rotation = PageRotation.normalize(original.rotation + ref.rotation)
                    if (ref.source == 0) firstCopyOf.putIfAbsent(ref.page, copy)
                }
                opened[0]?.let { first ->
                    first.documentInformation?.let { target.documentInformation = it }
                    copyOutline(first, target, firstCopyOf)
                }
                target.save(output)
            }
        } finally {
            opened.values.forEach { runCatching { it.close() } }
        }
    }

    /** Recreates [from]'s outline in [to], pointing entries at the kept pages and dropping the rest. */
    private fun copyOutline(from: PDDocument, to: PDDocument, newPageFor: Map<Int, PDPage>) {
        val outline = from.documentCatalog.documentOutline ?: return
        val result = PDDocumentOutline()
        fun copyChildren(source: PDOutlineNode, target: PDOutlineNode, depth: Int) {
            if (depth > 16) return
            for (item in source.children()) {
                val page = runCatching { item.findDestinationPage(from) }.getOrNull()?.let { from.pages.indexOf(it) }
                val newPage = page?.let { newPageFor[it] }
                val entry = PDOutlineItem().apply { title = item.title }
                if (newPage != null) entry.destination = PDPageFitDestination().apply { this.page = newPage }
                copyChildren(item, entry, depth + 1)
                // Keep entries whose page survived, and parents of surviving entries.
                if (newPage != null || entry.hasChildren()) target.addLast(entry)
            }
        }
        copyChildren(outline, result, 0)
        if (result.hasChildren()) to.documentCatalog.documentOutline = result
    }

    /** Number of pages in a PDF, e.g. one being inserted. */
    fun pageCount(context: Context, uri: Uri): Int = load(context, uri).use { it.numberOfPages }

    /**
     * Turns an image (JPEG, PNG, WebP…) into a one-page PDF in the cache: the image is centred on
     * an A4 page (landscape for wide images) with a small margin. Returns the file's URI.
     */
    fun imageToPdf(context: Context, image: Uri): Uri {
        PDFBoxResourceLoader.init(context.applicationContext)
        val bitmap = context.contentResolver.openInputStream(image)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IOException("Not an image: $image")
        val isJpeg = context.contentResolver.getType(image) == "image/jpeg"
        val file = File(File(context.cacheDir, "shared/images").apply { mkdirs() }, "${UUID.randomUUID()}.pdf")
        PDDocument().use { doc ->
            val landscape = bitmap.width > bitmap.height
            val size = BlankSize.A4
            val pageWidth = if (landscape) size.height else size.width
            val pageHeight = if (landscape) size.width else size.height
            val page = PDPage(PDRectangle(pageWidth, pageHeight))
            doc.addPage(page)
            // JPEGs stay compressed; other formats are stored losslessly.
            val xObject = if (isJpeg) {
                context.contentResolver.openInputStream(image)!!.use { JPEGFactory.createFromStream(doc, it) }
            } else {
                LosslessFactory.createFromImage(doc, bitmap)
            }
            val margin = 24f
            val scale = minOf((pageWidth - 2 * margin) / bitmap.width, (pageHeight - 2 * margin) / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            PDPageContentStream(doc, page).use { it.drawImage(xObject, (pageWidth - w) / 2, (pageHeight - h) / 2, w, h) }
            doc.save(file)
        }
        bitmap.recycle()
        return Uri.fromFile(file)
    }
}
