package com.example.pdfreader

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.pdfreader.core.Downloads
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Thrown for downloads that fail; [message] is a string resource to show. */
class DownloadException(val messageRes: Int, cause: Throwable? = null) : IOException(cause)

/** Downloads a PDF from the web into the app's shareable cache. */
object PdfDownloader {

    private const val MAX_REDIRECTS = 5

    /**
     * Downloads [url] and returns a content URI for the file and its name. Rejects responses
     * that aren't PDFs or are larger than [Downloads.MAX_BYTES]. Blocking; call off the main thread.
     */
    fun download(context: Context, url: String, onProgress: (read: Long, total: Long) -> Unit): Pair<Uri, String> {
        var current = URL(url)
        var connection: HttpURLConnection
        var redirects = 0
        while (true) {
            connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/pdf,*/*")
            }
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                throw DownloadException(R.string.download_failed, e)
            }
            if (code in 300..399 && redirects < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location") ?: throw DownloadException(R.string.download_failed)
                connection.disconnect()
                val next = URL(current, location)
                if (next.protocol != "http" && next.protocol != "https") throw DownloadException(R.string.download_failed)
                current = next
                redirects++
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw DownloadException(R.string.download_failed)
            }
            break
        }

        try {
            val total = connection.contentLengthLong
            if (total > Downloads.MAX_BYTES) throw DownloadException(R.string.download_too_large)
            val name = DocumentExport.safeFileName(Downloads.fileName(current.toString(), connection.getHeaderField("Content-Disposition")))
            val dir = File(context.cacheDir, "shared/downloads").apply { mkdirs() }
            val partial = File(dir, "${UUID.randomUUID()}.part")
            try {
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        var checkedSignature = false
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (!checkedSignature) {
                                if (!Downloads.looksLikePdf(buffer, count)) throw DownloadException(R.string.download_not_pdf)
                                checkedSignature = true
                            }
                            read += count
                            if (read > Downloads.MAX_BYTES) throw DownloadException(R.string.download_too_large)
                            output.write(buffer, 0, count)
                            onProgress(read, total)
                        }
                        if (!checkedSignature) throw DownloadException(R.string.download_not_pdf)
                    }
                }
                val file = uniqueFile(dir, name)
                if (!partial.renameTo(file)) throw DownloadException(R.string.download_failed)
                return FileProvider.getUriForFile(context, "${context.packageName}.files", file) to file.name
            } catch (e: DownloadException) {
                throw e
            } catch (e: IOException) {
                throw DownloadException(R.string.download_failed, e)
            } finally {
                partial.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        var n = 1
        while (file.exists()) {
            file = File(dir, name.removeSuffix(".pdf") + " ($n).pdf")
            n++
        }
        return file
    }
}

/** Opens password-protected PDFs by writing a decrypted copy to the app's private cache. */
object PdfUnlocker {

    /**
     * Decrypts [uri] with [password] (empty for files protected only by an owner password).
     * @throws com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException if it is wrong.
     */
    fun unlock(context: Context, uri: Uri, password: String): File {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
        val document = input.use { PDDocument.load(it, password, MemoryUsageSetting.setupTempFileOnly()) }
        document.use { doc ->
            doc.isAllSecurityToBeRemoved = true
            val dir = File(context.cacheDir, "unlocked").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.pdf")
            doc.save(file)
            return file
        }
    }
}
