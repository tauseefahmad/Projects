package com.example.pdfreader

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openPdf
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResumeTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    @Test
    fun reopeningAFileReturnsToTheSamePageAndZoom() {
        var zoomBefore = 0f
        openPdf(6, name = "resume.pdf").use { scenario ->
            waitForPageLabel("1 / 6")
            repeat(3) { onView(withId(R.id.nextPage)).perform(click()) }
            waitForPageLabel("4 / 6")
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.zoom_menu)).perform(click())
            onView(withText("200%")).perform(click())
            waitFor { scenario.onActivity { zoomBefore = it.findViewById<ZoomableRecyclerView>(R.id.pages).zoom }; check(zoomBefore > 1f) }
        }
        openPdf(6, name = "resume.pdf").use { scenario ->
            waitForPageLabel("4 / 6")
            waitFor {
                scenario.onActivity { assertEquals(zoomBefore, it.findViewById<ZoomableRecyclerView>(R.id.pages).zoom, 0.01f) }
            }
        }
    }

    @Test
    fun closedDocumentAppearsInRecentFilesAndReopensWhereLeft() {
        openPdf(5, name = "recent.pdf").use {
            waitForPageLabel("1 / 5")
            repeat(2) { onView(withId(R.id.nextPage)).perform(click()) }
            waitForPageLabel("3 / 5")
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.close_document)).perform(click())
            waitFor { onView(withText(context.getString(R.string.recent_item, "recent.pdf", 3))).check(matches(isDisplayed())) }
            onView(withText(context.getString(R.string.recent_item, "recent.pdf", 3))).perform(click())
            waitForPageLabel("3 / 5")
        }
    }
}
