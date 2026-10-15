package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun bytes() {
        assertEquals("512 B", Formatting.bytes(512))
        assertEquals("1.5 KB", Formatting.bytes(1536))
        assertEquals("14 KB", Formatting.bytes(14 * 1024 + 100))
        assertEquals("3.4 MB", Formatting.bytes((3.4 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", Formatting.bytes(1024L * 1024 * 1024))
    }

    @Test
    fun recognizesCommonPaperSizes() {
        assertEquals("A4 (210 × 297 mm)", Formatting.pageSize(595f, 842f))
        assertEquals("A4 landscape (297 × 210 mm)", Formatting.pageSize(842f, 595f))
        assertEquals("Letter (8.5 × 11 in)", Formatting.pageSize(612f, 792f))
        assertEquals("A5 (148 × 210 mm)", Formatting.pageSize(420f, 595f))
    }

    @Test
    fun otherSizesInMillimetres() {
        assertEquals("100 × 200 mm", Formatting.pageSize(283.46f, 566.93f))
    }
}
