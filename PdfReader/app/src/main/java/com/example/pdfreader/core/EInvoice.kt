package com.example.pdfreader.core

/** Key data of an electronic invoice (ZUGFeRD / Factur-X CII, or XRechnung UBL). */
data class EInvoice(
    val format: String,
    val number: String = "",
    val issueDate: String = "",
    val dueDate: String = "",
    val seller: String = "",
    val buyer: String = "",
    val currency: String = "",
    val netTotal: String = "",
    val taxTotal: String = "",
    val grandTotal: String = "",
    val amountDue: String = "",
    val iban: String = "",
    val buyerReference: String = "",
) {
    /** Label → value for the fields that are set. */
    fun fields(): List<Pair<String, String>> = listOf(
        "Format" to format, "Invoice number" to number, "Issue date" to issueDate, "Due date" to dueDate,
        "Seller" to seller, "Buyer" to buyer, "Buyer reference" to buyerReference, "Net total" to withCurrency(netTotal),
        "Tax" to withCurrency(taxTotal), "Total" to withCurrency(grandTotal), "Amount due" to withCurrency(amountDue), "IBAN" to iban,
    ).filter { it.second.isNotBlank() }

    private fun withCurrency(amount: String) = if (amount.isBlank() || currency.isBlank()) amount else "$amount $currency"
}

/**
 * Reads e-invoice XML. ZUGFeRD/Factur-X PDFs carry a Cross Industry Invoice (CII) as an
 * attachment (factur-x.xml, zugferd-invoice.xml, xrechnung.xml); XRechnung may also be UBL.
 */
object EInvoices {

    /** Attachment names that hold an e-invoice. */
    val attachmentNames = setOf("factur-x.xml", "zugferd-invoice.xml", "zugferd_invoice.xml", "xrechnung.xml", "zugferd.xml")

    /** A minimal XML element tree; enough for invoices (no DTDs, no entities beyond the five standard ones). */
    class Element(val name: String, val attributes: Map<String, String>) {
        val children = mutableListOf<Element>()
        var text = ""
        fun child(local: String) = children.firstOrNull { it.local == local }
        val local get() = name.substringAfter(':')
        fun path(vararg locals: String): Element? = locals.fold(this as Element?) { e, l -> e?.child(l) }
        fun all(local: String): List<Element> = children.filter { it.local == local } + children.flatMap { it.all(local) }
        fun find(local: String): Element? = all(local).firstOrNull()
    }

    fun parseXml(xml: String): Element? {
        val stack = ArrayDeque<Element>()
        var root: Element? = null
        var i = 0
        val text = StringBuilder()
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            text.append(xml, i, lt)
            when {
                xml.startsWith("<?", lt) -> i = xml.indexOf("?>", lt).let { if (it < 0) xml.length else it + 2 }
                xml.startsWith("<!--", lt) -> i = xml.indexOf("-->", lt).let { if (it < 0) xml.length else it + 3 }
                xml.startsWith("<![CDATA[", lt) -> {
                    val end = xml.indexOf("]]>", lt).let { if (it < 0) xml.length else it }
                    text.append(xml, lt + 9, end)
                    i = end + 3
                }
                xml.startsWith("<!", lt) -> i = xml.indexOf('>', lt) + 1
                xml.startsWith("</", lt) -> {
                    val e = stack.removeLastOrNull() ?: return root
                    e.text = unescape(text.toString()).trim()
                    text.clear()
                    i = xml.indexOf('>', lt) + 1
                }
                else -> {
                    val gt = xml.indexOf('>', lt)
                    if (gt < 0) return root
                    val body = xml.substring(lt + 1, gt)
                    val selfClosing = body.endsWith("/")
                    val content = body.removeSuffix("/").trim()
                    val name = content.split(Regex("\\s+"), limit = 2)[0]
                    val attributes = Regex("""([\w:.-]+)\s*=\s*("([^"]*)"|'([^']*)')""").findAll(content).associate { m ->
                        m.groupValues[1] to unescape(m.groupValues[3].ifEmpty { m.groupValues[4] })
                    }
                    val e = Element(name, attributes)
                    stack.lastOrNull()?.children?.add(e) ?: run { root = e }
                    text.clear()
                    if (!selfClosing) stack.addLast(e)
                    i = gt + 1
                }
            }
        }
        return root
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Formats CII date strings (format 102: yyyyMMdd) as yyyy-MM-dd. */
    private fun date(e: Element?): String {
        val raw = (e?.find("DateTimeString") ?: e)?.text.orEmpty()
        return if (raw.length == 8 && raw.all(Char::isDigit)) "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6)}" else raw
    }

