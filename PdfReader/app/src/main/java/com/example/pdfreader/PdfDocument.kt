package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Thin wrapper around [PdfRenderer]. The platform renderer only allows one page
 * to be open at a time and is not thread-safe, so every access goes through [mutex].
 */
class PdfDocument private constructor(
    private val fileDescriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    val pageCount: Int = renderer.pageCount

    /** Page sizes in PDF points, read once up front so the list can lay out before rendering. */
    val pageSizes: List<Size> = (0 until pageCount).map { index ->
        val page = renderer.openPage(index)
        try {
            Size(page.width, page.height)
        } finally {
            page.close()
        }
    }

    private val mutex = Mutex()
    private var closed = false

    suspend fun renderPage(index: Int, targetWidth: Int): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "Document is closed" }
            val page = renderer.openPage(index)
            try {
                val height = (targetWidth.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                // PDFs assume a white sheet; transparent areas would otherwise render black.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        }
    }

    /** Waits for any in-flight render (at most one page) so the renderer is never closed mid-draw. */
    override fun close() = runBlocking {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            renderer.close()
            fileDescriptor.close()
        }
    }

    companion object {
        /**
         * Opens [uri] for rendering. PdfRenderer needs a seekable descriptor; some providers
         * (e.g. email attachments) hand out pipes, so on failure we copy to the cache dir.
         *
         * @throws PasswordProtectedException if the PDF is encrypted.
         * @throws IOException if the file cannot be read or is not a valid PDF.
         */
        suspend fun open(context: Context, uri: Uri): PdfDocument = withContext(Dispatchers.IO) {
            try {
                openDescriptor(context.contentResolver.openFileDescriptor(uri, "r"))
            } catch (e: PasswordProtectedException) {
                throw e
            } catch (e: Exception) {
                val copy = File(context.cacheDir, "current.pdf")
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot read $uri", e)
                input.use { src -> copy.outputStream().use { dst -> src.copyTo(dst) } }
                openDescriptor(ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY))
            }
        }

        private fun openDescriptor(pfd: ParcelFileDescriptor?): PdfDocument {
            pfd ?: throw IOException("No file descriptor")
            return try {
                PdfDocument(pfd, PdfRenderer(pfd))
            } catch (e: SecurityException) {
                // PdfRenderer signals encrypted documents with SecurityException.
                pfd.close()
                throw PasswordProtectedException(e)
            } catch (e: Throwable) {
                pfd.close()
                throw e
            }
        }
    }
}

class PasswordProtectedException(cause: Throwable) : IOException("PDF is password-protected", cause)
