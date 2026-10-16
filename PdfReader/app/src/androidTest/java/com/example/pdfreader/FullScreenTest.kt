package com.example.pdfreader

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.openPdf
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.google.android.material.appbar.MaterialToolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full screen. The first time an app hides the system bars, Android shows a "Viewing full
 * screen" notice in a system window over it, which takes input focus and swallows injected
 * touches and keys. So these tests drive the screen directly (menu actions, touches delivered
 * to the page list, Back through the dispatcher) and read its state from the activity.
 */
@RunWith(AndroidJUnit4::class)
class FullScreenTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    private fun enterFullScreen(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_full_screen, 0) }
        waitFor { assertFalse(toolbarShown(scenario)) }
    }

    private fun toolbarShown(scenario: ActivityScenario<MainActivity>): Boolean {
        var shown = false
        scenario.onActivity { shown = it.findViewById<View>(R.id.toolbar).visibility == View.VISIBLE }
        return shown
    }

    private fun navBarShown(scenario: ActivityScenario<MainActivity>): Boolean {
        var shown = false
        scenario.onActivity { shown = it.findViewById<View>(R.id.navBar).visibility == View.VISIBLE }
        return shown
    }

    private fun label(scenario: ActivityScenario<MainActivity>): String {
        var text = ""
        scenario.onActivity { text = it.findViewById<TextView>(R.id.pageLabel).text.toString() }
        return text
    }

    /** Taps the page list at a fraction of its width, halfway down. */
    private fun tapAt(scenario: ActivityScenario<MainActivity>, widthFraction: Float) {
        scenario.onActivity { activity ->
            val list = activity.findViewById<ZoomableRecyclerView>(R.id.pages)
            val x = list.width * widthFraction
            val y = list.height / 2f
            val time = android.os.SystemClock.uptimeMillis()
            for ((action, t) in listOf(MotionEvent.ACTION_DOWN to time, MotionEvent.ACTION_UP to time + 50)) {
                val event = MotionEvent.obtain(time, t, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                list.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        // A single tap is confirmed once the double-tap window has passed.
        Thread.sleep(android.view.ViewConfiguration.getDoubleTapTimeout() + 150L)
    }

    private fun keepsScreenOn(scenario: ActivityScenario<MainActivity>): Boolean {
        var on = false
        scenario.onActivity { on = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        return on
    }

    @Test
    fun fullScreenHidesTheBarsAndKeepsTheScreenOn() {
        openPdf(4).use { scenario ->
            waitFor { assertEquals("1 / 4", label(scenario)) }
            assertFalse(keepsScreenOn(scenario))
            enterFullScreen(scenario)
            assertFalse(navBarShown(scenario))
            assertTrue(keepsScreenOn(scenario))
        }
    }

    @Test
    fun tappingTheEdgesTurnsPages() {
        openPdf(4).use { scenario ->
            waitFor { assertEquals("1 / 4", label(scenario)) }
            enterFullScreen(scenario)
            tapAt(scenario, 0.92f)
            waitFor { assertEquals("2 / 4", label(scenario)) }
            tapAt(scenario, 0.92f)
            waitFor { assertEquals("3 / 4", label(scenario)) }
            tapAt(scenario, 0.08f)
            waitFor { assertEquals("2 / 4", label(scenario)) }
            // Still full screen after turning pages.
            assertFalse(toolbarShown(scenario))
        }
    }

    @Test
    fun tappingTheMiddleOrBackLeavesFullScreen() {
        openPdf(4).use { scenario ->
            waitFor { assertEquals("1 / 4", label(scenario)) }
            enterFullScreen(scenario)
            tapAt(scenario, 0.5f)
            waitFor { assertTrue(toolbarShown(scenario)) }
            assertFalse(keepsScreenOn(scenario))

            enterFullScreen(scenario)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor { assertTrue(toolbarShown(scenario)) }
            // Back only left full screen; the document is still open.
            waitFor { assertEquals("1 / 4", label(scenario)) }
            assertTrue(navBarShown(scenario))
        }
    }

    @Test
    fun keepScreenOnSettingWorksOutsideFullScreen() {
        openPdf(2).use { scenario ->
            waitFor { assertEquals("1 / 2", label(scenario)) }
            ReaderTestSupport.menu(R.string.display_menu, R.string.keep_screen_on)
            waitFor { assertTrue(keepsScreenOn(scenario)) }
        }
    }
}
