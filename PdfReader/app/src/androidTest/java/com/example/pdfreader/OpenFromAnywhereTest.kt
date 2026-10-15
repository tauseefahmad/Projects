package com.example.pdfreader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OpenFromAnywhereTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    @Test
    fun pdfSharedFromAnotherAppOpens() {
        val uri = TestPdfs.create(context, "shared-in.pdf", 3)
        val intent = Intent(Intent.ACTION_SEND)
            .setClass(context, MainActivity::class.java)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch<MainActivity>(intent).use { waitForPageLabel("1 / 3") }
    }

    private fun openUrl(url: String) {
        onView(withId(R.id.openUrlButton)).perform(click())
        onView(withId(R.id.url_input)).inRoot(isDialog()).perform(replaceText(url))
        onView(withText(R.string.open)).inRoot(isDialog()).perform(click())
    }

    @Test
    fun downloadsAndOpensAPdfFromAUrl() {
        val pdf = TestPdfs.bytes(context, 4)
        LocalHttpServer(mapOf("/files/report.pdf" to Triple(200, "application/pdf", pdf))).use { server ->
            ActivityScenario.launch(MainActivity::class.java).use {
                openUrl(server.url("/files/report.pdf"))
                waitForPageLabel("1 / 4", timeoutMs = 20_000)
                assertTrue(File(context.cacheDir, "shared/downloads/report.pdf").exists())
            }
        }
    }

    @Test
    fun urlThatIsNotAPdfShowsAnError() {
        LocalHttpServer(mapOf("/page" to Triple(200, "text/html", "<html>hello</html>".toByteArray()))).use { server ->
            ActivityScenario.launch(MainActivity::class.java).use {
                openUrl(server.url("/page"))
                waitFor(20_000) { onView(withText(R.string.download_not_pdf)).inRoot(isDialog()).check(matches(isDisplayed())) }
            }
        }
    }

    @Test
    fun missingFileShowsAnError() {
        LocalHttpServer(emptyMap()).use { server ->
            ActivityScenario.launch(MainActivity::class.java).use {
                openUrl(server.url("/missing.pdf"))
                waitFor(20_000) { onView(withText(R.string.download_failed)).inRoot(isDialog()).check(matches(isDisplayed())) }
            }
        }
    }

    @Test
    fun passwordProtectedPdfAsksForThePassword() {
        openUri(TestPdfs.createEncrypted(context, "locked.pdf", 2, userPassword = "secret")).use {
            waitFor { onView(withText(R.string.password_message)).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withId(R.id.password_input)).inRoot(isDialog()).perform(replaceText("wrong"))
            onView(withText(R.string.open)).inRoot(isDialog()).perform(click())
            waitFor { onView(withText(R.string.password_wrong)).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withId(R.id.password_input)).inRoot(isDialog()).perform(replaceText("secret"))
            onView(withText(R.string.open)).inRoot(isDialog()).perform(click())
            waitForPageLabel("1 / 2")
        }
    }

    @Test
    fun pdfWithOnlyAnOwnerPasswordOpensDirectly() {
        openUri(TestPdfs.createEncrypted(context, "restricted.pdf", 3, userPassword = "")).use {
            waitForPageLabel("1 / 3")
        }
    }
}
