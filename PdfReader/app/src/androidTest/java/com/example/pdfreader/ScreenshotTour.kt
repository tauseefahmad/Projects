package com.example.pdfreader

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import com.example.pdfreader.core.Annotation
import com.example.pdfreader.core.Box
import com.example.pdfreader.core.MarkupKind
import com.example.pdfreader.core.Pt
import com.example.pdfreader.core.SummaryLength
import com.google.android.material.appbar.MaterialToolbar
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks through the main features on the emulator and takes a screenshot of each screen
 * (saved to /sdcard/Download/tour, collected by CI). Each step is independent: a step that
 * goes wrong is noted in tour.txt and the tour carries on, so the pictures show what works.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {

    private val dir = "/sdcard/Download/tour"
    private val log = StringBuilder()
    private var index = 0

    @Before
    fun setUp() {
        ReaderTestSupport.resetAppState()
        shell("mkdir -p $dir")
    }

    private fun shell(command: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelStreams.readAll(pfd)
    }

    private fun shot(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(900)
        index++
        shell("screencap -p $dir/${index.toString().padStart(2, '0')}-$name.png")
        log.append("OK   ").append(name).append('\n')
    }

    private fun step(name: String, block: () -> Unit) {
        try {
            block()
            shot(name)
        } catch (t: Throwable) {
            log.append("FAIL ").append(name).append(": ").append(t.toString().take(300)).append('\n')
            runCatching { shot("$name-failed") }
        } finally {
            closeLeftovers()
        }
    }

    /** The reader currently on screen; cleanup only touches it. */
    private var current: ActivityScenario<MainActivity>? = null

    /**
     * Closes the dialogs, menus and screens a step left open, without leaving the reader:
     * Back is pressed only while something covers the reader's window.
     */
    private fun closeLeftovers() {
        val scenario = current ?: return
        repeat(3) {
            Thread.sleep(300)
            var covered = false
            runCatching { scenario.onActivity { covered = !it.hasWindowFocus() } }
            if (covered) runCatching { Espresso.pressBackUnconditionally() }
        }
        runCatching { scenario.onActivity { it.findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer).closeDrawers() } }
        Thread.sleep(300)
    }

    private fun menu(scenario: ActivityScenario<MainActivity>, id: Int) =
        scenario.onActivity { it.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(id, 0) }

    private val pages = listOf(
        "Rental agreement between Anna Schmidt and Max Muster for the flat in Berlin.",
        "The tenant may terminate the lease with a notice period of three months.",
        "Rent: 850 EUR per month, payable to IBAN DE89 3704 0044 0532 0130 00.",
        "Pets are allowed only after written consent of the landlord.",
    )

    @Test
    fun tour() {
        val uri = TestPdfs.createWithText(context, "Rental agreement.pdf", pages)
        // 1. Start screen.
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { shot("start-screen") }

        openUri(uri).use { scenario ->
            current = scenario
            waitForPageLabel("1 / 4")
            shot("reader")

            step("menu") { Espresso.openActionBarOverflowOrOptionsMenu(context); Thread.sleep(500); shot("menu-open"); Espresso.pressBack() }
            step("search") {
                menu(scenario, R.id.action_search)
                onView(withId(R.id.searchInput)).perform(replaceText("notice"))
                Thread.sleep(1500)
            }
            step("side-panel-thumbnails") { scenario.onActivity { it.findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer).openDrawer(androidx.core.view.GravityCompat.START) } }

            step("annotations") {
                scenario.onActivity { a ->
                    a.annotateController.start()
                    val session = a.annotationSession!!
                    session.add(Annotation.Markup("h1", 0, 0xFFFFEB3B.toInt(), "Tour", 0, "", MarkupKind.HIGHLIGHT, listOf(Box(70f, 125f, 400f, 145f)), "Rental agreement"))
                    session.add(Annotation.Ink("i1", 0, 0xFF1E88E5.toInt(), "Tour", 0, strokes = listOf(listOf(Pt(100f, 300f), Pt(180f, 260f), Pt(260f, 330f), Pt(340f, 270f))), width = 3f))
                    session.add(Annotation.Note("n1", 0, 0xFFFFEB3B.toInt(), "Tour", 0, "Check the address", Pt(450f, 200f)))
                    session.add(Annotation.Stamp("s1", 0, 0xFF43A047.toInt(), "Tour", 0, box = Box(330f, 380f, 480f, 430f), kind = com.example.pdfreader.core.StampKind.APPROVED, label = "APPROVED", subtitle = "Tour"))
                }
            }
            step("tools-menu") { Espresso.openActionBarOverflowOrOptionsMenu(context); onView(withText(R.string.tools_menu)).perform(click()); Thread.sleep(500); shot("tools-submenu") }
            step("signature-pad") {
                scenario.onActivity { it.annotateController.signatures.draw(SignatureStore.Kind.SIGNATURE) {} }
                Thread.sleep(500)
                scenario.onActivity { it.annotateController.signatures.pad!!.addStroke(listOf(30f to 140f, 90f to 60f, 150f to 150f, 210f to 70f, 290f to 140f, 380f to 80f)) }
            }
            step("redaction-marked") {
                scenario.onActivity { a ->
                    a.annotateController.start()
                    a.annotationSession!!.add(Annotation.Redaction("r1", 0, 0, "Tour", 0, box = Box(70f, 140f, 300f, 160f)))
                }
            }
            step("summary") { scenario.onActivity { it.insights.summarize(SummaryLength.PARAGRAPH) }; waitFor { onView(withText(R.string.summary_title)).inRoot(isDialog()).check { _, _ -> } } }
            step("ask-this-document") { scenario.onActivity { it.insights.ask("What is the notice period?") }; Thread.sleep(1500) }
            step("extract-data") { scenario.onActivity { it.insights.showExtract() }; Thread.sleep(1500) }
            step("contract-review") { scenario.onActivity { it.insights.showContractReview() }; Thread.sleep(1500) }
            step("accessibility-check") { scenario.onActivity { it.extras.showAccessibility() }; Thread.sleep(1500) }
            step("compress") { menu(scenario, R.id.tool_compress) }
            step("password") { menu(scenario, R.id.tool_protect) }
            step("export") { menu(scenario, R.id.tool_export) }
            step("library") { scenario.onActivity { it.openLibrary() }; Thread.sleep(1200) }
        }

        // Forms: a fillable form with the field bar.
        val form = TestForms.create(context, "Application form.pdf")
        openUri(form).use { scenario ->
            current = scenario
            waitForPageLabel("1 / 1")
            step("form-filling") {
                waitFor { scenario.onActivity { check(it.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(0)?.pageRotation != null) } }
                scenario.onActivity { a ->
                    val fields = a.annotationSession!!.formFields
                    fields.firstOrNull { it.name == "Vorname" }?.let { a.annotationSession!!.setFieldValue(it, "Erika") }
                    fields.firstOrNull { it.name == "Agree" }?.let { a.annotationSession!!.setFieldValue(it, it.onValue) }
                    fields.firstOrNull { it.name == "Email" }?.let { a.formController.edit(it) }
                }
            }
        }

        // German interface.
        openUri(uri).use { scenario ->
            current = scenario
            waitForPageLabel("1 / 4")
            step("german-interface") {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("de")) }
                Thread.sleep(2500)
                Espresso.openActionBarOverflowOrOptionsMenu(context)
                Thread.sleep(600)
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
        }
        current = null

        File(context.cacheDir, "tour.txt").writeText(log.toString())
        shell("cp ${File(context.cacheDir, "tour.txt").path} $dir/tour.txt")
        android.util.Log.i("ScreenshotTour", "\n$log")
        println(log)
        // Keep the uri helper referenced for FileProvider-based opens.
        FileProvider::class.java
    }
}

/** Reads a shell command's output to the end, so the command has finished. */
object ParcelStreams {
    fun readAll(pfd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
}
