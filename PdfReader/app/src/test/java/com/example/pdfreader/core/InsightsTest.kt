package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {

    @Test
    fun sentencesKeepAbbreviationsAndDates() {
        val s = Sentences.split(listOf("Die Zahlung erfolgt z. B. per Überweisung bis zum 15. März 2025. Danach gilt Nr. 3 der AGB. Fragen? Rufen Sie an!"))
        assertEquals(
            listOf("Die Zahlung erfolgt z. B. per Überweisung bis zum 15. März 2025.", "Danach gilt Nr. 3 der AGB.", "Fragen?", "Rufen Sie an!"),
            s.map { it.text },
        )
    }

    @Test
    fun summaryPicksRepresentativeSentencesInOrder() {
        val pages = listOf(
            "Solar panels convert sunlight into electricity for homes. The weather was nice on Tuesday afternoon in the park. " +
                "Modern solar panels reach an efficiency of over twenty percent in sunlight. My neighbour owns a very old red bicycle with a bell.",
            "Electricity from solar panels can be stored in home batteries for the night. Installing solar panels on homes lowers electricity bills.",
        )
        val summary = Summarizer.summarize(pages, SummaryLength.PARAGRAPH)
        assertEquals(3, summary.size)
        assertTrue(summary.all { "solar" in it.text.lowercase() })
        assertEquals(summary.sortedBy { it.index }, summary)
        val bySection = Summarizer.summarize(pages, SummaryLength.BY_SECTION)
        assertEquals(setOf(0, 1), bySection.map { it.page }.toSet())
        assertTrue(Summarizer.markdown("Solar", summary, SummaryLength.PARAGRAPH).startsWith("# Summary — Solar\n\n- "))
    }

    @Test
    fun passageSearchCitesThePage() {
        val pages = listOf(
            "This agreement starts on 1 January. The landlord provides the flat with a kitchen.",
            "The tenant may terminate the lease with a notice period of three months. Notice must be given in writing.",
            "Pets are allowed after written consent of the landlord.",
        )
        val answer = PassageSearch.search(pages, "What is the notice period?")
        assertEquals(1, answer.first().page)
        assertTrue(answer.first().text.contains("three months"))
        assertEquals(2, PassageSearch.search(pages, "Are pets allowed?").first().page)
        assertTrue(PassageSearch.search(pages, "the of and").isEmpty())
    }

    @Test
    fun invoiceDataIsExtracted() {
        val invoice = """
            Muster GmbH · Hauptstraße 1 · 10115 Berlin
            Rechnung
            Rechnungsnummer: RE-2024/0815
            Rechnungsdatum: 12.03.2024
            Kaffee 2 x 12,50 € 25,00 €
            Gesamtbetrag 29,75 €
            Zahlbar bis 26.03.2024 auf IBAN DE89 3704 0044 0532 0130 00, BIC: COBADEFFXXX
            USt-IdNr. DE123456789 · buchhaltung@muster.de
        """.trimIndent()
        val data = DataExtraction.extract(listOf(invoice))
        assertEquals(DocumentKind.INVOICE, data.kind)
        fun value(name: String) = data.fields.filter { it.name == name }.map { it.value }
        assertEquals(listOf("RE-2024/0815"), value("Invoice number"))
        assertEquals(listOf("29,75 €"), value("Total"))
        assertEquals(listOf("26.03.2024"), value("Due date"))
        assertEquals(listOf("DE89 3704 0044 0532 0130 00"), value("IBAN"))
        assertEquals(listOf("COBADEFFXXX"), value("BIC"))
        assertEquals(listOf("DE123456789"), value("VAT ID"))
        assertEquals(listOf("buchhaltung@muster.de"), value("Email"))
        assertTrue(value("Date").containsAll(listOf("12.03.2024", "26.03.2024")))
        assertTrue(data.json().contains("\"type\": \"Invoice\""))
        assertTrue(data.csv().startsWith("Field,Value,Page\r\n"))
    }

    @Test
    fun documentKinds() {
        assertEquals(DocumentKind.PAYSLIP, DataExtraction.kindOf("Gehaltsabrechnung März. Brutto 3.000,00 Netto 2.000,00 Lohnsteuer"))
        assertEquals(DocumentKind.CONTRACT, DataExtraction.kindOf("Mietvertrag zwischen den Vertragsparteien. § 3 Kündigung"))
        assertEquals(DocumentKind.OTHER, DataExtraction.kindOf("Hello world"))
    }

    @Test
    fun contractReviewFlagsUnusualClauses() {
        val findings = ContractReview.review(listOf(
            "Der Vertrag verlängert sich automatisch um ein Jahr. Die Kündigungsfrist beträgt sechs Monate zum Jahresende. " +
                "Der Mieter haftet unbegrenzt für alle Schäden. Bei Verstoß wird eine Vertragsstrafe von 5.000 Euro fällig. Das Wetter ist schön.",
        ))
        val byCategory = findings.associate { it.category to it.warning }
        assertEquals("Renews automatically unless cancelled in time", byCategory["Automatic renewal"])
        assertEquals("Long notice period: 6 months", byCategory["Termination"])
        assertEquals("Unlimited liability", byCategory["Liability"])
        assertEquals("Contains a contractual penalty", byCategory["Penalty"])
        assertEquals(4, findings.size)
    }

    @Test
    fun outlineFromHeadingSizesAndNumbers() {
        val lines = listOf(
            StyledLine("Annual Report", 0, 50f, 24f),
            StyledLine("Body text that goes on and on about many things in detail.", 0, 100f, 11f),
            StyledLine("1 Introduction", 0, 150f, 16f),
            StyledLine("More body text that is long enough to count as the body.", 0, 180f, 11f),
            StyledLine("1.1 Background", 1, 60f, 11f, bold = true),
            StyledLine("Even more body text with plenty of characters in it here.", 1, 90f, 11f),
            StyledLine("2 Results", 2, 60f, 16f),
            StyledLine("Final body text that is long enough to be counted as body.", 2, 90f, 11f),
        )
        assertEquals(
            listOf(
                OutlineEntry("Annual Report", 0, 50f, 1),
                OutlineEntry("1 Introduction", 0, 150f, 1),
                OutlineEntry("1.1 Background", 1, 60f, 2),
                OutlineEntry("2 Results", 2, 60f, 1),
            ),
            AutoOutline.build(lines, 3),
        )
    }
}
