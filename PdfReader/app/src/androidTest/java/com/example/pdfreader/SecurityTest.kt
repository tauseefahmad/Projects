package com.example.pdfreader

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
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
import com.example.pdfreader.core.Annotation
import com.google.android.material.appbar.MaterialToolbar
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Phase 6: passwords, permissions, hidden data and redaction. */
@RunWith(AndroidJUnit4::class)
class SecurityTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    private fun file(name: String) = File(context.cacheDir, "shared/$name")

    private fun open(name: String, pages: List<String>): ActivityScenario<MainActivity> {
        val scenario = openUri(TestPdfs.createWithText(context, name, pages))
        waitForPageLabel("1 / ${pages.size}")
        return scenario
    }

    private fun menuAction(scenario: ActivityScenario<MainActivity>, id: Int) {
        scenario.onActivity { it.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(id, 0) }
    }

    private fun assertError(input: Int, message: Int) {
        onView(withId(input)).inRoot(isDialog()).check { view, _ ->
            assertEquals(context.getString(message), (view as android.widget.EditText).error?.toString())
        }
    }

    private fun dialogButton(text: Int) = onView(withText(text)).inRoot(isDialog()).perform(click())

    private fun text(f: File, password: String = "", page: Int? = null): String = PDDocument.load(f, password).use { doc ->
        PDFTextStripper().apply {
            if (page != null) {
                startPage = page
                endPage = page
            }
        }.getText(doc)
    }

    @Test
    fun passwordIsAddedWithAes256AndCanBeRemovedAgain() {
        open("protect.pdf", listOf("Top secret plans")).use { scenario ->
            menuAction(scenario, R.id.tool_protect)
            waitFor { onView(withId(R.id.protect_password_input)).inRoot(isDialog()).perform(replaceText("pw123")) }
            onView(withId(R.id.protect_confirm_input)).inRoot(isDialog()).perform(replaceText("pw1234"))
            dialogButton(R.string.protect_action)
            // Mismatch: the dialog stays.
            assertError(R.id.protect_confirm_input, R.string.protect_mismatch)
            onView(withId(R.id.protect_confirm_input)).inRoot(isDialog()).perform(replaceText("pw123"))
            dialogButton(R.string.protect_action)

            // Saved in place and reopened: the password is asked for.
            waitFor { onView(withText(R.string.password_message)).inRoot(isDialog()).check(matches(isDisplayed())) }
            try {
                PDDocument.load(file("protect.pdf")).close()
                fail("opened without a password")
            } catch (expected: InvalidPasswordException) {
            }
            PDDocument.load(file("protect.pdf"), "pw123").use { doc -> assertEquals(256, doc.encryption.length) }
            onView(withId(R.id.password_input)).inRoot(isDialog()).perform(replaceText("pw123"))
            dialogButton(R.string.open)
            waitForPageLabel("1 / 1")

            menuAction(scenario, R.id.tool_unprotect)
            waitFor { onView(withId(R.id.unprotect_password_input)).inRoot(isDialog()).perform(replaceText("nope")) }
            dialogButton(R.string.unprotect_action)
            waitFor { assertError(R.id.unprotect_password_input, R.string.password_wrong) }
            onView(withId(R.id.unprotect_password_input)).inRoot(isDialog()).perform(replaceText("pw123"))
            dialogButton(R.string.unprotect_action)
            waitFor { PDDocument.load(file("protect.pdf")).use { assertFalse(it.isEncrypted) } }
            assertTrue(text(file("protect.pdf")).contains("Top secret plans"))
        }
    }

    @Test
    fun restrictionsWithoutAnOpenPassword() {
        val f = file("restrict.pdf")
        TestPdfs.createWithText(context, "restrict.pdf", listOf("Read only"))
        PDDocument.load(f).use { doc ->
            SecurityTools.protect(doc, "", "boss", Permissions(print = true, copy = false, edit = false))
            val temp = File(context.cacheDir, "restrict-temp.pdf")
            doc.save(temp)
            temp.copyTo(f, overwrite = true)
            temp.delete()
        }
        PDDocument.load(f).use { doc ->
            assertTrue(doc.isEncrypted)
            val p = doc.currentAccessPermission
            assertTrue(p.canPrint())
            assertFalse(p.canExtractContent())
            assertFalse(p.canModify())
        }
        // The open password alone can't lift restrictions; the owner password can.
        try {
            File(context.cacheDir, "out.pdf").outputStream().use { SecurityTools.removePassword(context, android.net.Uri.fromFile(f), "", it) }
            fail("restrictions lifted without the owner password")
        } catch (expected: SecurityTools.NotAllowedException) {
        }
        val out = File(context.cacheDir, "out.pdf")
        out.outputStream().use { SecurityTools.removePassword(context, android.net.Uri.fromFile(f), "boss", it) }
        PDDocument.load(out).use { assertFalse(it.isEncrypted) }
    }

    @Test
    fun hiddenDataIsListedAndRemoved() {
        val f = file("hidden.pdf")
        TestPdfs.createWithText(context, "hidden.pdf", listOf("Visible text"))
        PDDocument.load(f).use { doc ->
            doc.documentInformation.author = "Alice Example"
            doc.documentInformation.creator = "Secret Tool"
            doc.documentCatalog.setOpenAction(PDActionJavaScript("app.alert('hi');"))
            val spec = PDComplexFileSpecification().apply {
                file = "notes.txt"
                embeddedFile = PDEmbeddedFile(doc, "private notes".byteInputStream())
            }
            val tree = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("notes.txt" to spec) }
            doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { embeddedFiles = tree }
            val temp = File(context.cacheDir, "hidden-temp.pdf")
            doc.save(temp)
            temp.copyTo(f, overwrite = true)
            temp.delete()
        }
        val before = PDDocument.load(f).use { SecurityTools.inspect(it) }
        assertEquals("Alice Example", before.info["Author"])
        assertEquals(1, before.embeddedFiles)
        assertEquals(1, before.scripts)

        openUri(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)).use { scenario ->
            waitForPageLabel("1 / 1")
            menuAction(scenario, R.id.tool_hidden_data)
            waitFor { onView(withText("Author: Alice Example")).inRoot(isDialog()).check(matches(isDisplayed())) }
            dialogButton(R.string.hidden_remove)
            waitFor {
                val after = PDDocument.load(f).use { SecurityTools.inspect(it) }
                assertTrue("$after", after.isEmpty)
            }
            PDDocument.load(f).use { doc ->
                assertEquals(null, doc.documentCatalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION))
            }
            assertTrue(text(f).contains("Visible text"))
        }
    }

    @Test
    fun redactedIbanLeavesNoTraceAndOtherPagesKeepTheirText() {
        open("redact.pdf", listOf("Pay DE89 3704 0044 0532 0130 00 to Max", "Page two stays searchable")).use { scenario ->
            menuAction(scenario, R.id.tool_redact)
            waitFor { onView(withId(R.id.annotateBar)).check(matches(isDisplayed())) }
            scenario.onActivity { it.annotateController.findAndRedact() }
            waitFor { onView(withText(R.string.redact_ibans)).inRoot(isDialog()).perform(click()) }
            dialogButton(R.string.redact_mark)
            waitFor {
                var marked = emptyList<Annotation>()
                scenario.onActivity { marked = it.annotationSession?.items.orEmpty() }
                assertTrue(marked.isNotEmpty() && marked.all { it is Annotation.Redaction && it.page == 0 })
            }
            onView(withId(R.id.annotDone)).perform(click())
            dialogButton(R.string.save)
            waitFor { dialogButton(R.string.redact_and_save) }
            waitFor {
                val first = text(file("redact.pdf"), page = 1)
                assertFalse(first, first.contains("DE89"))
                assertFalse(first, first.contains("3704"))
            }
            assertTrue(text(file("redact.pdf"), page = 2).contains("Page two stays searchable"))
            // The redacted page is now a picture: no fonts left that could hold the text.
            PDDocument.load(file("redact.pdf")).use { doc ->
                val resources = doc.getPage(0).resources
                assertFalse(resources.fontNames.iterator().hasNext())
                assertTrue(resources.xObjectNames.iterator().hasNext())
            }
        }
    }
}
