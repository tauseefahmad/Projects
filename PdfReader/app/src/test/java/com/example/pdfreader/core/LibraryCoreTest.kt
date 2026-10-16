package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCoreTest {

    @Test
    fun compareFindsAddedRemovedAndChangedWords() {
        val old = listOf("The rent is 800 euros per month.", "Pets are not allowed.")
        val new = listOf("The rent is 850 euros per month, due on the first.", "Pets are not allowed.", "Smoking is forbidden.")
        val changes = Compare.diff(old, new)
        assertEquals(3, changes.size)
        val (rent, due, smoking) = changes
        assertEquals(Change.Kind.CHANGED, rent.kind)
        assertEquals("800", rent.before)
        assertEquals("850", rent.text)
        assertEquals(Change.Kind.CHANGED, due.kind)
        assertEquals("month.", due.before)
        assertEquals("month, due on the first.", due.text)
        assertEquals(Change.Kind.ADDED, smoking.kind)
        assertEquals("Smoking is forbidden.", smoking.text)
        assertEquals(2, smoking.newPage)
        assertTrue(Compare.diff(old, old).isEmpty())
        val report = Compare.report("a.pdf", "b.pdf", changes)
        assertTrue(report, report.contains("~ (p. 1) “800” → “850”"))
        assertTrue(report, report.contains("+ (p. 3) Smoking is forbidden."))
    }

    @Test
    fun removedWordsKeepTheirOldPage() {
        val changes = Compare.diff(listOf("a b c", "d e f"), listOf("a b c", "d f"))
        assertEquals(listOf(Change(Change.Kind.REMOVED, "e", 1, 1)), changes)
    }

    private val cii = """<?xml version="1.0" encoding="UTF-8"?>
<rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100" xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100" xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100">
  <rsm:ExchangedDocumentContext>
    <ram:GuidelineSpecifiedDocumentContextParameter><ram:ID>urn:cen.eu:en16931:2017#compliant#urn:factur-x.eu:1p0:en16931</ram:ID></ram:GuidelineSpecifiedDocumentContextParameter>
  </rsm:ExchangedDocumentContext>
  <rsm:ExchangedDocument>
    <ram:ID>RE-4711</ram:ID>
    <ram:IssueDateTime><udt:DateTimeString format="102">20240312</udt:DateTimeString></ram:IssueDateTime>
  </rsm:ExchangedDocument>
  <rsm:SupplyChainTradeTransaction>
    <ram:ApplicableHeaderTradeAgreement>
      <ram:BuyerReference>04011000-12345-34</ram:BuyerReference>
      <ram:SellerTradeParty><ram:Name>Muster &amp; Co. GmbH</ram:Name></ram:SellerTradeParty>
      <ram:BuyerTradeParty><ram:Name>Kunde AG</ram:Name></ram:BuyerTradeParty>
    </ram:ApplicableHeaderTradeAgreement>
    <ram:ApplicableHeaderTradeSettlement>
      <ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>
      <ram:SpecifiedTradeSettlementPaymentMeans><ram:PayeePartyCreditorFinancialAccount><ram:IBANID>DE89370400440532013000</ram:IBANID></ram:PayeePartyCreditorFinancialAccount></ram:SpecifiedTradeSettlementPaymentMeans>
      <ram:SpecifiedTradePaymentTerms><ram:DueDateDateTime><udt:DateTimeString format="102">20240326</udt:DateTimeString></ram:DueDateDateTime></ram:SpecifiedTradePaymentTerms>
      <ram:SpecifiedTradeSettlementHeaderMonetarySummation>
        <ram:TaxBasisTotalAmount>100.00</ram:TaxBasisTotalAmount>
        <ram:TaxTotalAmount currencyID="EUR">19.00</ram:TaxTotalAmount>
        <ram:GrandTotalAmount>119.00</ram:GrandTotalAmount>
        <ram:DuePayableAmount>119.00</ram:DuePayableAmount>
      </ram:SpecifiedTradeSettlementHeaderMonetarySummation>
    </ram:ApplicableHeaderTradeSettlement>
  </rsm:SupplyChainTradeTransaction>
</rsm:CrossIndustryInvoice>"""

    @Test
    fun zugferdInvoiceIsRead() {
        val invoice = EInvoices.read(cii)!!
        assertEquals("ZUGFeRD / Factur-X", invoice.format)
        assertEquals("RE-4711", invoice.number)
        assertEquals("2024-03-12", invoice.issueDate)
        assertEquals("2024-03-26", invoice.dueDate)
        assertEquals("Muster & Co. GmbH", invoice.seller)
        assertEquals("Kunde AG", invoice.buyer)
        assertEquals("119.00 EUR", invoice.fields().toMap()["Total"])
        assertEquals("DE89370400440532013000", invoice.iban)
        assertEquals("04011000-12345-34", invoice.buyerReference)
    }

    @Test
    fun xrechnungUblIsRead() {
        val ubl = """<Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2" xmlns:cbc="urn:cbc" xmlns:cac="urn:cac">
<cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0</cbc:CustomizationID>
<cbc:ID>INV-1</cbc:ID><cbc:IssueDate>2024-05-01</cbc:IssueDate><cbc:DueDate>2024-05-31</cbc:DueDate>
<cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
<cac:AccountingSupplierParty><cac:Party><cac:PartyLegalEntity><cbc:RegistrationName>Lieferant GmbH</cbc:RegistrationName></cac:PartyLegalEntity></cac:Party></cac:AccountingSupplierParty>
<cac:AccountingCustomerParty><cac:Party><cac:PartyName><cbc:Name>Stadt Musterhausen</cbc:Name></cac:PartyName></cac:Party></cac:AccountingCustomerParty>
<cac:PaymentMeans><cac:PayeeFinancialAccount><cbc:ID>DE89370400440532013000</cbc:ID></cac:PayeeFinancialAccount></cac:PaymentMeans>
<cac:LegalMonetaryTotal><cbc:TaxExclusiveAmount currencyID="EUR">10.00</cbc:TaxExclusiveAmount><cbc:TaxInclusiveAmount currencyID="EUR">11.90</cbc:TaxInclusiveAmount><cbc:PayableAmount currencyID="EUR">11.90</cbc:PayableAmount></cac:LegalMonetaryTotal>
</Invoice>"""
        val invoice = EInvoices.read(ubl)!!
        assertEquals("XRechnung (UBL)", invoice.format)
        assertEquals("INV-1", invoice.number)
        assertEquals("Lieferant GmbH", invoice.seller)
        assertEquals("Stadt Musterhausen", invoice.buyer)
        assertEquals("11.90", invoice.amountDue)
        assertEquals("2024-05-31", invoice.dueDate)
    }
}
