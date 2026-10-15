package com.example.pdfreader

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PropertiesTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    @Test
    fun propertiesShowMetadataPageCountAndSize() {
        val uri = TestPdfs.createWithInfo(context, "report.pdf", "Annual Report 2026", "Sara Example", "Finances")
        openUri(uri).use {
            waitForPageLabel("1 / 3")
            ReaderTestSupport.menu(R.string.document_menu, R.string.properties)
            waitFor { onView(withText("Annual Report 2026")).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withText("Sara Example")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Finances")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("report.pdf")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("3")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("A4 (210 × 297 mm)")).inRoot(isDialog()).check(matches(isDisplayed()))
        }
    }
}
