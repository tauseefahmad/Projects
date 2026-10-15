package com.example.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentExportTest {

    @Test
    fun keepsPdfNames() {
        assertEquals("Report.pdf", DocumentExport.safeFileName("Report.pdf"))
        assertEquals("Report.PDF", DocumentExport.safeFileName("Report.PDF"))
    }

    @Test
    fun addsExtensionAndReplacesUnsafeCharacters() {
        assertEquals("a_b_c.pdf", DocumentExport.safeFileName("a/b:c"))
        assertEquals("Document.pdf", DocumentExport.safeFileName("  "))
    }
}
