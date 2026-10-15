package com.example.pdfreader

import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openPdf
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    @After
    fun tearDown() = ReaderTestSupport.resetAppState()

    private fun chooseTheme(title: Int) {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(R.string.theme_menu)).perform(click())
        onView(withText(title)).perform(click())
    }

    private fun pageFilter(scenario: ActivityScenario<MainActivity>): Any? {
        var filter: Any? = null
        scenario.onActivity { filter = it.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(0)?.colorFilter }
        return filter
    }

    @Test
    fun darkThemeSwitchesTheAppToNightMode() {
        openPdf(2).use { scenario ->
            waitForPageLabel("1 / 2")
            chooseTheme(R.string.theme_dark)
            waitFor {
                scenario.onActivity { activity ->
                    val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    assertEquals(Configuration.UI_MODE_NIGHT_YES, night)
                }
            }
            assertEquals(AppTheme.DARK, ReaderSettings(context).theme)
            assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
            // The document stays open on the same page after the screen is recreated.
            waitForPageLabel("1 / 2")
        }
    }

    @Test
    fun sepiaThemeTintsPages() {
        openPdf(2).use { scenario ->
            waitForPageLabel("1 / 2")
            chooseTheme(R.string.theme_sepia)
            waitFor { assertSame(PageFilters.SEPIA, pageFilter(scenario)) }
            assertEquals(AppTheme.SEPIA, ReaderSettings(context).theme)
        }
    }

    @Test
    fun invertPageColorsForNightReading() {
        openPdf(2).use { scenario ->
            waitForPageLabel("1 / 2")
            waitFor { scenario.onActivity { checkNotNull(it.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(0)) } }
            assertEquals(null, pageFilter(scenario))
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.dark_pages)).perform(click())
            waitFor { assertSame(PageFilters.INVERT, pageFilter(scenario)) }
            assertTrue(ReaderSettings(context).darkPages)
        }
    }
}
