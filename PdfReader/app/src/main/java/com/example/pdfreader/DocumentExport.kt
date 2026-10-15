package com.example.pdfreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Sharing, saving and printing the open document as-is. */
object DocumentExport {

    /** A file name safe for the cache directory, ending in .pdf. */
    fun safeFileName(name: String): String {
        val base = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "Document" }
        return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
    }

    /** Copies the document into the shareable cache folder and returns a content URI for it. */
    suspend fun shareableCopy(context: Context, source: Uri, name: String): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared/export").apply { mkdirs() }
        val file = File(dir, safeFileName(name))
        copy(context, source, file.outputStream())
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** A chooser for sending the document to another app. */
    fun shareIntent(context: Context, uri: Uri, name: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(name, uri)
        return Intent.createChooser(send, context.getString(R.string.share))
    }

    /** Writes the document to a location the user picked (Save a copy). */
    suspend fun saveCopy(context: Context, source: Uri, destination: Uri) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(destination, "w") ?: throw IOException("Cannot write $destination")
        copy(context, source, output)
    }

    private fun copy(context: Context, source: Uri, output: java.io.OutputStream) {
        val input = context.contentResolver.openInputStream(source) ?: throw IOException("Cannot read $source")
        input.use { src -> output.use { dst -> src.copyTo(dst) } }
    }
}

/**
 * Prints the PDF file through Android's print service. The file is passed through unchanged;
 * the print dialog's page-range selection is applied by the print system.
 */
class PdfPrintAdapter(
    private val context: Context,
    private val source: Uri,
    private val name: String,
    private val pageCount: Int,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(DocumentExport.safeFileName(name))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pageCount)
            .build()
        callback.onLayoutFinished(info, oldAttributes == null)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        // Copy off the main thread; large files would otherwise freeze the print dialog.
        Thread {
            try {
                val input = context.contentResolver.openInputStream(source) ?: throw IOException("Cannot read $source")
                input.use { src ->
                    FileOutputStream(destination.fileDescriptor).use { dst ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancellationSignal.isCanceled) {
                                callback.onWriteCancelled()
                                return@Thread
                            }
                            val read = src.read(buffer)
                            if (read < 0) break
                            dst.write(buffer, 0, read)
                        }
                    }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }.start()
    }
}
