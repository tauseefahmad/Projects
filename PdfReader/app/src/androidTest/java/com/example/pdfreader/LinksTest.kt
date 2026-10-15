package com.example.pdfreader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinksTest {

    @Before
    fun setUp() {
        ReaderTestSupport.resetAppState()
        Intents.init()
        // Never actually launch a browser from tests.
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun tearDown() = Intents.release()

    /** Taps page 1 at a point given in PDF points from its top-left corner. */
    private fun tapOnPage(scenario: ActivityScenario<MainActivity>, xPt: Float, yPt: Float) {
        waitFor { scenario.onActivity { checkNotNull(it.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(0)) } }
        var point = FloatArray(2)
        scenario.onActivity { activity ->
            val list = activity.findViewById<ZoomableRecyclerView>(R.id.pages)
            val view = list.pageViewFor(0)!!
            val local = list.screenPointIn(view, xPt * view.scale, yPt * view.scale)
            val origin = IntArray(2).also(list::getLocationOnScreen)
            point = floatArrayOf(origin[0] + local.x, origin[1] + local.y)
        }
        onView(withId(R.id.pages)).perform(
            GeneralClickAction(Tap.SINGLE, CoordinatesProvider { point }, Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.BUTTON_PRIMARY)
        )
    }

    @Test
    fun internalLinkJumpsToItsPage() {
        openUri(TestPdfs.createWithLinks(context, "links.pdf")).use { scenario ->
            waitForPageLabel("1 / 3")
            tapOnPage(scenario, 150f, 120f)
            waitForPageLabel("3 / 3")
        }
    }

    @Test
    fun externalLinkAsksFirstAndOpensTheBrowser() {
        openUri(TestPdfs.createWithLinks(context, "links.pdf")).use { scenario ->
            waitForPageLabel("1 / 3")
            tapOnPage(scenario, 150f, 220f)
            waitFor { onView(withText("https://example.com/")).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withText(R.string.open_link)).inRoot(isDialog()).perform(click())
            intended(allOf(hasAction(Intent.ACTION_VIEW), hasData("https://example.com/")))
        }
    }

    @Test
    fun cancellingTheLinkPromptOpensNothing() {
        openUri(TestPdfs.createWithLinks(context, "links.pdf")).use { scenario ->
            waitForPageLabel("1 / 3")
            tapOnPage(scenario, 150f, 220f)
            waitFor { onView(withText(R.string.open_link_title)).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withText(R.string.cancel)).inRoot(isDialog()).perform(click())
            onView(withText(R.string.open_link_title)).check(doesNotExist())
            Intents.assertNoUnverifiedIntents()
        }
    }

    @Test
    fun tappingOutsideLinksDoesNothing() {
        openUri(TestPdfs.createWithLinks(context, "links.pdf")).use { scenario ->
            waitForPageLabel("1 / 3")
            tapOnPage(scenario, 400f, 600f)
            Thread.sleep(800)
            waitForPageLabel("1 / 3")
            onView(withText(R.string.open_link_title)).check(doesNotExist())
        }
    }
}
