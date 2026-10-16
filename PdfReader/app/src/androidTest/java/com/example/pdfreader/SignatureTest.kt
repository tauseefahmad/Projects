package com.example.pdfreader

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import com.example.pdfreader.core.Annotation
import com.example.pdfreader.core.Box
import com.example.pdfreader.core.FieldFormat
import com.example.pdfreader.core.FieldKind
import com.example.pdfreader.core.Pt
import com.google.android.material.appbar.MaterialToolbar
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Signatures, marks for flat forms and the form builder. */
@RunWith(AndroidJUnit4::class)
class SignatureTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    private fun file(name: String) = File(context.cacheDir, "shared/$name")

    private fun open(name: String, pages: Int = 1): ActivityScenario<MainActivity> {
        val scenario = openUri(TestPdfs.createWithText(context, name, List(pages) { "Page ${it + 1}" }))
        waitForPageLabel("1 / $pages")
        waitFor { scenario.onActivity { assertTrue(it.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(0)?.pageRotation != null) } }
        return scenario
    }

    private fun menuAction(scenario: ActivityScenario<MainActivity>, id: Int) {
        scenario.onActivity { it.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(id, 0) }
    }

    private fun screenPoint(scenario: ActivityScenario<MainActivity>, at: Pt): FloatArray {
        var point = FloatArray(2)
        scenario.onActivity { activity ->
            val list = activity.findViewById<ZoomableRecyclerView>(R.id.pages)
            val view = list.pageViewFor(0)!!
            val local = view.toLocal(at.x, at.y)
            val onList = list.screenPointIn(view, local.x, local.y)
            val origin = IntArray(2).also(list::getLocationOnScreen)
            point = floatArrayOf(origin[0] + onList.x, origin[1] + onList.y)
        }
        return point
    }

    private fun tap(scenario: ActivityScenario<MainActivity>, at: Pt) {
        val p = screenPoint(scenario, at)
        onView(withId(R.id.pages)).perform(
            GeneralClickAction(Tap.SINGLE, CoordinatesProvider { p }, Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.BUTTON_PRIMARY)
        )
    }

    private fun drag(scenario: ActivityScenario<MainActivity>, from: Pt, to: Pt) {
        val a = screenPoint(scenario, from)
        val b = screenPoint(scenario, to)
        onView(withId(R.id.pages)).perform(GeneralSwipeAction(Swipe.SLOW, CoordinatesProvider { a }, CoordinatesProvider { b }, Press.FINGER))
    }

    private fun annotations(scenario: ActivityScenario<MainActivity>): List<Annotation> {
        var items = emptyList<Annotation>()
        scenario.onActivity { items = it.annotationSession?.items.orEmpty() }
        return items
    }

    private fun tool(label: Int) = onView(withContentDescription(label)).perform(scrollTo(), click())

    private fun dialogButton(text: Int) = onView(withText(text)).inRoot(isDialog()).perform(click())

    private fun doneAndSave() {
        onView(withId(R.id.annotDone)).perform(click())
        dialogButton(R.string.save)
    }

    private fun storedSubtypes(f: File): List<List<String>> =
        PDDocument.load(f).use { doc -> doc.pages.map { page -> page.annotations.map { it.subtype } } }

    @Test
    fun drawnSignatureIsPlacedCopiedToEveryPageAndKeptForNextTime() {
        open("sign.pdf", pages = 3).use { scenario ->
            menuAction(scenario, R.id.action_sign)
            // Nothing saved yet: straight to making one.
            waitFor { onView(withText(R.string.sign_draw)).inRoot(isDialog()).perform(click()) }
            waitFor { onView(withId(R.id.signature_pad)).inRoot(isDialog()).check(matches(isDisplayed())) }
            scenario.onActivity { it.annotateController.signatures.pad!!.addStroke(listOf(30f to 120f, 120f to 60f, 200f to 130f, 320f to 70f)) }
            waitFor { onView(withText(R.string.use_signature)).inRoot(isDialog()).check(matches(isEnabled())) }
            dialogButton(R.string.use_signature)
            assertEquals(1, SignatureStore(context).list().size)

            tap(scenario, Pt(400f, 600f))
            waitFor { assertEquals(listOf(Annotation.Image::class), annotations(scenario).map { it::class }) }
            val signature = annotations(scenario).single() as Annotation.Image
            assertTrue(signature.box.contains(400f, 600f))
            assertTrue(File(signature.imagePath).exists())

            // It is selected, ready to be copied onto the other pages.
            onView(withId(R.id.annotCopyPages)).perform(click())
            onView(withId(R.id.copy_pages_input)).inRoot(isDialog()).check(matches(withText("1-3")))
            dialogButton(R.string.ok)
            waitFor { assertEquals(listOf(0, 1, 2), annotations(scenario).map { it.page }.sorted()) }
            assertTrue(annotations(scenario).all { it.bounds == signature.box })

            // Next time the saved signature is offered.
            tool(R.string.tool_sign)
            waitFor { onView(withContentDescription(R.string.signature)).inRoot(isDialog()).perform(click()) }
            tap(scenario, Pt(150f, 300f))
            waitFor { assertEquals(4, annotations(scenario).size) }

            doneAndSave()
            waitFor { assertEquals(listOf(listOf("Stamp", "Stamp"), listOf("Stamp"), listOf("Stamp")), storedSubtypes(file("sign.pdf"))) }
        }
    }

    @Test
    fun typedSignatureFillsASignatureField() {
        val f = file("sign-field.pdf")
        TestPdfs.createWithText(context, "sign-field.pdf", listOf("Contract"))
        val fieldBox = Box(300f, 600f, 500f, 650f)
        PDDocument.load(f).use { doc ->
            FormIO.create(doc, listOf(Annotation.Field("f1", 0, 0, "", 0, box = fieldBox, kind = FieldKind.SIGNATURE, name = "Signature")))
            val temp = File(context.cacheDir, "sign-field-temp.pdf")
            doc.save(temp)
            temp.copyTo(f, overwrite = true)
            temp.delete()
        }
        openUri(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)).use { scenario ->
            waitForPageLabel("1 / 1")
            waitFor {
                var kinds = emptyList<FieldKind>()
                scenario.onActivity { kinds = it.annotationSession?.formFields.orEmpty().map { field -> field.kind } }
                assertEquals(listOf(FieldKind.SIGNATURE), kinds)
            }
            waitFor { scenario.onActivity { assertTrue(it.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(0)?.pageRotation != null) } }
            tap(scenario, Pt(400f, 625f))
            waitFor { onView(withText(R.string.sign_type)).inRoot(isDialog()).perform(click()) }
            waitFor { onView(withId(R.id.signature_text)).inRoot(isDialog()).perform(replaceText("Ada Lovelace")) }
            dialogButton(R.string.use_signature)
            waitFor { assertEquals(1, annotations(scenario).size) }
            val image = annotations(scenario).single() as Annotation.Image
            val b = image.box
            assertTrue("$b inside $fieldBox", b.left >= fieldBox.left && b.right <= fieldBox.right && b.top >= fieldBox.top && b.bottom <= fieldBox.bottom)
            // Typed signatures are wide: it fills the field's width.
            assertTrue(b.width > fieldBox.width * 0.6f)
        }
    }

    @Test
    fun tickCrossAndDateMarkFlatForms() {
        open("marks.pdf").use { scenario ->
            ReaderTestSupport.toolbarAction(R.string.annotate)
            waitFor { onView(withId(R.id.annotateBar)).check(matches(isDisplayed())) }
            tool(R.string.tool_tick)
            tap(scenario, Pt(100f, 300f))
            tool(R.string.tool_cross)
            tap(scenario, Pt(100f, 340f))
            tool(R.string.tool_date)
            tap(scenario, Pt(200f, 380f))
            waitFor { assertEquals(3, annotations(scenario).size) }
            val (tick, cross, date) = annotations(scenario)
            assertEquals(1, (tick as Annotation.Ink).strokes.size)
            assertTrue(tick.bounds.contains(100f, 300f))
            assertEquals(2, (cross as Annotation.Ink).strokes.size)
            assertEquals(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date()), (date as Annotation.FreeText).text)
            assertTrue(date.box.contains(200f, 380f))
        }
    }

    @Test
    fun formBuilderAddsFieldsThatBecomeFillableAfterSaving() {
        open("builder.pdf").use { scenario ->
            menuAction(scenario, R.id.action_build_form)
            waitFor { onView(withId(R.id.annotateBar)).check(matches(isDisplayed())) }
            drag(scenario, Pt(150f, 200f), Pt(350f, 222f))
            waitFor { onView(withId(R.id.field_name_input)).inRoot(isDialog()).perform(replaceText("City")) }
            dialogButton(R.string.ok)
            waitFor { assertEquals(1, annotations(scenario).size) }

            tap(scenario, Pt(160f, 300f))
            waitFor { onView(withText(R.string.field_type_dropdown)).inRoot(isDialog()).perform(click()) }
            onView(withId(R.id.field_name_input)).inRoot(isDialog()).perform(replaceText("Size"))
            // A dropdown needs options.
            dialogButton(R.string.ok)
            onView(withId(R.id.field_options_input)).inRoot(isDialog()).perform(replaceText("S, M, L"))
            dialogButton(R.string.ok)

            tap(scenario, Pt(160f, 360f))
            waitFor { onView(withText(R.string.field_type_checkbox)).inRoot(isDialog()).perform(click()) }
            onView(withId(R.id.field_name_input)).inRoot(isDialog()).perform(replaceText("Agree"))
            dialogButton(R.string.ok)
            waitFor { assertEquals(3, annotations(scenario).size) }
            val drawn = annotations(scenario).first() as Annotation.Field
            assertEquals(FieldKind.TEXT, drawn.kind)
            assertEquals(FieldFormat.PLAIN, drawn.format)
            assertEquals(200f, drawn.box.width, 3f)

            doneAndSave()
            // Saved as real fields: the reopened file has a fillable form and no pending fields.
            waitFor {
                var fields = emptyList<Pair<String, FieldKind>>()
                scenario.onActivity { fields = it.annotationSession?.formFields.orEmpty().map { f -> f.name to f.kind } }
                assertEquals(listOf("City" to FieldKind.TEXT, "Size" to FieldKind.CHOICE, "Agree" to FieldKind.CHECKBOX), fields)
            }
            assertTrue(annotations(scenario).isEmpty())
            PDDocument.load(file("builder.pdf")).use { doc ->
                val form = doc.documentCatalog.acroForm!!
                assertEquals(listOf("S", "M", "L"), (form.getField("Size") as com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox).options)
            }
        }
    }
}
