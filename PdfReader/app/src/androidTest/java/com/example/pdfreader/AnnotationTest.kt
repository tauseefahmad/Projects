package com.example.pdfreader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.EspressoKey
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import com.example.pdfreader.core.Annotation
import com.example.pdfreader.core.Pt
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AnnotationTest {

    private val line = "An apple a day keeps the doctor away."
    private val output = File(context.cacheDir, "annotation-output.pdf")

    @Before
    fun setUp() {
        ReaderTestSupport.resetAppState()
        output.delete()
        Intents.init()
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(output))))
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun tearDown() = Intents.release()

    private fun open(name: String): ActivityScenario<MainActivity> {
        val scenario = openUri(TestPdfs.createWithText(context, name, listOf(line)))
        waitForPageLabel("1 / 1")
        waitFor { scenario.onActivity { assertTrue(it.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(0)?.pageRotation != null) } }
        return scenario
    }

    private fun file(name: String) = File(context.cacheDir, "shared/$name")

    /** Screen position of a point on page 1, in page points from the top-left. */
    private fun screenPoint(scenario: ActivityScenario<MainActivity>, xPt: Float, yPt: Float): FloatArray {
        var point = FloatArray(2)
        scenario.onActivity { activity ->
            val list = activity.findViewById<ZoomableRecyclerView>(R.id.pages)
            val view = list.pageViewFor(0)!!
            val local = view.toLocal(xPt, yPt)
            val onList = list.screenPointIn(view, local.x, local.y)
            val origin = IntArray(2).also(list::getLocationOnScreen)
            point = floatArrayOf(origin[0] + onList.x, origin[1] + onList.y)
        }
        return point
    }

    private fun drag(scenario: ActivityScenario<MainActivity>, from: Pt, to: Pt) {
        val a = screenPoint(scenario, from.x, from.y)
        val b = screenPoint(scenario, to.x, to.y)
        onView(withId(R.id.pages)).perform(GeneralSwipeAction(Swipe.SLOW, CoordinatesProvider { a }, CoordinatesProvider { b }, Press.FINGER))
    }

    private fun tap(scenario: ActivityScenario<MainActivity>, at: Pt, tap: Tap = Tap.SINGLE) {
        val p = screenPoint(scenario, at.x, at.y)
        onView(withId(R.id.pages)).perform(
            GeneralClickAction(tap, CoordinatesProvider { p }, Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.BUTTON_PRIMARY)
        )
    }

    private fun annotations(scenario: ActivityScenario<MainActivity>): List<Annotation> {
        var items = emptyList<Annotation>()
        scenario.onActivity { items = it.annotationSession?.items.orEmpty() }
        return items
    }

    private fun isAnnotating(scenario: ActivityScenario<MainActivity>): Boolean {
        var active = false
        scenario.onActivity { active = it.annotateController.isActive }
        return active
    }

    private fun startAnnotating() {
        ReaderTestSupport.toolbarAction(R.string.annotate)
        waitFor { onView(withId(R.id.annotateBar)).check(matches(isDisplayed())) }
    }

    private fun tool(label: Int) = onView(withContentDescription(label)).perform(scrollTo(), click())

    private fun doneAndSave() {
        onView(withId(R.id.annotDone)).perform(click())
        onView(withText(R.string.save)).inRoot(isDialog()).perform(click())
    }

    private fun typeInDialog(text: String) {
        onView(withId(R.id.annotation_text_input)).inRoot(isDialog()).perform(replaceText(text))
        onView(withText(R.string.ok)).inRoot(isDialog()).perform(click())
    }

    /** Subtypes of the annotations stored in a file. */
    private fun storedSubtypes(f: File): List<String> =
        PDDocument.load(f).use { doc -> doc.pages.flatMap { page -> page.annotations.map { it.subtype } } }

    /** Page point of the middle of [word] in the test line (x=72pt, baseline 700pt from the bottom, 18pt Helvetica). */
    private fun wordPoint(word: String, end: Boolean = false): Pt {
        val font = PDType1Font.HELVETICA
        val before = line.substring(0, line.indexOf(word))
        val x0 = 72f + font.getStringWidth(before) / 1000f * 18f
        val w = font.getStringWidth(word) / 1000f * 18f
        return Pt(if (end) x0 + w - 2f else x0 + 2f, 842f - 700f - 5f)
    }

    @Test
    fun penStrokeIsSavedIntoThePdfAsAnInkAnnotation() {
        open("ink.pdf").use { scenario ->
            startAnnotating()
            tool(R.string.tool_pen)
            drag(scenario, Pt(100f, 300f), Pt(400f, 360f))
            waitFor { assertEquals(listOf(Annotation.Ink::class), annotations(scenario).map { it::class }) }
            doneAndSave()
            waitFor { assertEquals(listOf("Ink"), storedSubtypes(file("ink.pdf"))) }
            // The reopened file shows the saved stroke as an editable annotation again.
            waitFor { assertEquals(1, annotations(scenario).size) }
            assertFalse(isAnnotating(scenario))
        }
    }

    @Test
    fun highlightToolMarksTheTextUnderTheFinger() {
        open("highlight.pdf").use { scenario ->
            startAnnotating()
            tool(R.string.tool_highlight)
            drag(scenario, wordPoint("apple"), wordPoint("doctor", end = true))
            waitFor {
                val markup = annotations(scenario).single() as Annotation.Markup
                assertEquals("apple a day keeps the doctor", markup.text)
            }
            doneAndSave()
            waitFor { assertEquals(listOf("Highlight"), storedSubtypes(file("highlight.pdf"))) }
            waitFor {
                val reloaded = annotations(scenario).single() as Annotation.Markup
                assertEquals("apple a day keeps the doctor", reloaded.text)
                assertEquals("", reloaded.note)
            }
        }
    }

    @Test
    fun selectedTextCanBeHighlightedFromTheSelectionToolbar() {
        open("select-highlight.pdf").use { scenario ->
            tap(scenario, wordPoint("doctor").let { Pt(it.x + 8f, it.y) }, Tap.LONG)
            waitFor {
                var text = ""
                scenario.onActivity { text = it.textSelection.selectedText }
                assertEquals("doctor", text)
            }
            try {
                onView(withText(R.string.tool_highlight)).inRoot(isPlatformPopup()).perform(click())
            } catch (e: NoMatchingViewException) {
                onView(withContentDescription("More options")).inRoot(isPlatformPopup()).perform(click())
                onView(withText(R.string.tool_highlight)).inRoot(isPlatformPopup()).perform(click())
            }
            waitFor { assertEquals("doctor", (annotations(scenario).single() as Annotation.Markup).text) }
            assertTrue(isAnnotating(scenario))
        }
    }

    @Test
    fun stickyNoteKeepsItsCommentAndShowsInThePanelAndSummary() {
        open("note.pdf").use { scenario ->
            startAnnotating()
            tool(R.string.tool_note)
            tap(scenario, Pt(300f, 400f))
            typeInDialog("Check this figure")
            waitFor { assertEquals("Check this figure", (annotations(scenario).single() as Annotation.Note).note) }

            onView(withId(R.id.annotList)).perform(click())
            waitFor { onView(withText(context.getString(R.string.annotation_row_title, "Note", 1))).check(matches(isDisplayed())) }
            onView(withId(R.id.exportAnnotations)).perform(click())
            waitFor {
                val chooser = Intents.getIntents().last { it.action == Intent.ACTION_CHOOSER }
                @Suppress("DEPRECATION")
                val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                val text = send.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                assertTrue(text, text.contains("Page 1") && text.contains("• Note") && text.contains("Check this figure"))
            }
        }
    }

    @Test
    fun undoAndRedoWithButtonsAndKeyboard() {
        open("undo.pdf").use { scenario ->
            startAnnotating()
            tool(R.string.tool_pen)
            drag(scenario, Pt(100f, 300f), Pt(400f, 300f))
            drag(scenario, Pt(100f, 400f), Pt(400f, 400f))
            waitFor { assertEquals(2, annotations(scenario).size) }
            onView(withId(R.id.annotUndo)).perform(click())
            waitFor { assertEquals(1, annotations(scenario).size) }
            onView(withId(R.id.annotRedo)).perform(click())
            waitFor { assertEquals(2, annotations(scenario).size) }
            onView(withId(R.id.pages)).perform(pressKey(EspressoKey.Builder().withCtrlPressed(true).withKeyCode(KeyEvent.KEYCODE_Z).build()))
            waitFor { assertEquals(1, annotations(scenario).size) }
            onView(withId(R.id.pages)).perform(pressKey(EspressoKey.Builder().withCtrlPressed(true).withKeyCode(KeyEvent.KEYCODE_Y).build()))
            waitFor { assertEquals(2, annotations(scenario).size) }
        }
    }

    @Test
    fun selectToolMovesAndDeletesAnnotations() {
        open("move.pdf").use { scenario ->
            startAnnotating()
            tool(R.string.tool_note)
            tap(scenario, Pt(200f, 300f))
            typeInDialog("Move me")
            waitFor { assertEquals(1, annotations(scenario).size) }
            tool(R.string.tool_select)
            drag(scenario, Pt(200f, 300f), Pt(400f, 500f))
            waitFor {
                val note = annotations(scenario).single() as Annotation.Note
                assertEquals(390f, note.at.x, 10f)
                assertEquals(490f, note.at.y, 10f)
            }
            onView(withId(R.id.annotDelete)).perform(click())
            waitFor { assertTrue(annotations(scenario).isEmpty()) }
        }
    }

    @Test
    fun unsavedAnnotationsAreOfferedAgainAfterReopening() {
        val uri = TestPdfs.createWithText(context, "draft.pdf", listOf(line))
        openUri(uri).use { scenario ->
            waitForPageLabel("1 / 1")
            waitFor { scenario.onActivity { assertTrue(it.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(0)?.pageRotation != null) } }
            startAnnotating()
            tool(R.string.tool_pen)
            drag(scenario, Pt(100f, 300f), Pt(400f, 360f))
            waitFor { assertEquals(1, annotations(scenario).size) }
        }
        Thread.sleep(1000)
        openUri(uri).use { scenario ->
            waitFor { onView(withText(R.string.restore_draft_title)).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withText(R.string.restore)).inRoot(isDialog()).perform(click())
            waitFor { assertEquals(1, annotations(scenario).size) }
            assertTrue(isAnnotating(scenario))
        }
        // The file itself was never changed.
        assertTrue(storedSubtypes(file("draft.pdf")).isEmpty())
    }

    @Test
    fun flattenedCopyBakesAnnotationsIntoThePage() {
        open("flatten.pdf").use { scenario ->
            startAnnotating()
            tool(R.string.tool_pen)
            drag(scenario, Pt(100f, 300f), Pt(400f, 360f))
            waitFor { assertEquals(1, annotations(scenario).size) }
            onView(withId(R.id.annotMore)).perform(click())
            onView(withText(R.string.save_flattened_copy)).inRoot(isPlatformPopup()).perform(click())
            waitFor {
                assertTrue(output.length() > 0)
                PDDocument.load(output).use { doc ->
                    assertTrue(doc.getPage(0).annotations.isEmpty())
                    assertTrue(doc.getPage(0).resources.xObjectNames.iterator().hasNext())
                }
            }
            assertTrue(storedSubtypes(file("flatten.pdf")).isEmpty())
        }
    }

    @Test
    fun existingAnnotationsAreLoadedAndCanBeRemoved() {
        val f = file("existing.pdf")
        TestPdfs.createWithText(context, "existing.pdf", listOf(line))
        PDDocument.load(f).use { doc ->
            AnnotationIO.write(
                doc,
                listOf(
                    Annotation.Note("pdfreader-1", 0, 0xFFFFFF00.toInt(), "Someone", 0, "old note", Pt(300f, 300f)),
                    Annotation.Ink("pdfreader-2", 0, 0xFF0000FF.toInt(), "Someone", 0, strokes = listOf(listOf(Pt(100f, 500f), Pt(300f, 520f))), width = 2f),
                ),
            )
            val temp = File(context.cacheDir, "existing-temp.pdf")
            doc.save(temp)
            temp.copyTo(f, overwrite = true)
            temp.delete()
        }
        openUri(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)).use { scenario ->
            waitFor { assertEquals(setOf("pdfreader-1", "pdfreader-2"), annotations(scenario).map { it.id }.toSet()) }
            onView(withId(R.id.pages)).check(matches(isDisplayed()))
            startAnnotating()
            onView(withId(R.id.annotList)).perform(click())
            waitFor {
                onView(withContentDescription(context.getString(R.string.annot_delete_named, "Note", 1))).perform(click())
            }
            waitFor { assertEquals(listOf("pdfreader-2"), annotations(scenario).map { it.id }) }
            scenario.onActivity { it.closeSidePanel() }
            // Wait until the panel has slid away, or the tap lands on it.
            waitFor { scenario.onActivity { assertFalse(it.findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer).isDrawerVisible(androidx.core.view.GravityCompat.START)) } }
            doneAndSave()
            waitFor { assertEquals(listOf("Ink"), storedSubtypes(f)) }
        }
    }
}
