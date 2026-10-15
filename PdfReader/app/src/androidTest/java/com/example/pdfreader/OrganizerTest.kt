package com.example.pdfreader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OrganizerTest {

    private val output = File(context.cacheDir, "organized.pdf")

    @Before
    fun setUp() {
        ReaderTestSupport.resetAppState()
        output.delete()
        Intents.init()
        // "Save a copy" / "Extract" write to a file we can read back.
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(output))))
    }

    @After
    fun tearDown() = Intents.release()

    private fun fivePages(name: String = "five.pdf") =
        TestPdfs.createWithText(context, name, (1..5).map { "Page $it" })

    private fun openOrganizer(uri: Uri): ActivityScenario<MainActivity> {
        val scenario = openUri(uri)
        waitForPageLabel("1 / 5")
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(R.string.organize_pages)).perform(click())
        waitFor { onView(withId(R.id.organizerGrid)).check(matches(isDisplayed())) }
        return scenario
    }

    /** Clicks a toolbar action whether it is shown as an icon or sits in the overflow menu. */
    private fun action(title: Int) {
        try {
            onView(withContentDescription(title)).perform(click())
        } catch (e: NoMatchingViewException) {
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(title)).perform(click())
        }
    }

    private fun tapPage(position: Int) =
        onView(withId(R.id.organizerGrid)).perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(position, click()))

    private fun gridCount(): Int {
        var count = -1
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // The organizer is the resumed activity; find its grid through the app's windows.
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first()
            count = activity.findViewById<RecyclerView>(R.id.organizerGrid).adapter!!.itemCount
        }
        return count
    }

    private fun saveCopy() {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(R.string.save_copy)).perform(click())
        waitFor { check(output.exists() && output.length() > 0) }
        Thread.sleep(300)
    }

    @Test
    fun deletingSelectedPagesCanBeUndone() {
        openOrganizer(fivePages()).use {
            tapPage(0)
            tapPage(2)
            waitFor { onView(withText(context.getString(R.string.selected_count, 2))).check(matches(isDisplayed())) }
            action(R.string.delete_pages)
            waitFor { assertEquals(3, gridCount()) }
            action(R.string.undo)
            waitFor { assertEquals(5, gridCount()) }
        }
    }

    @Test
    fun savingInPlaceUpdatesTheFileAndTheReader() {
        val uri = fivePages("inplace.pdf")
        openOrganizer(uri).use {
            tapPage(1)
            action(R.string.delete_pages)
            waitFor { assertEquals(4, gridCount()) }
            action(R.string.save)
            waitForPageLabel("1 / 4", timeoutMs = 15_000)
            assertEquals(listOf("Page 1", "Page 3", "Page 4", "Page 5"), TestPdfs.pageTexts(context, uri))
        }
    }

    @Test
    fun moveToPositionReordersPages() {
        openOrganizer(fivePages()).use {
            tapPage(4)
            action(R.string.move_pages)
            onView(withId(R.id.move_input)).inRoot(isDialog()).perform(replaceText("1"))
            onView(withText(R.string.go)).inRoot(isDialog()).perform(click())
            saveCopy()
            assertEquals(listOf("Page 5", "Page 1", "Page 2", "Page 3", "Page 4"), TestPdfs.pageTexts(context, Uri.fromFile(output)))
        }
    }

    @Test
    fun rotationIsSavedIntoTheFile() {
        openOrganizer(fivePages()).use {
            tapPage(0)
            action(R.string.rotate_right)
            tapPage(2)
            action(R.string.rotate_180)
            saveCopy()
            // Page 1 was rotated twice (right, then 180° with page 3 selected too).
            assertEquals(listOf(270, 0, 180, 0, 0), TestPdfs.pageRotations(context, Uri.fromFile(output)))
        }
    }

    @Test
    fun duplicateAndInsertBlankPage() {
        openOrganizer(fivePages()).use {
            tapPage(1)
            action(R.string.duplicate_pages)
            waitFor { assertEquals(6, gridCount()) }
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.insert_menu)).perform(click())
            onView(withText(R.string.insert_blank_a4)).perform(click())
            waitFor { assertEquals(7, gridCount()) }
            saveCopy()
            assertEquals(listOf("Page 1", "Page 2", "Page 2", "Page 3", "Page 4", "Page 5", ""), TestPdfs.pageTexts(context, Uri.fromFile(output)))
        }
    }

    @Test
    fun insertPagesFromAnotherPdf() {
        val other = TestPdfs.createWithText(context, "other.pdf", listOf("Extra A", "Extra B"))
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(other)))
        openOrganizer(fivePages()).use {
            tapPage(0)
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.insert_menu)).perform(click())
            onView(withText(R.string.insert_pdf)).perform(click())
            waitFor { assertEquals(7, gridCount()) }
            saveCopy()
            assertEquals(
                listOf("Page 1", "Extra A", "Extra B", "Page 2", "Page 3", "Page 4", "Page 5"),
                TestPdfs.pageTexts(context, Uri.fromFile(output)),
            )
        }
    }

    @Test
    fun extractSelectedPagesAsANewPdf() {
        openOrganizer(fivePages()).use {
            tapPage(1)
            tapPage(3)
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.extract_pages)).perform(click())
            waitFor { check(output.exists() && output.length() > 0) }
            Thread.sleep(300)
            assertEquals(listOf("Page 2", "Page 4"), TestPdfs.pageTexts(context, Uri.fromFile(output)))
            // Extracting doesn't change the document being organized.
            assertEquals(5, gridCount())
        }
    }

    @Test
    fun leavingWithUnsavedChangesAsksFirst() {
        openOrganizer(fivePages()).use {
            tapPage(0)
            action(R.string.delete_pages)
            waitFor { assertEquals(4, gridCount()) }
            pressBack()
            onView(withText(R.string.discard_changes_title)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(R.string.keep_editing)).inRoot(isDialog()).perform(click())
            assertEquals(4, gridCount())
        }
    }
}
