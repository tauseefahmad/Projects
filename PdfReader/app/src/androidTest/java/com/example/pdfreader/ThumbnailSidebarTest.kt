package com.example.pdfreader

import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.DrawerMatchers.isClosed
import androidx.test.espresso.contrib.DrawerMatchers.isOpen
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.openPdf
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailSidebarTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    private fun openPanel() {
        onView(withContentDescription(R.string.open_side_panel)).perform(click())
        waitFor { onView(withId(R.id.drawer)).check(matches(isOpen())) }
    }

    @Test
    fun panelShowsAThumbnailForEveryPage() {
        openPdf(6).use { scenario ->
            waitForPageLabel("1 / 6")
            openPanel()
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.thumbnails)
                assertEquals(6, list.adapter!!.itemCount)
            }
            // Thumbnails render lazily.
            waitFor {
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.thumbnails)
                    val first = list.findViewHolderForAdapterPosition(0)!!.itemView
                    assertNotNull(first.findViewById<ImageView>(R.id.thumbnailImage).drawable)
                }
            }
        }
    }

    @Test
    fun tappingAThumbnailJumpsToThatPageAndClosesThePanel() {
        openPdf(6).use {
            waitForPageLabel("1 / 6")
            openPanel()
            onView(withId(R.id.thumbnails)).perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(3, click()))
            waitForPageLabel("4 / 6")
            waitFor { onView(withId(R.id.drawer)).check(matches(isClosed())) }
        }
    }

    @Test
    fun currentPageIsHighlighted() {
        openPdf(6).use { scenario ->
            waitForPageLabel("1 / 6")
            repeat(2) { onView(withId(R.id.nextPage)).perform(click()) }
            waitForPageLabel("3 / 6")
            openPanel()
            waitFor {
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.thumbnails)
                    assertTrue(list.findViewHolderForAdapterPosition(2)!!.itemView.isSelected)
                    assertTrue(!list.findViewHolderForAdapterPosition(0)!!.itemView.isSelected)
                }
            }
        }
    }

    @Test
    fun backClosesThePanel() {
        openPdf(3).use {
            waitForPageLabel("1 / 3")
            openPanel()
            androidx.test.espresso.Espresso.pressBack()
            waitFor { onView(withId(R.id.drawer)).check(matches(isClosed())) }
            waitForPageLabel("1 / 3")
        }
    }
}
