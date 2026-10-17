package com.example.pdfreader

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry

/** Shared helpers for reader UI tests. */
object ReaderTestSupport {

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun resetAppState() {
        context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("recent_files", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(BookmarkStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "annotation-drafts").deleteRecursively()
        java.io.File(context.filesDir, "signatures").deleteRecursively()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    /** Opens a generated PDF of [pageCount] pages as if another app sent it with "Open with". */
    fun openPdf(
        pageCount: Int,
        name: String = "test-$pageCount.pdf",
        width: Int = 595,
        height: Int = 842,
    ): ActivityScenario<MainActivity> = openUri(TestPdfs.create(context, name, pageCount, width, height))

    fun openUri(uri: android.net.Uri): ActivityScenario<MainActivity> {
        val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return ActivityScenario.launch(intent)
    }

    /** Retries [block] until it passes or [timeoutMs] elapses; rendering and scrolling are asynchronous. */
    fun waitFor(timeoutMs: Long = 10_000, block: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                block()
                return
            } catch (t: Throwable) {
                if (System.currentTimeMillis() > deadline) throw t
                Thread.sleep(100)
            }
        }
    }

    /** Opens the overflow menu and follows a path of entries, e.g. menu(R.string.document_menu, R.string.share). */
    fun menu(vararg titles: Int) {
        androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu(context)
        for (title in titles) onView(withText(title)).perform(androidx.test.espresso.action.ViewActions.click())
    }

    /** Clicks a toolbar action whether it is shown as an icon or sits in the overflow menu. */
    fun toolbarAction(title: Int) {
        try {
            onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription(title))
                .perform(androidx.test.espresso.action.ViewActions.click())
        } catch (e: androidx.test.espresso.NoMatchingViewException) {
            menu(title)
        }
    }

    /** The app's recent log lines, to explain a failure. */
    fun appLog(): String {
        val pfd = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("logcat -d -t 60 -s PdfReader:V AndroidRuntime:E")
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Current text of the page label ("3 / 10"). */
    fun pageLabelText(): String {
        var text = ""
        onView(withId(R.id.pageLabel)).check { view, _ -> text = (view as TextView).text.toString() }
        return text
    }

    fun waitForPageLabel(expected: String, timeoutMs: Long = 10_000, diagnostics: () -> String = { "" }) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            // The screen can be briefly paused (e.g. while a file is reopened); keep waiting.
            val actual = try {
                pageLabelText()
            } catch (e: RuntimeException) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(100)
                continue
            }
            if (actual == expected) return
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Page label was \"$actual\", expected \"$expected\". ${diagnostics()}")
            }
            Thread.sleep(100)
        }
    }

    /** A point on the fast-scroll thumb track, 0 = top, 1 = bottom. */
    fun fastScrollPoint(fraction: Float) = CoordinatesProvider { view: View ->
        val location = IntArray(2).also(view::getLocationOnScreen)
        val density = view.resources.displayMetrics.density
        val thumbHeight = 48 * density
        val track = view.height - view.paddingTop - view.paddingBottom - thumbHeight
        floatArrayOf(
            location[0] + view.width - 16 * density,
            location[1] + view.paddingTop + thumbHeight / 2 + fraction * track,
        )
    }
}
