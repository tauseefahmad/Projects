package com.example.pdfreader

import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.DrawerMatchers.isClosed
import androidx.test.espresso.contrib.DrawerMatchers.isOpen
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isSelected
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openPdf
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import com.example.pdfreader.TestPdfs.Entry
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutlineAndBookmarksTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    private fun bookWithOutline() = TestPdfs.createWithOutline(
        context, "book.pdf", 8,
        listOf(
            Entry("Introduction", 0),
            Entry("Part 1", 1, listOf(Entry("Chapter 1", 2), Entry("Chapter 2", 4))),
            Entry("Appendix", 6),
        ),
    )

    private fun openPanelTab(tab: Int) {
        onView(withContentDescription(R.string.open_side_panel)).perform(click())
        waitFor { onView(withId(R.id.drawer)).check(matches(isOpen())) }
        onView(withContentDescription(tab)).perform(click())
    }

    private fun outlineRow(title: String) = allOf(withId(R.id.outlineTitle), withText(title))

    @Test
    fun outlineTabShowsTopLevelEntries() {
        openUri(bookWithOutline()).use { scenario ->
            waitForPageLabel("1 / 8")
            openPanelTab(R.string.tab_outline)
            waitFor { onView(outlineRow("Part 1")).check(matches(isDisplayed())) }
            onView(outlineRow("Introduction")).check(matches(isDisplayed()))
            onView(outlineRow("Appendix")).check(matches(isDisplayed()))
            onView(outlineRow("Chapter 1")).check(doesNotExist())
            scenario.onActivity { assertEquals(3, it.findViewById<RecyclerView>(R.id.outline).adapter!!.itemCount) }
        }
    }

    @Test
    fun expandingASectionAndTappingAnEntryJumpsToItsPage() {
        openUri(bookWithOutline()).use {
            waitForPageLabel("1 / 8")
            openPanelTab(R.string.tab_outline)
            waitFor { onView(outlineRow("Part 1")).check(matches(isDisplayed())) }
            onView(withContentDescription("Expand Part 1")).perform(click())
            waitFor { onView(outlineRow("Chapter 2")).check(matches(isDisplayed())) }
            onView(outlineRow("Chapter 2")).perform(click())
            waitForPageLabel("5 / 8")
            waitFor { onView(withId(R.id.drawer)).check(matches(isClosed())) }
        }
    }

    @Test
    fun currentSectionIsHighlightedAndRevealed() {
        openUri(bookWithOutline()).use {
            waitForPageLabel("1 / 8")
            onView(withId(R.id.pageLabel)).perform(click())
            onView(withId(R.id.page_input)).inRoot(isDialog()).perform(replaceText("6"))
            onView(withText(R.string.go)).inRoot(isDialog()).perform(click())
            waitForPageLabel("6 / 8")
            openPanelTab(R.string.tab_outline)
            // Page 6 is inside Chapter 2, which is inside the (auto-expanded) Part 1.
            waitFor {
                onView(allOf(hasDescendant(outlineRow("Chapter 2")), withParent(withId(R.id.outline))))
                    .check(matches(isSelected()))
            }
        }
    }

    @Test
    fun documentWithoutOutlineSaysSo() {
        openPdf(3).use {
            waitForPageLabel("1 / 3")
            openPanelTab(R.string.tab_outline)
            waitFor { onView(withText(R.string.no_outline)).check(matches(isDisplayed())) }
        }
    }

    @Test
    fun bookmarkingAPageListsItAndTappingJumpsBack() {
        openPdf(6).use {
            waitForPageLabel("1 / 6")
            repeat(2) { onView(withId(R.id.nextPage)).perform(click()) }
            waitForPageLabel("3 / 6")
            onView(withContentDescription(R.string.add_bookmark)).perform(click())
            waitFor { onView(withContentDescription(R.string.remove_bookmark)).check(matches(isDisplayed())) }

            onView(withId(R.id.prevPage)).perform(click())
            waitForPageLabel("2 / 6")
            openPanelTab(R.string.tab_bookmarks)
            waitFor { onView(allOf(withId(R.id.bookmarkTitle), withText("Page 3"))).check(matches(isDisplayed())) }
            onView(allOf(withId(R.id.bookmarkTitle), withText("Page 3"))).perform(click())
            waitForPageLabel("3 / 6")
        }
    }

    @Test
    fun bookmarksAreKeptPerFileAndCanBeDeleted() {
        openPdf(6, name = "keep.pdf").use {
            waitForPageLabel("1 / 6")
            onView(withContentDescription(R.string.add_bookmark)).perform(click())
            waitFor { onView(withContentDescription(R.string.remove_bookmark)).check(matches(isDisplayed())) }
        }
        openPdf(6, name = "keep.pdf").use {
            waitForPageLabel("1 / 6")
            openPanelTab(R.string.tab_bookmarks)
            waitFor { onView(allOf(withId(R.id.bookmarkTitle), withText("Page 1"))).check(matches(isDisplayed())) }
            onView(withContentDescription("Delete bookmark Page 1")).perform(click())
            waitFor { onView(withText(R.string.no_bookmarks)).check(matches(isDisplayed())) }
        }
        openPdf(6, name = "other.pdf").use {
            waitForPageLabel("1 / 6")
            onView(withContentDescription(R.string.add_bookmark)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun longPressRenamesABookmark() {
        openPdf(4).use {
            waitForPageLabel("1 / 4")
            onView(withContentDescription(R.string.add_bookmark)).perform(click())
            openPanelTab(R.string.tab_bookmarks)
            waitFor { onView(allOf(withId(R.id.bookmarkTitle), withText("Page 1"))).check(matches(isDisplayed())) }
            onView(allOf(withId(R.id.bookmarkTitle), withText("Page 1"))).perform(longClick())
            onView(withId(R.id.bookmark_title_input)).inRoot(isDialog()).perform(replaceText("Budget"))
            onView(withText(R.string.save)).inRoot(isDialog()).perform(click())
            waitFor { onView(allOf(withId(R.id.bookmarkTitle), withText("Budget"))).check(matches(isDisplayed())) }
        }
    }
}
