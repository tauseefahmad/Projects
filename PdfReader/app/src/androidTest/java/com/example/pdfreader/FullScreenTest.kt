package com.example.pdfreader

import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openPdf
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullScreenTest {

    @Before
    fun setUp() {
        ReaderTestSupport.resetAppState()
        // Skip Android's one-time "swipe to exit full screen" notice, a system window over the app.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("settings put secure immersive_mode_confirmations confirmed").close()
    }

    private fun enterFullScreen() {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(R.string.full_screen)).perform(click())
        waitFor { onView(withId(R.id.toolbar)).check(matches(withEffectiveVisibility(Visibility.GONE))) }
    }

    /** Taps the page list at a fraction of its width, halfway down. */
    private fun tapAt(widthFraction: Float) {
        onView(withId(R.id.pages)).perform(
            GeneralClickAction(
                Tap.SINGLE,
                CoordinatesProvider { view ->
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    floatArrayOf(location[0] + view.width * widthFraction, location[1] + view.height / 2f)
                },
                Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.BUTTON_PRIMARY,
            )
        )
    }

    private fun keepsScreenOn(scenario: ActivityScenario<MainActivity>): Boolean {
        var on = false
        scenario.onActivity { on = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        return on
    }

    @Test
    fun fullScreenHidesTheBarsAndKeepsTheScreenOn() {
        openPdf(4).use { scenario ->
            waitForPageLabel("1 / 4")
            assertFalse(keepsScreenOn(scenario))
            enterFullScreen()
            onView(withId(R.id.navBar)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            assertTrue(keepsScreenOn(scenario))
        }
    }

    @Test
    fun tappingTheEdgesTurnsPages() {
        openPdf(4).use {
            waitForPageLabel("1 / 4")
            enterFullScreen()
            tapAt(0.92f)
            waitForPageLabel("2 / 4")
            tapAt(0.92f)
            waitForPageLabel("3 / 4")
            tapAt(0.08f)
            waitForPageLabel("2 / 4")
        }
    }

    @Test
    fun tappingTheMiddleOrBackLeavesFullScreen() {
        openPdf(4).use { scenario ->
            waitForPageLabel("1 / 4")
            enterFullScreen()
            tapAt(0.5f)
            waitFor { onView(withId(R.id.toolbar)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }
            assertFalse(keepsScreenOn(scenario))

            enterFullScreen()
            pressBack()
            waitFor { onView(withId(R.id.toolbar)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }
            // Back only left full screen; the document is still open.
            waitForPageLabel("1 / 4")
        }
    }

    @Test
    fun keepScreenOnSettingWorksOutsideFullScreen() {
        openPdf(2).use { scenario ->
            waitForPageLabel("1 / 2")
            ReaderTestSupport.menu(R.string.display_menu, R.string.keep_screen_on)
            waitFor { assertTrue(keepsScreenOn(scenario)) }
        }
    }
}
