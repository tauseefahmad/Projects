package com.example.pdfreader

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.doubleClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openPdf
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import com.example.pdfreader.core.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ZoomTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    private fun chooseZoom(title: String) {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(R.string.zoom_menu)).perform(click())
        onView(withText(title)).perform(click())
    }

    private fun waitForZoomLabel(text: String) = waitFor {
        onView(withId(R.id.zoomLabel)).check(matches(withText(text)))
    }

    private fun sendCtrlKey(keyCode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val time = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        instrumentation.sendKeySync(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        instrumentation.sendKeySync(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private class ZoomSnapshot(val drawZoom: Float, val layoutScale: Float, val pageWidth: Int, val pageHeight: Int, val listWidth: Int, val listHeight: Int)

    private fun snapshot(scenario: ActivityScenario<MainActivity>): ZoomSnapshot {
        var result: ZoomSnapshot? = null
        scenario.onActivity { activity ->
            val list = activity.findViewById<ZoomableRecyclerView>(R.id.pages)
            val row = list.getChildAt(0) as android.view.ViewGroup
            val image = row.getChildAt(0)
            result = ZoomSnapshot(list.zoom, (list.adapter as PageAdapter).layoutScale, image.width, image.height, list.width, list.height)
        }
        return result!!
    }

    /** Pages much taller than a phone screen, so "fit page" has to shrink them. */
    private fun openTallPdf(pages: Int, name: String) = openPdf(pages, name = name, width = 595, height = 1600)

    @Test
    fun fitPageShowsTheWholePage() {
        openTallPdf(3, "tall-3.pdf").use { scenario ->
            waitForPageLabel("1 / 3")
            chooseZoom(context.getString(R.string.zoom_fit_page))
            waitFor {
                val s = snapshot(scenario)
                assertTrue("layout should shrink", s.layoutScale < 1f)
                assertTrue("page should fit vertically", s.pageHeight <= s.listHeight)
                assertTrue("page should be narrower than the screen", s.pageWidth < s.listWidth)
                assertEquals(1f, s.drawZoom)
            }
        }
    }

    @Test
    fun fitWidthFillsTheWidth() {
        openTallPdf(3, "tall-3.pdf").use { scenario ->
            waitForPageLabel("1 / 3")
            chooseZoom(context.getString(R.string.zoom_fit_page))
            waitFor { assertTrue(snapshot(scenario).layoutScale < 1f) }
            chooseZoom(context.getString(R.string.zoom_fit_width))
            waitFor {
                val s = snapshot(scenario)
                assertEquals(1f, s.layoutScale)
                assertEquals(1f, s.drawZoom)
            }
        }
    }

    @Test
    fun actualSizeShowsOnePointPerDp() {
        openPdf(3).use { scenario ->
            waitForPageLabel("1 / 3")
            chooseZoom(context.getString(R.string.zoom_actual_size))
            waitForZoomLabel("100%")
            waitFor {
                val s = snapshot(scenario)
                val shownWidth = s.pageWidth * s.drawZoom
                val expected = 595 * context.resources.displayMetrics.density
                assertTrue("shown $shownWidth, expected $expected", abs(shownWidth - expected) < 4)
            }
        }
    }

    @Test
    fun presetFromMenuZooms() {
        openPdf(3).use { scenario ->
            waitForPageLabel("1 / 3")
            chooseZoom("200%")
            waitForZoomLabel("200%")
            waitFor { assertTrue(snapshot(scenario).drawZoom > 1f) }
        }
    }

    @Test
    fun ctrlPlusMinusStepThroughPresetsAndCtrlZeroFits() {
        openPdf(3).use { scenario ->
            waitForPageLabel("1 / 3")
            chooseZoom(context.getString(R.string.zoom_actual_size))
            waitForZoomLabel("100%")
            sendCtrlKey(KeyEvent.KEYCODE_EQUALS)
            waitForZoomLabel("125%")
            sendCtrlKey(KeyEvent.KEYCODE_MINUS)
            waitForZoomLabel("100%")
            sendCtrlKey(KeyEvent.KEYCODE_MINUS)
            waitForZoomLabel("75%")
            sendCtrlKey(KeyEvent.KEYCODE_0)
            waitFor {
                val s = snapshot(scenario)
                assertEquals(1f, s.layoutScale)
                assertEquals(1f, s.drawZoom)
            }
        }
    }

    @Test
    fun doubleTapZoomsInAndOut() {
        openPdf(3).use { scenario ->
            waitForPageLabel("1 / 3")
            onView(withId(R.id.pages)).perform(doubleClick())
            waitFor { assertTrue(snapshot(scenario).drawZoom > 2f) }
            onView(withId(R.id.pages)).perform(doubleClick())
            waitFor { assertEquals(1f, snapshot(scenario).drawZoom) }
        }
    }

    @Test
    fun zoomingInRendersSharpDetail() {
        openPdf(3).use { scenario ->
            waitForPageLabel("1 / 3")
            chooseZoom("300%")
            waitForZoomLabel("300%")
            waitFor {
                scenario.onActivity { activity ->
                    val list = activity.findViewById<ZoomableRecyclerView>(R.id.pages)
                    val row = list.getChildAt(0) as android.view.ViewGroup
                    val pageView = row.getChildAt(0) as PageView
                    assertTrue("expected a sharp detail render on the visible page", pageView.hasDetail)
                }
            }
        }
    }

    @Test
    fun fitPageIsRememberedForTheNextDocument() {
        openTallPdf(3, "tall-3.pdf").use { scenario ->
            waitForPageLabel("1 / 3")
            chooseZoom(context.getString(R.string.zoom_fit_page))
            waitFor { assertTrue(snapshot(scenario).layoutScale < 1f) }
        }
        assertEquals(FitMode.FIT_PAGE, ReaderSettings(context).fitMode(ViewMode.CONTINUOUS))
        openTallPdf(4, "tall-4.pdf").use { scenario ->
            waitForPageLabel("1 / 4")
            waitFor { assertTrue(snapshot(scenario).layoutScale < 1f) }
        }
    }
}
