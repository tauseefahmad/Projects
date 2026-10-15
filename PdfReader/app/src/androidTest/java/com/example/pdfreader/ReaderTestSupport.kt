package com.example.pdfreader

import android.content.Context
import android.content.Intent
import android.view.View
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
    }

    /** Opens a generated PDF of [pageCount] pages as if another app sent it with "Open with". */
    fun openPdf(pageCount: Int, name: String = "test-$pageCount.pdf"): ActivityScenario<MainActivity> {
        val uri = TestPdfs.create(context, name, pageCount)
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

    fun waitForPageLabel(text: String) = waitFor {
        onView(withId(R.id.pageLabel)).check(matches(withText(text)))
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
