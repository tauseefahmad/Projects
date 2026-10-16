package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.example.pdfreader.core.TextExport
import com.example.pdfreader.core.TextFormat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.IdentityHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** How hard to compress: the longest side of pictures, in pixels, and the JPEG quality. */
enum class CompressionPreset(val maxPixels: Int, val quality: Float) {
    HIGH_QUALITY(2400, 0.85f),
    BALANCED(1600, 0.7f),
    SMALLEST(1000, 0.5f),
}

/** Picture formats pages can be exported to. */
enum class ImageFormat(val extension: String, val mimeType: String) { PNG("png", "image/png"), JPG("jpg", "image/jpeg") }

/** Phase 5 tools that run on the phone: compression, export and creating PDFs from pictures. */
object ConvertTools {

    // --- Compression ---------------------------------------------------------------------------

    /**
     * Shrinks the document's pictures to [preset] and re-encodes them as JPEG where that makes
     * them smaller. Saving also leaves out objects nothing refers to any more. Pictures with
     * transparency, masks or 1-bit pictures (fax-style scans) are kept as they are.
     * Returns how many pictures were made smaller.
     */
    fun compress(doc: PDDocument, preset: CompressionPreset): Int {
        val done = IdentityHashMap<COSBase, PDImageXObject>()
        var changed = 0
        val seenForms = IdentityHashMap<COSBase, Boolean>()
        fun visit(resources: PDResources?) {
            resources ?: return
            for (name in resources.xObjectNames.toList()) {
                val x = runCatching { resources.getXObject(name) }.getOrNull() ?: continue
                when (x) {
                    is PDImageXObject -> {
                        val replacement = done.getOrPut(x.cosObject) {
                            runCatching { shrink(doc, x, preset) }.getOrNull()?.also { changed++ } ?: x
                        }
                        if (replacement !== x) resources.put(name, replacement)
                    }
                    is PDFormXObject -> if (seenForms.put(x.cosObject, true) == null) visit(x.resources)
                }
            }
        }
        for (page in doc.pages) visit(page.resources)
        return changed
    }

    private fun shrink(doc: PDDocument, image: PDImageXObject, preset: CompressionPreset): PDImageXObject? {
        val dict = image.cosObject
        if (image.isStencil || image.bitsPerComponent == 1 || dict.containsKey(COSName.SMASK) || dict.containsKey(COSName.MASK)) return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val scale = minOf(1f, preset.maxPixels.toFloat() / max(width, height))
        val source = image.image ?: return null
        val targetW = (width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (height * scale).roundToInt().coerceAtLeast(1)
        // Opaque RGB on white, as JPEG has no transparency.
        val opaque = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(Bitmap.createScaledBitmap(source, targetW, targetH, true), 0f, 0f, null)
        }
        val result = JPEGFactory.createFromImage(doc, opaque, preset.quality)
        opaque.recycle()
        val before = dict.length
        val after = result.cosObject.length
        return if (after < before) result else null
    }

    // --- Export -------------------------------------------------------------------------------

    /** Renders [pages] (0-based) of [file] at [dpi]; returns the files, named after [baseName]. */
    fun exportImages(file: File, pages: List<Int>, dpi: Int, format: ImageFormat, baseName: String, dir: File): List<File> {
        val result = mutableListOf<File>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val digits = renderer.pageCount.toString().length
                for (index in pages) {
                    if (index !in 0 until renderer.pageCount) continue
                    renderer.openPage(index).use { page ->
                        val scale = dpi / 72f
                        val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1), (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val out = File(dir, "$baseName-${(index + 1).toString().padStart(digits, '0')}.${format.extension}")
                        out.outputStream().use {
                            if (format == ImageFormat.PNG) bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            else bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                        }
                        bitmap.recycle()
                        result += out
                    }
                }
            }
        }
        return result
    }

    /** The text of every page, in reading order. */
    fun pageTexts(doc: PDDocument): List<String> {
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        return (1..doc.numberOfPages).map { page ->
            stripper.startPage = page
            stripper.endPage = page
            runCatching { stripper.getText(doc) }.getOrDefault("")
        }
    }

    fun exportText(doc: PDDocument, title: String, format: TextFormat, output: OutputStream) {
        val pages = pageTexts(doc)
        when (format) {
            TextFormat.TEXT -> output.write(TextExport.text(pages).toByteArray(Charsets.UTF_8))
            TextFormat.MARKDOWN -> output.write(TextExport.markdown(title, pages).toByteArray(Charsets.UTF_8))
            TextFormat.HTML -> output.write(TextExport.html(title, pages).toByteArray(Charsets.UTF_8))
            TextFormat.WORD -> TextExport.docx(title, pages, output)
        }
    }

    fun zip(files: List<File>, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    // --- Pictures to PDF ----------------------------------------------------------------------

    /** Longest side of pictures put into a PDF. */
    private const val MAX_PICTURE_PIXELS = 2500

    /**
     * One A4 page per picture (portrait or landscape to match it), the picture fitted inside
     * a small margin. Handles JPEG, PNG, WebP and, from Android 9, HEIC; photos are turned
     * the right way up.
     */
    fun imagesToPdf(context: Context, uris: List<Uri>, output: OutputStream) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            for (uri in uris) {
                val bitmap = decode(context, uri) ?: throw IOException("Cannot read picture $uri")
                val landscape = bitmap.width > bitmap.height
                val size = if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
                val page = PDPage(size)
                doc.addPage(page)
                val image = JPEGFactory.createFromImage(doc, opaque(bitmap), 0.85f)
                val margin = 18f
                val scale = minOf((size.width - 2 * margin) / bitmap.width, (size.height - 2 * margin) / bitmap.height)
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                PDPageContentStream(doc, page).use { it.drawImage(image, (size.width - w) / 2, (size.height - h) / 2, w, h) }
                bitmap.recycle()
            }
            doc.save(output)
        }
    }

    private fun opaque(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return result
    }

    /** Decodes a picture, at most [MAX_PICTURE_PIXELS] on its longest side, the right way up. */
    fun decode(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val longest = max(info.size.width, info.size.height)
                    if (longest > MAX_PICTURE_PIXELS) {
                        val s = MAX_PICTURE_PIXELS.toFloat() / longest
                        decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PICTURE_PIXELS) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
