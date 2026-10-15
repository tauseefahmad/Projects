package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsTest {

    @Test
    fun acceptsWebAddresses() {
        assertEquals("https://example.com/a.pdf", Downloads.parseUrl(" https://example.com/a.pdf "))
        assertEquals("http://127.0.0.1:8080/x", Downloads.parseUrl("http://127.0.0.1:8080/x"))
        assertEquals("https://example.com/doc.pdf", Downloads.parseUrl("example.com/doc.pdf"))
    }

    @Test
    fun rejectsOtherSchemesAndJunk() {
        assertNull(Downloads.parseUrl("file:///sdcard/a.pdf"))
        assertNull(Downloads.parseUrl("ftp://example.com/a.pdf"))
        assertNull(Downloads.parseUrl("not a url"))
        assertNull(Downloads.parseUrl(""))
    }

    @Test
    fun fileNameFromHeaderOrPath() {
        assertEquals("report.pdf", Downloads.fileName("https://x.de/files/report.pdf?dl=1", null))
        assertEquals("Q3 results.pdf", Downloads.fileName("https://x.de/d", "attachment; filename=\"Q3 results.pdf\""))
        assertEquals("Übersicht.pdf", Downloads.fileName("https://x.de/d", "attachment; filename*=UTF-8''%C3%9Cbersicht.pdf"))
        assertEquals("invoice.pdf", Downloads.fileName("https://x.de/invoice", null))
        assertEquals("Download.pdf", Downloads.fileName("https://x.de/", null))
    }

    @Test
    fun fileNameCannotEscapeTheFolder() {
        assertEquals("evil.pdf", Downloads.fileName("https://x.de/d", "attachment; filename=\"../../evil.pdf\""))
    }

    @Test
    fun recognizesPdfSignature() {
        assertTrue(Downloads.looksLikePdf("%PDF-1.7\n...".toByteArray()))
        assertTrue(Downloads.looksLikePdf("\n\n%PDF-1.4".toByteArray()))
        assertFalse(Downloads.looksLikePdf("<html><body>Not found</body></html>".toByteArray()))
    }
}
