package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionsTest {

    private fun found(text: String, vararg kinds: SensitiveKind, terms: List<String> = emptyList()) =
        Redactions.find(text, kinds.toSet(), terms).map { text.substring(it) }

    @Test
    fun ibansAreFoundWithAndWithoutSpacesAndCheckedByChecksum() {
        assertTrue(Redactions.isValidIban("DE89 3704 0044 0532 0130 00"))
        assertTrue(Redactions.isValidIban("GB82WEST12345698765432"))
        assertFalse(Redactions.isValidIban("DE88 3704 0044 0532 0130 00"))
        assertEquals(
            listOf("DE89 3704 0044 0532 0130 00", "GB82WEST12345698765432"),
            found("Pay to DE89 3704 0044 0532 0130 00 or GB82WEST12345698765432, not DE88 3704 0044 0532 0130 00.", SensitiveKind.IBAN),
        )
    }

    @Test
    fun emailsAndPhoneNumbers() {
        val text = "Mail anna.schmidt@example.de or call +49 (0)30 123456-78, office 030/1234567. Due 2024-05-01, ref 12345."
        assertEquals(listOf("anna.schmidt@example.de"), found(text, SensitiveKind.EMAIL))
        assertEquals(listOf("+49 (0)30 123456-78", "030/1234567"), found(text, SensitiveKind.PHONE))
    }

    @Test
    fun phoneDoesNotSwallowIbans() {
        val text = "IBAN DE89 3704 0044 0532 0130 00"
        assertEquals(listOf("DE89 3704 0044 0532 0130 00"), found(text, SensitiveKind.IBAN, SensitiveKind.PHONE))
    }

    @Test
    fun termsIgnoreCaseAndLineBreaks() {
        val text = "Max Mustermann signed.\nMAX\nMUSTERMANN agreed."
        assertEquals(listOf("Max Mustermann", "MAX\nMUSTERMANN"), found(text, terms = listOf("max mustermann")))
        assertEquals(listOf(0..4), Redactions.merge(listOf(0..2, 2..4)))
        assertEquals(listOf(0..2, 4..5), Redactions.merge(listOf(4..5, 0..2)))
    }

    @Test
    fun redactionsBehaveLikeAnnotations() {
        val r = Annotation.Redaction("r", 0, 0, "", 0, box = Box(0f, 0f, 10f, 10f))
        assertEquals("Redaction", r.typeName())
        assertEquals(Box(5f, 5f, 15f, 15f), r.moved(5f, 5f).bounds)
        assertEquals(2, AnnotationTransform.copyTo(r, 2, "s").page)
    }
}
