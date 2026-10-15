package com.example.pdfreader

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openPdf
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RotateViewTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    private fun rotateFromMenu() {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(R.string.rotate_view)).perform(click())
    }

    private fun firstPageSize(scenario: ActivityScenario<MainActivity>, page: Int = 0): Pair<Int, Int> {
        var size = 0 to 0
        scenario.onActivity { activity ->
            val view = activity.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(page)!!
            size = view.width to view.height
        }
        return size
    }

    @Test
    fun rotatingTurnsPortraitPagesLandscapeAndKeepsThePage() {
        openPdf(4).use { scenario ->
            waitForPageLabel("1 / 4")
            onView(withId(R.id.nextPage)).perform(click())
            waitForPageLabel("2 / 4")
            waitFor { firstPageSize(scenario, 1).let { (w, h) -> assertTrue(h > w) } }
            rotateFromMenu()
            waitForPageLabel("2 / 4")
            waitFor { firstPageSize(scenario, 1).let { (w, h) -> assertTrue("landscape after rotating: ${w}x$h", w > h) } }
        }
    }

    @Test
    fun fourTurnsAndCtrlRReturnToUpright() {
        openPdf(2).use { scenario ->
            waitForPageLabel("1 / 2")
            val (w0, h0) = firstPageSize(scenario)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            repeat(4) {
                val time = SystemClock.uptimeMillis()
                val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                instrumentation.sendKeySync(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_R, 0, meta))
                instrumentation.sendKeySync(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_R, 0, meta))
                instrumentation.waitForIdleSync()
            }
            waitFor { assertEquals(w0 to h0, firstPageSize(scenario)) }
        }
    }

    @Test
    fun linksStillWorkWhenRotated() {
        openUri(TestPdfs.createWithLinks(context, "links-rot.pdf")).use { scenario ->
            waitForPageLabel("1 / 3")
            rotateFromMenu()
            // The internal link covers (72..272, 100..140) pt on the unrotated page.
            waitFor { scenario.onActivity { checkNotNull(it.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(0)) } }
            var point = FloatArray(2)
            scenario.onActivity { activity ->
                val list = activity.findViewById<ZoomableRecyclerView>(R.id.pages)
                val view = list.pageViewFor(0)!!
                val local = view.toLocal(150f, 120f)
                val screen = list.screenPointIn(view, local.x, local.y)
                val origin = IntArray(2).also(list::getLocationOnScreen)
                point = floatArrayOf(origin[0] + screen.x, origin[1] + screen.y)
            }
            onView(withId(R.id.pages)).perform(
                GeneralClickAction(Tap.SINGLE, CoordinatesProvider { point }, Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.BUTTON_PRIMARY)
            )
            waitForPageLabel("3 / 3")
        }
    }
}
