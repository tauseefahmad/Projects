package com.example.pdfreader

import android.content.ClipboardManager
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextSelectionTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    private val line = "An apple a day keeps the doctor away."

    private fun openTextPdf(): ActivityScenario<MainActivity> =
        openUri(TestPdfs.createWithText(context, "select.pdf", listOf(line, "Second page.")))

    /** Screen point over the middle of [word] on page 1 (text drawn at x=72pt, baseline 700pt, 18pt Helvetica). */
    private fun pointOnWord(scenario: ActivityScenario<MainActivity>, word: String): FloatArray {
        var point = FloatArray(2)
        scenario.onActivity { activity ->
            val list = activity.findViewById<ZoomableRecyclerView>(R.id.pages)
            val view = list.pageViewFor(0)!!
            val font = PDType1Font.HELVETICA
            val before = line.substring(0, line.indexOf(word))
            val xPt = 72f + font.getStringWidth(before) / 1000f * 18f + font.getStringWidth(word) / 1000f * 18f / 2f
            val yPt = 842f - 700f - 5f // a little above the baseline, inside the glyphs
            val local = list.screenPointIn(view, xPt * view.scale, yPt * view.scale)
            val origin = IntArray(2).also(list::getLocationOnScreen)
            point = floatArrayOf(origin[0] + local.x, origin[1] + local.y)
        }
        return point
    }

    private fun longPressAt(point: FloatArray) {
        onView(withId(R.id.pages)).perform(
            GeneralClickAction(Tap.LONG, CoordinatesProvider { point }, Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.BUTTON_PRIMARY)
        )
    }

    private fun selectedText(scenario: ActivityScenario<MainActivity>): String {
        var text = ""
        scenario.onActivity { text = it.textSelection.selectedText }
        return text
    }

    private fun waitForPageText(scenario: ActivityScenario<MainActivity>) = waitFor {
        scenario.onActivity { assertTrue(it.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(0) != null) }
    }

    @Test
    fun longPressSelectsTheWordAndCopyPutsItOnTheClipboard() {
        openTextPdf().use { scenario ->
            waitForPageLabel("1 / 2")
            waitForPageText(scenario)
            longPressAt(pointOnWord(scenario, "doctor"))
            waitFor { assertEquals("doctor", selectedText(scenario)) }
            onView(withText(android.R.string.copy)).inRoot(isPlatformPopup()).perform(click())
            waitFor {
                var active = true
                scenario.onActivity { active = it.textSelection.isActive }
                assertFalse(active)
            }
            var clip = ""
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            }
            assertEquals("doctor", clip)
        }
    }

    @Test
    fun draggingTheEndHandleExtendsTheSelection() {
        openTextPdf().use { scenario ->
            waitForPageLabel("1 / 2")
            waitForPageText(scenario)
            longPressAt(pointOnWord(scenario, "apple"))
            waitFor { assertEquals("apple", selectedText(scenario)) }
            // Drag the end handle (its circle sits just below-right of the word's end).
            var handle = FloatArray(2)
            scenario.onActivity { activity ->
                val handles = activity.findViewById<SelectionHandles>(R.id.selectionHandles)
                val end = handles.end!!
                val origin = IntArray(2).also(handles::getLocationOnScreen)
                val r = 11 * activity.resources.displayMetrics.density
                handle = floatArrayOf(origin[0] + end.x + r, origin[1] + end.y + r)
            }
            // Drop over "keeps", well past "day", staying on the same line.
            val dropAt = floatArrayOf(pointOnWord(scenario, "keeps")[0], handle[1])
            onView(withId(R.id.selectionHandles)).perform(
                GeneralSwipeAction(Swipe.SLOW, CoordinatesProvider { handle }, CoordinatesProvider { dropAt }, Press.FINGER)
            )
            waitFor {
                val text = selectedText(scenario)
                assertTrue("selection was \"$text\"", text.startsWith("apple a day"))
            }
        }
    }

    @Test
    fun selectAllSelectsTheWholePage() {
        openTextPdf().use { scenario ->
            waitForPageLabel("1 / 2")
            waitForPageText(scenario)
            longPressAt(pointOnWord(scenario, "keeps"))
            waitFor { assertEquals("keeps", selectedText(scenario)) }
            scenario.onActivity { it.textSelection.selectAll() }
            waitFor { assertEquals(line, selectedText(scenario)) }
        }
    }

    @Test
    fun tappingClearsTheSelection() {
        openTextPdf().use { scenario ->
            waitForPageLabel("1 / 2")
            waitForPageText(scenario)
            longPressAt(pointOnWord(scenario, "away"))
            waitFor { assertEquals("away", selectedText(scenario)) }
            onView(withId(R.id.pages)).perform(click())
            waitFor { assertEquals("", selectedText(scenario)) }
        }
    }

    @Test
    fun longPressOnEmptyMarginSelectsNothing() {
        openTextPdf().use { scenario ->
            waitForPageLabel("1 / 2")
            waitForPageText(scenario)
            var point = FloatArray(2)
            scenario.onActivity { activity ->
                val list = activity.findViewById<ZoomableRecyclerView>(R.id.pages)
                val view = list.pageViewFor(0)!!
                // Middle of the page, far below the single line of text.
                val local = list.screenPointIn(view, view.width / 2f, view.height * 0.7f)
                val origin = IntArray(2).also(list::getLocationOnScreen)
                point = floatArrayOf(origin[0] + local.x, origin[1] + local.y)
            }
            longPressAt(point)
            Thread.sleep(1000)
            assertEquals("", selectedText(scenario))
        }
    }
}
