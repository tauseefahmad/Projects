package com.example.pdfreader

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.print.PrintManager
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openPdf
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportTest {

    @Before
    fun setUp() {
        ReaderTestSupport.resetAppState()
        Intents.init()
    }

    @After
    fun tearDown() = Intents.release()

    private fun chooseMenu(title: Int) {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(title)).perform(click())
    }

    private fun original() = File(context.cacheDir, "shared/test-3.pdf")

    @Test
    fun shareSendsTheFileAsPdf() {
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        openPdf(3).use {
            waitForPageLabel("1 / 3")
            chooseMenu(R.string.share)
            waitFor {
                intended(allOf(hasAction(Intent.ACTION_CHOOSER), hasExtra(org.hamcrest.Matchers.equalTo(Intent.EXTRA_INTENT), allOf(hasAction(Intent.ACTION_SEND), hasType("application/pdf")))))
            }
            val chooser = Intents.getIntents().last { it.action == Intent.ACTION_CHOOSER }
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            @Suppress("DEPRECATION")
            val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            val size = context.contentResolver.openInputStream(stream)!!.use { it.readBytes().size }
            assertEquals(original().length().toInt(), size)
        }
    }

    @Test
    fun saveACopyWritesTheFileWhereTheUserChose() {
        val target = File(context.cacheDir, "saved-copy.pdf").apply { delete() }
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(target))))
        openPdf(3).use {
            waitForPageLabel("1 / 3")
            chooseMenu(R.string.save_copy)
            waitFor { assertTrue(target.exists() && target.length() == original().length()) }
            assertTrue(target.readBytes().contentEquals(original().readBytes()))
        }
    }

    @Test
    fun printOpensThePrintDialogWithAJob() {
        openPdf(3).use {
            waitForPageLabel("1 / 3")
            chooseMenu(R.string.print)
            val printManager = context.getSystemService(PrintManager::class.java)
            try {
                waitFor(15_000) { assertTrue(printManager.printJobs.any { it.info.label == "test-3.pdf" }) }
            } finally {
                printManager.printJobs.forEach { it.cancel() }
                // Leave the system print dialog.
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
    }
}