    fun read(xml: String): EInvoice? {
        val root = parseXml(xml) ?: return null
        return when (root.local) {
            "CrossIndustryInvoice" -> cii(root)
            "Invoice", "CreditNote" -> ubl(root)
            else -> null
        }
    }

    private fun cii(root: Element): EInvoice {
        val doc = root.child("ExchangedDocument")
        val trade = root.child("SupplyChainTradeTransaction")
        val agreement = trade?.child("ApplicableHeaderTradeAgreement")
        val settlement = trade?.child("ApplicableHeaderTradeSettlement")
        val sums = settlement?.child("SpecifiedTradeSettlementHeaderMonetarySummation")
        val guideline = root.path("ExchangedDocumentContext", "GuidelineSpecifiedDocumentContextParameter", "ID")?.text.orEmpty()
        val format = when {
            "xrechnung" in guideline.lowercase() -> "XRechnung (CII)"
            "factur-x" in guideline.lowercase() || "zugferd" in guideline.lowercase() -> "ZUGFeRD / Factur-X"
            else -> "ZUGFeRD / Factur-X (CII)"
        }
        return EInvoice(
            format = format,
            number = doc?.child("ID")?.text.orEmpty(),
            issueDate = date(doc?.child("IssueDateTime")),
            dueDate = date(settlement?.find("DueDateDateTime")),
            seller = agreement?.path("SellerTradeParty", "Name")?.text.orEmpty(),
            buyer = agreement?.path("BuyerTradeParty", "Name")?.text.orEmpty(),
            buyerReference = agreement?.child("BuyerReference")?.text.orEmpty(),
            currency = settlement?.child("InvoiceCurrencyCode")?.text.orEmpty(),
            netTotal = sums?.child("TaxBasisTotalAmount")?.text.orEmpty(),
            taxTotal = sums?.child("TaxTotalAmount")?.text.orEmpty(),
            grandTotal = sums?.child("GrandTotalAmount")?.text.orEmpty(),
            amountDue = sums?.child("DuePayableAmount")?.text.orEmpty(),
            iban = settlement?.find("IBANID")?.text.orEmpty(),
        )
    }

    private fun ubl(root: Element): EInvoice {
        val totals = root.child("LegalMonetaryTotal")
        return EInvoice(
            format = if ("xrechnung" in root.child("CustomizationID")?.text.orEmpty().lowercase()) "XRechnung (UBL)" else "UBL invoice",
            number = root.child("ID")?.text.orEmpty(),
            issueDate = root.child("IssueDate")?.text.orEmpty(),
            dueDate = root.child("DueDate")?.text ?: root.find("PaymentDueDate")?.text.orEmpty(),
            seller = root.path("AccountingSupplierParty", "Party")?.let { p -> p.find("RegistrationName")?.text ?: p.find("Name")?.text }.orEmpty(),
            buyer = root.path("AccountingCustomerParty", "Party")?.let { p -> p.find("RegistrationName")?.text ?: p.find("Name")?.text }.orEmpty(),
            buyerReference = root.child("BuyerReference")?.text.orEmpty(),
            currency = root.child("DocumentCurrencyCode")?.text.orEmpty(),
            netTotal = totals?.child("TaxExclusiveAmount")?.text.orEmpty(),
            taxTotal = root.child("TaxTotal")?.child("TaxAmount")?.text.orEmpty(),
            grandTotal = totals?.child("TaxInclusiveAmount")?.text.orEmpty(),
            amountDue = totals?.child("PayableAmount")?.text.orEmpty(),
            iban = root.find("PayeeFinancialAccount")?.child("ID")?.text.orEmpty(),
        )
    }
}
