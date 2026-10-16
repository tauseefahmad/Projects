package com.example.pdfreader

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Phase 8: library, full-text search across files, versions, compare, e-invoices, accessibility. */
@RunWith(AndroidJUnit4::class)
class LibraryTest {

    @Before
    fun setUp() {
        ReaderTestSupport.resetAppState()
        context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "versions").deleteRecursively()
        LibraryIndex.get(context).clear()
        PDFBoxResourceLoader.init(context)
    }

    private fun shared(name: String) = File(context.cacheDir, "shared/$name")
    private fun uriOf(f: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", f)

    private fun text(f: File) = PDDocument.load(f).use { PDFTextStripper().getText(it) }

    @Test
    fun libraryFindsTextInsideOpenedFiles() {
        TestPdfs.createWithText(context, "budget.pdf", listOf("Introduction", "Quarterly budget review for the board"))
        TestPdfs.createWithText(context, "other.pdf", listOf("Holiday plans"))
        openUri(uriOf(shared("other.pdf"))).use { scenario ->
            waitForPageLabel("1 / 1")
            scenario.onActivity { it.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_close, 0) }
            scenario.onActivity { it.openUriForTest(uriOf(shared("budget.pdf"))) }
            waitForPageLabel("1 / 2")
            // Indexed in the background.
            waitFor(20_000) { assertEquals(listOf(1), LibraryIndex.get(context).search("budget").map { it.page }) }
            assertEquals(setOf("budget.pdf", "other.pdf"), LibraryStore(context).all().map { it.name }.toSet())

            scenario.onActivity { it.openLibrary() }
            waitFor { onView(withId(R.id.librarySearch)).check(matches(isDisplayed())) }
            // Favorites.
            onView(withContentDescription(context.getString(R.string.library_favorite, "other.pdf"))).perform(click())
            waitFor { assertTrue(LibraryStore(context).all().single { it.name == "other.pdf" }.favorite) }
            onView(withId(R.id.librarySearch)).perform(replaceText("quarterly"))
            waitFor { onView(withText(context.getString(R.string.library_hit_title, "budget.pdf", 2))).perform(click()) }
            waitForPageLabel("2 / 2")
            onView(withId(R.id.searchBar)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun savingKeepsVersionsThatCanBeRestored() {
        val f = shared("versioned.pdf")
        TestPdfs.createWithText(context, "versioned.pdf", listOf("Original wording"))
        openUri(uriOf(f)).use { scenario ->
            waitForPageLabel("1 / 1")
            // Overwrite the file the way the app's tools do (watermark), which keeps a version.
            scenario.onActivity { it.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.tool_watermark, 0) }
            waitFor { onView(withText(R.string.apply)).inRoot(isDialog()).perform(click()) }
            waitFor { assertEquals(1, VersionStore.list(context, uriOf(f)).size) }
            waitFor { assertTrue(f.length() != VersionStore.list(context, uriOf(f)).single().size) }
            waitForPageLabel("1 / 1")

            scenario.onActivity { it.extras.showVersions() }
            waitFor { scenario.onActivity { a -> a.extras.dialog!!.listView.performItemClick(null, 0, 0) } }
            waitFor { onView(withText(R.string.versions_restore)).inRoot(isDialog()).perform(click()) }
            waitFor { onView(withText(R.string.versions_restore_action)).inRoot(isDialog()).perform(click()) }
            waitFor { assertEquals("Original wording", text(f).trim()) }
            // The watermarked state was kept too.
            assertEquals(2, VersionStore.list(context, uriOf(f)).size)
        }
    }

    @Test
    fun compareListsTheChanges() {
        TestPdfs.createWithText(context, "v1.pdf", listOf("The rent is 800 euros"))
        TestPdfs.createWithText(context, "v2.pdf", listOf("The rent is 850 euros"))
        openUri(uriOf(shared("v2.pdf"))).use { scenario ->
            waitForPageLabel("1 / 1")
            scenario.onActivity { a ->
                val doc = ToolsController.CurrentDocument(uriOf(shared("v2.pdf")), uriOf(shared("v2.pdf")), "v2.pdf", true, 1)
                a.extras.compare(doc, uriOf(shared("v1.pdf")), "v1.pdf")
            }
            waitFor { onView(withText("~ “800” → “850”")).inRoot(isDialog()).check(matches(isDisplayed())) }
        }
    }

    @Test
    fun eInvoiceDataIsShown() {
        val f = shared("einvoice.pdf")
        TestPdfs.createWithText(context, "einvoice.pdf", listOf("Rechnung RE-4711"))
        val xml = """<?xml version="1.0"?><rsm:CrossIndustryInvoice xmlns:rsm="urn:rsm" xmlns:ram="urn:ram"><rsm:ExchangedDocument><ram:ID>RE-4711</ram:ID></rsm:ExchangedDocument><rsm:SupplyChainTradeTransaction><ram:ApplicableHeaderTradeSettlement><ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode><ram:SpecifiedTradeSettlementHeaderMonetarySummation><ram:GrandTotalAmount>119.00</ram:GrandTotalAmount></ram:SpecifiedTradeSettlementHeaderMonetarySummation></ram:ApplicableHeaderTradeSettlement></rsm:SupplyChainTradeTransaction></rsm:CrossIndustryInvoice>"""
        PDDocument.load(f).use { doc ->
            val spec = PDComplexFileSpecification().apply {
                file = "factur-x.xml"
                embeddedFile = PDEmbeddedFile(doc, xml.byteInputStream())
            }
            doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply {
                embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("factur-x.xml" to spec) }
            }
            val temp = File(context.cacheDir, "einvoice-temp.pdf")
            doc.save(temp)
            temp.copyTo(f, overwrite = true)
            temp.delete()
        }
        openUri(uriOf(f)).use { scenario ->
            waitForPageLabel("1 / 1")
            scenario.onActivity { it.extras.showEInvoice() }
            waitFor { onView(withText("Invoice number: RE-4711")).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withText("Total: 119.00 EUR")).inRoot(isDialog()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun accessibilityCheckOfAnUntaggedFile() {
        TestPdfs.createWithText(context, "plain.pdf", listOf("Some text"))
        val results = PDDocument.load(shared("plain.pdf")).use { AccessibilityCheck.run(it) }.associate { it.check to it.passed }
        assertFalse(results.getValue("Tagged PDF (reading order and structure)"))
        assertFalse(results.getValue("Document language"))
        assertTrue(results.getValue("Pages have real text (not only scans)"))
        openUri(uriOf(shared("plain.pdf"))).use { scenario ->
            waitForPageLabel("1 / 1")
            scenario.onActivity { it.extras.showAccessibility() }
            waitFor { onView(withText(containsString("checks passed"))).inRoot(isDialog()).check(matches(isDisplayed())) }
            // The widget can be refreshed at any time.
            RecentFilesWidget.update(context)
        }
    }

    @Suppress("unused")
    private fun launch(intent: Intent) = ActivityScenario.launch<MainActivity>(intent)
}
