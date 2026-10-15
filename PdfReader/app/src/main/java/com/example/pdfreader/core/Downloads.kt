package com.example.pdfreader.core

import java.net.URI
import java.net.URLDecoder

object Downloads {

    /** Largest download accepted, to protect storage. */
    const val MAX_BYTES = 200L * 1024 * 1024

    /** The URL to download, or null if it isn't a web address. Adds https:// when missing. */
    fun parseUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = if (text.contains("://")) text else "https://$text"
        return try {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()) withScheme else null
        } catch (e: Exception) {
            null
        }
    }

    /** A file name from the Content-Disposition header, else the URL's last path segment. */
    fun fileName(url: String, contentDisposition: String?): String {
        val fromHeader = contentDisposition?.let { header ->
            Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
                ?.let { runCatching { URLDecoder.decode(it.trim('"', ' '), "UTF-8") }.getOrNull() }
                ?: Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim()
        }
        val fromPath = runCatching { URI(url).path }.getOrNull()
            ?.substringAfterLast('/')
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val name = (fromHeader ?: fromPath)?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() } ?: "Download"
        return if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf"
    }

    /** PDF files start with "%PDF-" (possibly after a few junk bytes, which readers tolerate). */
    fun looksLikePdf(head: ByteArray, length: Int = head.size): Boolean {
        val text = String(head, 0, minOf(length, 1024), Charsets.ISO_8859_1)
        return text.contains("%PDF-")
    }
}
