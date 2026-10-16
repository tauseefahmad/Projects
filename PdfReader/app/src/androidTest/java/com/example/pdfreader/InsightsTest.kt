package com.example.pdfreader

import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import com.example.pdfreader.core.AutoOutline
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Phase 7: answers with page citations and automatic bookmarks. */
@RunWith(AndroidJUnit4::class)
class InsightsTest {

    @Before
    fun setUp() {
        ReaderTestSupport.resetAppState()
        PDFBoxResourceLoader.init(context)
    }

    /** Pages with a big heading and body lines. */
    private fun report(name: String, pages: List<Pair<String, List<String>>>): File {
        val f = File(File(context.cacheDir, "shared").apply { mkdirs() }, name)
        PDDocument().use { doc ->
            for ((heading, lines) in pages) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { s ->
                    s.beginText()
                    s.setFont(PDType1Font.HELVETICA_BOLD, 20f)
                    s.newLineAtOffset(72f, 760f)
                    s.showText(heading)
                    s.setFont(PDType1Font.HELVETICA, 11f)
                    for (line in lines) {
                        s.newLineAtOffset(0f, -30f)
                        s.showText(line)
                    }
                    s.endText()
                }
            }
            doc.save(f)
        }
        return f
    }

    private val lease = listOf(
        "Introduction" to listOf("This lease is made between the landlord and the tenant for the flat.", "The flat has two rooms and a kitchen with a window."),
        "Termination" to listOf("The tenant may terminate the lease with a notice period of three months.", "Notice must be given in writing to the landlord."),
        "Pets" to listOf("Pets are allowed only after written consent of the landlord.", "Small animals such as fish do not need consent."),
    )

    @Test
    fun answersCiteThePageAndJumpToIt() {
        val f = report("lease.pdf", lease)
        openUri(FileProvider.getUriForFile(context, "${context.packageName}.files", f)).use { scenario ->
            waitForPageLabel("1 / 3")
            scenario.onActivity { it.insights.ask("What is the notice period?") }
            waitFor { onView(withText(containsString("notice period of three months"))).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withText(containsString("notice period of three months"))).inRoot(isDialog()).perform(click())
            waitForPageLabel("2 / 3")
            onView(withId(R.id.searchBar)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun bookmarksAreMadeFromHeadings() {
        val f = report("headings.pdf", lease)
        PDDocument.load(f).use { doc ->
            val entries = AutoOutline.build(InsightsController.styledLines(doc), doc.numberOfPages)
            assertEquals(listOf("Introduction" to 0, "Termination" to 1, "Pets" to 2), entries.map { it.title to it.page })
            InsightsController.addOutline(doc, entries)
            val titles = generateSequence(doc.documentCatalog.documentOutline.firstChild) { it.nextSibling }.map { it.title }.toList()
            assertEquals(listOf("Introduction", "Termination", "Pets"), titles)
        }
    }
}
