package com.example.pdfreader

import android.view.ViewGroup
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.DrawerMatchers.isOpen
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.recyclerview.widget.RecyclerView
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    // "apple" appears 5 times ignoring case (1 + 2 + 2); 4 with match case; 4 as a whole word.
    private fun fruitPdf() = TestPdfs.createWithText(
        context, "fruit.pdf",
        listOf(
            "An apple a day keeps the doctor away.",
            "Pineapple is not an apple.",
            "APPLE pie and apple tart.",
        ),
    )

    private fun search(query: String) {
        onView(withContentDescription(R.string.search)).perform(click())
        onView(withId(R.id.searchInput)).perform(replaceText(query), pressImeActionButton())
    }

    private fun waitForCount(text: String) = waitFor { onView(withId(R.id.searchCount)).check(matches(withText(text))) }

    private fun toggleOption(title: Int) {
        onView(withId(R.id.searchOptions)).perform(click())
        onView(withText(title)).perform(click())
    }

    @Test
    fun findsEveryMatchAndShowsTheCount() {
        openUri(fruitPdf()).use {
            waitForPageLabel("1 / 3")
            search("apple")
            waitForCount("1 of 5")
        }
    }

    @Test
    fun nextAndPreviousMoveThroughMatchesAcrossPages() {
        openUri(fruitPdf()).use {
            waitForPageLabel("1 / 3")
            search("apple")
            waitForCount("1 of 5")
            onView(withId(R.id.searchNext)).perform(click())
            waitForCount("2 of 5")
            waitForPageLabel("2 / 3")
            repeat(2) { onView(withId(R.id.searchNext)).perform(click()) }
            waitForCount("4 of 5")
            waitForPageLabel("3 / 3")
            repeat(2) { onView(withId(R.id.searchNext)).perform(click()) }
            waitForCount("1 of 5") // wraps around
            waitForPageLabel("1 / 3")
            onView(withId(R.id.searchPrev)).perform(click())
            waitForCount("5 of 5")
        }
    }

    @Test
    fun matchCaseAndWholeWordNarrowResults() {
        openUri(fruitPdf()).use {
            waitForPageLabel("1 / 3")
            search("apple")
            waitForCount("1 of 5")
            toggleOption(R.string.match_case)
            waitForCount("1 of 4")
            toggleOption(R.string.match_case)
            toggleOption(R.string.whole_word)
            waitForCount("1 of 4")
            toggleOption(R.string.match_case)
            waitForCount("1 of 3")
        }
    }

    @Test
    fun unknownWordShowsNoResults() {
        openUri(fruitPdf()).use {
            waitForPageLabel("1 / 3")
            search("banana")
            waitForCount(context.getString(R.string.no_results))
        }
    }

    @Test
    fun matchesAreHighlightedOnThePage() {
        openUri(fruitPdf()).use { scenario ->
            waitForPageLabel("1 / 3")
            search("apple")
            waitForCount("1 of 5")
            waitFor {
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.pages)
                    val page = (list.getChildAt(0) as ViewGroup).getChildAt(0) as PageView
                    assertEquals(0, page.page)
                    assertEquals("current match highlighted", 1, page.currentHighlights.size)
                    val box = page.currentHighlights[0]
                    // "apple" sits on the line drawn at y = 700 pt from the bottom (≈142 pt from the top).
                    assertTrue("highlight at $box", box.top in 100f..160f && box.width() > 10f)
                }
            }
        }
    }

    @Test
    fun resultsTabListsMatchesAndTappingJumps() {
        openUri(fruitPdf()).use { scenario ->
            waitForPageLabel("1 / 3")
            search("apple")
            waitForCount("1 of 5")
            onView(withContentDescription(R.string.open_side_panel)).perform(click())
            waitFor { onView(withId(R.id.drawer)).check(matches(isOpen())) }
            onView(withText(R.string.tab_search)).perform(click())
            waitFor { onView(withId(R.id.searchResults)).check(matches(isDisplayed())) }
            scenario.onActivity { assertEquals(5, it.findViewById<RecyclerView>(R.id.searchResults).adapter!!.itemCount) }
            onView(withId(R.id.searchResults)).perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(3, click()))
            waitForCount("4 of 5")
            waitForPageLabel("3 / 3")
        }
    }

    @Test
    fun closingSearchClearsHighlights() {
        openUri(fruitPdf()).use { scenario ->
            waitForPageLabel("1 / 3")
            search("apple")
            waitForCount("1 of 5")
            onView(withId(R.id.searchClose)).perform(click())
            onView(withId(R.id.searchBar)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            waitFor {
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.pages)
                    val page = (list.getChildAt(0) as ViewGroup).getChildAt(0) as PageView
                    assertTrue(page.highlights.isEmpty() && page.currentHighlights.isEmpty())
                }
            }
        }
    }
}
