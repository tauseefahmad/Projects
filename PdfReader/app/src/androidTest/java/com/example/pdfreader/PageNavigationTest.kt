package com.example.pdfreader

import android.view.KeyEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.fastScrollPoint
import com.example.pdfreader.ReaderTestSupport.openPdf
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import com.example.pdfreader.core.SpreadMode
import com.example.pdfreader.core.ViewMode
import org.hamcrest.Matchers.not
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageNavigationTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    @Test
    fun opensDocumentOnFirstPageWithPageCount() {
        openPdf(5).use {
            waitForPageLabel("1 / 5")
            onView(withId(R.id.prevPage)).check(matches(not(isEnabled())))
            onView(withId(R.id.nextPage)).check(matches(isEnabled()))
        }
    }

    @Test
    fun nextAndPreviousButtonsTurnPages() {
        openPdf(5).use {
            waitForPageLabel("1 / 5")
            onView(withId(R.id.nextPage)).perform(click())
            waitForPageLabel("2 / 5")
            onView(withId(R.id.nextPage)).perform(click())
            waitForPageLabel("3 / 5")
            onView(withId(R.id.prevPage)).perform(click())
            waitForPageLabel("2 / 5")
        }
    }

    @Test
    fun nextIsDisabledOnLastPage() {
        openPdf(3).use {
            waitForPageLabel("1 / 3")
            repeat(2) { onView(withId(R.id.nextPage)).perform(click()) }
            waitForPageLabel("3 / 3")
            onView(withId(R.id.nextPage)).check(matches(not(isEnabled())))
        }
    }

    @Test
    fun goToPageDialogJumpsToPage() {
        openPdf(8).use {
            waitForPageLabel("1 / 8")
            onView(withId(R.id.pageLabel)).perform(click())
            onView(withId(R.id.page_input)).inRoot(isDialog()).perform(replaceText("6"))
            onView(withText(R.string.go)).inRoot(isDialog()).perform(click())
            waitForPageLabel("6 / 8")
        }
    }

    @Test
    fun keyboardShortcutsNavigate() {
        openPdf(5).use {
            waitForPageLabel("1 / 5")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_DOWN)
            waitForPageLabel("2 / 5")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
            waitForPageLabel("3 / 5")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_UP)
            waitForPageLabel("2 / 5")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MOVE_END)
            waitForPageLabel("5 / 5")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MOVE_HOME)
            waitForPageLabel("1 / 5")
        }
    }

    @Test
    fun volumeKeysTurnPagesWhenEnabled() {
        ReaderSettings(context).volumeKeysTurnPages = true
        openPdf(5).use {
            waitForPageLabel("1 / 5")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
            waitForPageLabel("2 / 5")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
            waitForPageLabel("1 / 5")
        }
    }

    @Test
    fun volumeKeysDoNothingByDefault() {
        openPdf(5).use {
            waitForPageLabel("1 / 5")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
            Thread.sleep(500)
            waitForPageLabel("1 / 5")
        }
    }

    @Test
    fun fastScrollHandleJumpsThroughDocument() {
        openPdf(30).use {
            waitForPageLabel("1 / 30")
            onView(withId(R.id.fastScroll)).perform(
                GeneralSwipeAction(Swipe.SLOW, fastScrollPoint(0f), fastScrollPoint(1f), Press.FINGER)
            )
            waitForPageLabel("30 / 30")
        }
    }

    @Test
    fun singlePageModePagesHorizontally() {
        ReaderSettings(context).viewMode = ViewMode.SINGLE_PAGE
        openPdf(5).use { scenario ->
            waitForPageLabel("1 / 5")
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.pages)
                assertEquals(RecyclerView.HORIZONTAL, (list.layoutManager as LinearLayoutManager).orientation)
            }
            onView(withId(R.id.nextPage)).perform(click())
            waitForPageLabel("2 / 5")
            onView(withId(R.id.pages)).perform(swipeLeft())
            waitForPageLabel("3 / 5")
        }
    }

    @Test
    fun twoPageSpreadPairsPages() {
        ReaderSettings(context).spreadMode = SpreadMode.TWO_PAGE
        openPdf(5).use {
            waitForPageLabel("1–2 / 5")
            onView(withId(R.id.nextPage)).perform(click())
            waitForPageLabel("3–4 / 5")
            onView(withId(R.id.nextPage)).perform(click())
            waitForPageLabel("5 / 5")
            onView(withId(R.id.nextPage)).check(matches(not(isEnabled())))
        }
    }

    @Test
    fun twoPageSpreadWithCoverShowsFirstPageAlone() {
        ReaderSettings(context).spreadMode = SpreadMode.TWO_PAGE_COVER
        openPdf(5).use {
            waitForPageLabel("1 / 5")
            onView(withId(R.id.nextPage)).perform(click())
            waitForPageLabel("2–3 / 5")
            onView(withId(R.id.nextPage)).perform(click())
            waitForPageLabel("4–5 / 5")
        }
    }

    @Test
    fun switchingViewModeFromMenuKeepsPage() {
        openPdf(5).use { scenario ->
            waitForPageLabel("1 / 5")
            repeat(2) { onView(withId(R.id.nextPage)).perform(click()) }
            waitForPageLabel("3 / 5")
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.view_menu)).perform(click())
            onView(withText(R.string.view_single_page)).perform(click())
            waitForPageLabel("3 / 5")
            waitFor {
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.pages)
                    assertEquals(RecyclerView.HORIZONTAL, (list.layoutManager as LinearLayoutManager).orientation)
                }
            }
            assertEquals(ViewMode.SINGLE_PAGE, ReaderSettings(context).viewMode)
        }
    }
}
