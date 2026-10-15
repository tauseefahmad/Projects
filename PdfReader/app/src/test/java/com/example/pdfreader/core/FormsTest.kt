package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FormsTest {

    private fun text(name: String, label: String = "", page: Int = 0, top: Float = 0f, left: Float = 0f, value: String = "",
                     format: FieldFormat = FieldFormat.PLAIN, required: Boolean = false, maxLength: Int = 0, datePattern: String = "") =
        FormField(name, FieldKind.TEXT, page, Box(left, top, left + 100, top + 20), value, label, required = required,
            maxLength = maxLength, format = format, datePattern = datePattern)

    @Test
    fun formatComesFromAcrobatScriptsFirst() {
        assertEquals(FieldFormat.DATE to "dd.MM.yyyy", FormFormats.detect("Field1", "", "AFDate_FormatEx(\"dd.mm.yyyy\");"))
        assertEquals(FieldFormat.NUMBER, FormFormats.detect("x", "", "AFNumber_Format(2, 0, 0, 0, \"€\", true);").first)
        assertEquals(FieldFormat.PHONE, FormFormats.detect("x", "", "AFSpecial_Format(2);").first)
    }

    @Test
    fun formatIsGuessedFromNamesInEnglishAndGerman() {
        assertEquals(FieldFormat.EMAIL, FormFormats.detect("contactEmail", "", null).first)
        assertEquals(FieldFormat.EMAIL, FormFormats.detect("E-Mail", "", null).first)
        assertEquals(FieldFormat.DATE, FormFormats.detect("Geburtsdatum", "", null).first)
        assertEquals(FieldFormat.PHONE, FormFormats.detect("Telefon", "", null).first)
        assertEquals(FieldFormat.NUMBER, FormFormats.detect("PLZ", "", null).first)
        assertEquals(FieldFormat.PLAIN, FormFormats.detect("Vorname", "", null).first)
    }

    @Test
    fun acrobatMonthsAndMinutesAreSwapped() {
        assertEquals("MM/dd/yyyy HH:mm", FormFormats.acrobatDatePattern("mm/dd/yyyy HH:MM"))
        assertEquals("MMM d, yyyy", FormFormats.acrobatDatePattern("mmm d, yyyy"))
    }

    @Test
    fun validation() {
        assertEquals(FieldProblem.REQUIRED, FormValidation.problem(text("a", required = true)))
        assertNull(FormValidation.problem(text("a")))
        assertEquals(FieldProblem.TOO_LONG, FormValidation.problem(text("a", value = "abcdef", maxLength = 5)))
        assertEquals(FieldProblem.NOT_AN_EMAIL, FormValidation.problem(text("a", value = "me@", format = FieldFormat.EMAIL)))
        assertNull(FormValidation.problem(text("a", value = "me@example.de", format = FieldFormat.EMAIL)))
        assertEquals(FieldProblem.NOT_A_NUMBER, FormValidation.problem(text("a", value = "12a", format = FieldFormat.NUMBER)))
        assertNull(FormValidation.problem(text("a", value = "1.234,50", format = FieldFormat.NUMBER)))
        assertEquals(FieldProblem.NOT_A_PHONE, FormValidation.problem(text("a", value = "12", format = FieldFormat.PHONE)))
        assertNull(FormValidation.problem(text("a", value = "+49 30 1234567", format = FieldFormat.PHONE)))
        assertEquals(FieldProblem.NOT_A_DATE, FormValidation.problem(text("a", value = "31.02.2024", format = FieldFormat.DATE)))
        assertNull(FormValidation.problem(text("a", value = "29.02.2024", format = FieldFormat.DATE)))
        assertNull(FormValidation.problem(text("a", value = "03/15/2024", format = FieldFormat.DATE, datePattern = "MM/dd/yyyy")))
    }

    @Test
    fun numbersInEitherNotation() {
        assertEquals(1234.5, FormValidation.parseNumber("1,234.50")!!, 0.001)
        assertEquals(1234.5, FormValidation.parseNumber("1.234,50")!!, 0.001)
        assertEquals(1234.0, FormValidation.parseNumber("1,234")!!, 0.001)
        assertEquals(12.5, FormValidation.parseNumber("12,5")!!, 0.001)
        assertEquals(1234567.0, FormValidation.parseNumber("1.234.567")!!, 0.001)
        assertNull(FormValidation.parseNumber("abc"))
    }

    @Test
    fun datesFormatTheWayTheFieldWants() {
        val millis = FormValidation.parseDate("2024-03-15")!!
        assertEquals("15.03.2024", FormValidation.formatDate(millis, ""))
        assertEquals("03/15/2024", FormValidation.formatDate(millis, "MM/dd/yyyy"))
    }

    @Test
    fun nextFieldGoesAlongRowsThenDownThenToTheNextPage() {
        val a = text("a", top = 100f, left = 300f)
        val b = text("b", top = 102f, left = 50f)
        val c = text("c", top = 200f, left = 50f)
        val d = text("d", page = 1, top = 10f)
        val fields = listOf(d, c, a, b)
        assertEquals(listOf("b", "a", "c", "d"), FormNavigation.order(fields).map { it.name })
        assertEquals("a", FormNavigation.next(fields, b)?.name)
        assertEquals("d", FormNavigation.next(fields, c)?.name)
        assertNull(FormNavigation.next(fields, d))
        assertEquals("b", FormNavigation.next(fields, a, forward = false)?.name)
        assertEquals("b", FormNavigation.next(fields, null)?.name)
    }

    @Test
    fun autofillRecognisesEnglishAndGermanFields() {
        val cases = mapOf(
            "Vorname" to ProfileKey.FIRST_NAME, "firstName" to ProfileKey.FIRST_NAME, "Nachname" to ProfileKey.LAST_NAME,
            "last_name" to ProfileKey.LAST_NAME, "Name" to ProfileKey.FULL_NAME, "Straße und Hausnummer" to ProfileKey.STREET,
            "PLZ" to ProfileKey.POSTAL_CODE, "Wohnort" to ProfileKey.CITY, "PLZ_Ort" to ProfileKey.POSTAL_CODE_CITY,
            "E-Mail-Adresse" to ProfileKey.EMAIL, "Telefonnummer" to ProfileKey.PHONE, "Geburtsdatum" to ProfileKey.BIRTH_DATE,
            "Steuer-ID" to ProfileKey.TAX_ID, "Tax ID" to ProfileKey.TAX_ID, "zipCode" to ProfileKey.POSTAL_CODE,
            "Country" to ProfileKey.COUNTRY, "Firma" to ProfileKey.COMPANY,
        )
        for ((name, key) in cases) assertEquals(name, key, Autofill.keyFor(text(name)))
        assertNull(Autofill.keyFor(text("Field17")))
    }

    @Test
    fun autofillLeavesFilledFieldsAlone() {
        val profile = AutofillProfile(firstName = "Erika", lastName = "Mustermann", postalCode = "10115", city = "Berlin")
        val fields = listOf(text("Vorname"), text("Nachname", value = "Already"), text("PLZ Ort", maxLength = 8), text("Notes"))
        assertEquals(mapOf("Vorname" to "Erika", "PLZ Ort" to "10115 Be"), Autofill.fill(fields, profile))
        assertNotNull(Autofill.fill(fields, profile, overwrite = true)["Nachname"])
    }
}
