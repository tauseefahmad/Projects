package com.example.pdfreader

import androidx.core.content.FileProvider
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
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Certificate-based (PAdES) signatures. */
@RunWith(AndroidJUnit4::class)
class DigitalSignatureTest {

    @Before
    fun setUp() {
        ReaderTestSupport.resetAppState()
        File(context.filesDir, "certificates").deleteRecursively()
    }

    @Test
    fun signedFileVerifiesAndDetectsLaterChanges() {
        val source = File(context.cacheDir, "shared/contract.pdf")
        TestPdfs.createWithText(context, "contract.pdf", listOf("I agree to everything"))
        val cert = DigitalSignatures.createPersonal(context, "Erika Mustermann", "erika@example.de", "secret".toCharArray())
        val identity = DigitalSignatures.load(cert, "secret".toCharArray())
        val signed = File(context.cacheDir, "signed.pdf")
        signed.outputStream().use { DigitalSignatures.sign(context, source, identity, "Agreed", "Berlin", it) }

        val check = DigitalSignatures.verify(context, signed).single()
        assertEquals("Erika Mustermann", check.signer)
        assertTrue(check.intact)
        assertTrue(check.coversWholeFile)
        assertTrue(check.selfSigned)
        assertEquals("Agreed", check.reason)
        assertEquals("ETSI.CAdES.detached", check.format)

        // Changing a signed byte breaks the signature.
        val bytes = signed.readBytes()
        val i = String(bytes, Charsets.ISO_8859_1).indexOf("I agree")
        val tampered = File(context.cacheDir, "tampered.pdf")
        if (i >= 0) {
            bytes[i] = 'U'.code.toByte()
            tampered.writeBytes(bytes)
            assertFalse(DigitalSignatures.verify(context, tampered).single().intact)
        }
    }

    @Test
    fun signFromTheMenuWithANewPersonalCertificate() {
        val f = File(context.cacheDir, "shared/menu-sign.pdf")
        TestPdfs.createWithText(context, "menu-sign.pdf", listOf("Please sign"))
        openUri(FileProvider.getUriForFile(context, "${context.packageName}.files", f)).use { scenario ->
            waitForPageLabel("1 / 1")
            scenario.onActivity { it.extras.showSignDigitally() }
            waitFor { onView(withText(R.string.dsig_create)).inRoot(isDialog()).perform(click()) }
            waitFor { onView(withId(R.id.dsig_name_input)).inRoot(isDialog()).perform(replaceText("Max Muster")) }
            onView(withId(R.id.dsig_new_password_input)).inRoot(isDialog()).perform(replaceText("pass1234"))
            onView(withText(R.string.dsig_create_action)).inRoot(isDialog()).perform(click())
            waitFor(20_000) { onView(withId(R.id.dsig_reason_input)).inRoot(isDialog()).perform(replaceText("Approved")) }
            onView(withText(R.string.dsig_sign)).inRoot(isDialog()).perform(click())
            waitFor(20_000) { assertTrue(DigitalSignatures.verify(context, f).singleOrNull()?.intact == true) }
            waitForPageLabel("1 / 1")
            // The signed file is reopened after saving; ask once it is shown again.
            try {
                waitFor(45_000) {
                    // Asking again while a check runs is ignored, so this starts at most one at a time.
                    scenario.onActivity { a -> if (a.extras.dialog?.isShowing != true) a.extras.showSignatures() }
                    onView(withText(containsString("Max Muster"))).inRoot(isDialog()).check(matches(isDisplayed()))
                }
            } catch (e: Throwable) {
                // Say what the screen was doing, and keep a picture of it for the CI screenshots.
                var state = ""
                scenario.onActivity { a -> state = "state=${a.stateForTest()} dialog=${a.extras.dialog?.isShowing} label=${a.findViewById<android.widget.TextView>(R.id.pageLabel).text}" }
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/tour").close()
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/tour/zz-signature-check-failed.png").let { ParcelStreams.readAll(it) }
                throw AssertionError("Check signatures did not show: $state\n${ReaderTestSupport.appLog()}", e)
            }
        }
    }
}
