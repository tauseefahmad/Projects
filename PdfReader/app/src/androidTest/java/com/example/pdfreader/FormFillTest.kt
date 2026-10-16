package com.example.pdfreader

import android.content.Context
import android.net.Uri
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressImeActionButton
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
import com.example.pdfreader.core.AutofillProfile
import com.example.pdfreader.core.FieldFormat
import com.example.pdfreader.core.FieldKind
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Builds an interactive form for tests. Field boxes are given from the top-left, in points. */
object TestForms {

    data class Spec(val name: String, val top: Float, val left: Float = 150f, val width: Float = 200f, val height: Float = 20f)

    val vorname = Spec("Vorname", 100f)
    val email = Spec("Email", 140f)
    val geburtsdatum = Spec("Geburtsdatum", 180f)
    val agree = Spec("Agree", 230f, width = 16f, height = 16f)
    val choiceA = Spec("Choice", 270f, left = 150f, width = 16f, height = 16f)
    val choiceB = Spec("Choice", 270f, left = 250f, width = 16f, height = 16f)
    val country = Spec("Country", 310f)

    private const val HEIGHT = 842f

    private fun rect(s: Spec) = PDRectangle(s.left, HEIGHT - s.top - s.height, s.width, s.height)

    private fun buttonAppearance(doc: PDDocument, w: Float, h: Float, on: String): PDAppearanceDictionary {
        fun stream(checked: Boolean) = PDAppearanceStream(doc).apply {
            bBox = PDRectangle(w, h)
            resources = PDResources()
            PDPageContentStream(doc, this).use { s ->
                s.addRect(0.5f, 0.5f, w - 1, h - 1)
                s.stroke()
                if (checked) {
                    s.addRect(3f, 3f, w - 6, h - 6)
                    s.fill()
                }
            }
        }
        val normal = COSDictionary().apply {
            setItem(COSName.getPDFName(on), stream(true))
            setItem(COSName.Off, stream(false))
        }
        return PDAppearanceDictionary().apply { cosObject.setItem(COSName.N, normal) }
    }

    fun create(context: Context, name: String): Uri {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, name)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 0 Tf 0 g"
            val widgets = mutableListOf<PDAnnotation>()
            val fields = mutableListOf<PDField>()

            fun widget(s: Spec) = PDAnnotationWidget().apply {
                rectangle = rect(s)
                this.page = page
                isPrinted = true
                widgets += this
            }

            // Labels next to the fields, as on a real form.
            PDPageContentStream(doc, page).use { c ->
                for ((s, text) in listOf(vorname to "Vorname", email to "E-Mail", geburtsdatum to "Geburtsdatum", agree to "Agree", choiceA to "A", country to "Country")) {
                    c.beginText()
                    c.setFont(PDType1Font.HELVETICA, 11f)
                    c.newLineAtOffset(40f, HEIGHT - s.top - 14f)
                    c.showText(text)
                    c.endText()
                }
            }

            fun text(s: Spec, required: Boolean = false) = PDTextField(form).apply {
                partialName = s.name
                defaultAppearance = "/Helv 11 Tf 0 g"
                isRequired = required
                widgets = listOf(widget(s))
                fields += this
            }
            text(vorname)
            text(email, required = true)
            text(geburtsdatum)

            PDCheckBox(form).apply {
                partialName = agree.name
                val w = widget(agree).apply {
                    appearance = buttonAppearance(doc, agree.width, agree.height, "Yes")
                    setAppearanceState("Off")
                }
                widgets = listOf(w)
                fields += this
            }
            PDRadioButton(form).apply {
                partialName = "Choice"
                val a = widget(choiceA).apply {
                    appearance = buttonAppearance(doc, 16f, 16f, "A")
                    setAppearanceState("Off")
                }
                val b = widget(choiceB).apply {
                    appearance = buttonAppearance(doc, 16f, 16f, "B")
                    setAppearanceState("Off")
                }
                widgets = listOf(a, b)
                fields += this
            }
            PDComboBox(form).apply {
                partialName = country.name
                defaultAppearance = "/Helv 11 Tf 0 g"
                setOptions(listOf("France", "Germany", "Spain"))
                widgets = listOf(widget(country))
                fields += this
            }
            form.fields = fields
            page.annotations = widgets
            doc.save(file)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun values(file: File): Map<String, String> = PDDocument.load(file).use { doc ->
        doc.documentCatalog.acroForm?.fieldTree?.associate { it.fullyQualifiedName to it.valueAsString.orEmpty() }.orEmpty()
    }
}

@RunWith(AndroidJUnit4::class)
class FormFillTest {

    @Before
    fun setUp() = ReaderTestSupport.resetAppState()

    private fun open(name: String): ActivityScenario<MainActivity> {
        val scenario = openUri(TestForms.create(context, name))
        waitForPageLabel("1 / 1")
        waitFor { scenario.onActivity { assertTrue(it.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(0)?.pageRotation != null) } }
        return scenario
    }

    private fun file(name: String) = File(context.cacheDir, "shared/$name")

    private fun tapField(scenario: ActivityScenario<MainActivity>, s: TestForms.Spec) {
        var point = FloatArray(2)
        scenario.onActivity { activity ->
            val list = activity.findViewById<ZoomableRecyclerView>(R.id.pages)
            val view = list.pageViewFor(0)!!
            val local = view.toLocal(s.left + s.width / 2, s.top + s.height / 2)
            val onList = list.screenPointIn(view, local.x, local.y)
            val origin = IntArray(2).also(list::getLocationOnScreen)
            point = floatArrayOf(origin[0] + onList.x, origin[1] + onList.y)
        }
        onView(withId(R.id.pages)).perform(
            GeneralClickAction(Tap.SINGLE, CoordinatesProvider { point }, Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.BUTTON_PRIMARY)
        )
    }

    private fun activeFieldName(scenario: ActivityScenario<MainActivity>): String? {
        var name: String? = null
        scenario.onActivity { name = it.formController.activeField?.name }
        return name
    }

    private fun changed(scenario: ActivityScenario<MainActivity>): Map<String, String> {
        var values = emptyMap<String, String>()
        scenario.onActivity { values = it.annotationSession?.changedFormValues.orEmpty().toMap() }
        return values
    }

    private fun save() {
        ReaderTestSupport.toolbarAction(R.string.save_changes)
    }

    @Test
    fun fieldsAreFoundWithTheirKindsAndFormats() {
        open("fields.pdf").use { scenario ->
            var fields = emptyList<com.example.pdfreader.core.FormField>()
            waitFor {
                scenario.onActivity { fields = it.formController.fields }
                assertEquals(7, fields.size)
            }
            val byName = fields.groupBy { it.name }
            assertEquals(FieldKind.TEXT, byName.getValue("Vorname").single().kind)
            assertEquals(FieldFormat.EMAIL, byName.getValue("Email").single().format)
            assertTrue(byName.getValue("Email").single().required)
            assertEquals(FieldFormat.DATE, byName.getValue("Geburtsdatum").single().format)
            assertEquals(FieldKind.CHECKBOX, byName.getValue("Agree").single().kind)
            assertEquals(listOf("A", "B"), byName.getValue("Choice").map { it.onValue })
            assertEquals(listOf("France", "Germany", "Spain"), byName.getValue("Country").single().options)
        }
    }

    @Test
    fun typingWithNextMovesThroughTheFormAndSavesIntoTheFile() {
        open("typing.pdf").use { scenario ->
            tapField(scenario, TestForms.vorname)
            waitFor { onView(withId(R.id.formBar)).check(matches(isDisplayed())) }
            assertEquals("Vorname", activeFieldName(scenario))
            onView(withId(R.id.formInput)).perform(replaceText("Erika"), pressImeActionButton())
            waitFor { assertEquals("Email", activeFieldName(scenario)) }
            // A bad email is pointed out, and Next stays on the field once so it can be fixed.
            onView(withId(R.id.formInput)).perform(replaceText("erika@"), pressImeActionButton())
            waitFor { onView(withId(R.id.formError)).check(matches(withText(R.string.field_not_email))) }
            assertEquals("Email", activeFieldName(scenario))
            onView(withId(R.id.formInput)).perform(replaceText("erika@example.de"))
            onView(withId(R.id.formDone)).perform(click())
            waitFor { assertEquals(mapOf("Vorname" to "Erika", "Email" to "erika@example.de"), changed(scenario)) }

            save()
            waitFor {
                val values = TestForms.values(file("typing.pdf"))
                assertEquals("Erika", values["Vorname"])
                assertEquals("erika@example.de", values["Email"])
            }
            // After saving, the file itself shows the values and nothing is pending.
            waitFor { assertTrue(changed(scenario).isEmpty()) }
        }
    }

    @Test
    fun checkboxRadioAndDropdown() {
        open("buttons.pdf").use { scenario ->
            tapField(scenario, TestForms.agree)
            waitFor { assertEquals("Yes", changed(scenario)["Agree"]) }
            tapField(scenario, TestForms.choiceB)
            waitFor { assertEquals("B", changed(scenario)["Choice"]) }
            tapField(scenario, TestForms.country)
            onView(withText("Germany")).inRoot(isDialog()).perform(click())
            waitFor { assertEquals("Germany", changed(scenario)["Country"]) }
            // The email is required; saving asks first.
            save()
            waitFor { onView(withText(R.string.form_problems_title)).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withText(R.string.save_anyway)).inRoot(isDialog()).perform(click())
            waitFor {
                val values = TestForms.values(file("buttons.pdf"))
                assertEquals("Yes", values["Agree"])
                assertEquals("B", values["Choice"])
                assertEquals("Germany", values["Country"])
            }
        }
    }

    @Test
    fun autofillFillsMatchingEmptyFields() {
        ProfileStore(context).profile = AutofillProfile(firstName = "Erika", lastName = "Mustermann", email = "erika@example.de")
        open("autofill.pdf").use { scenario ->
            ReaderTestSupport.menu(R.string.form_menu, R.string.form_autofill)
            waitFor { assertEquals(mapOf("Vorname" to "Erika", "Email" to "erika@example.de"), changed(scenario)) }
        }
    }

    @Test
    fun flattenedCopyKeepsTheValuesAsPageText() {
        val output = File(context.cacheDir, "form-flat.pdf").apply { delete() }
        androidx.test.espresso.intent.Intents.init()
        try {
            androidx.test.espresso.intent.Intents.intending(androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.content.Intent.ACTION_CREATE_DOCUMENT))
                .respondWith(android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, android.content.Intent().setData(Uri.fromFile(output))))
            open("flatten-form.pdf").use { scenario ->
                tapField(scenario, TestForms.email)
                onView(withId(R.id.formInput)).perform(replaceText("flat@example.de"))
                onView(withId(R.id.formDone)).perform(click())
                waitFor { assertEquals("flat@example.de", changed(scenario)["Email"]) }
                ReaderTestSupport.menu(R.string.form_menu, R.string.save_flattened_copy)
                waitFor {
                    assertTrue(output.length() > 0)
                    PDDocument.load(output).use { doc ->
                        assertTrue(doc.documentCatalog.acroForm?.fields.isNullOrEmpty())
                        assertTrue(PDFTextStripper().getText(doc).contains("flat@example.de"))
                    }
                }
                // The original stays fillable and unchanged.
                assertEquals("", TestForms.values(file("flatten-form.pdf"))["Email"])
            }
        } finally {
            androidx.test.espresso.intent.Intents.release()
        }
    }

    @Test
    fun unsavedFormEntriesAreOfferedAgainAfterReopening() {
        val uri = TestForms.create(context, "form-draft.pdf")
        openUri(uri).use { scenario ->
            waitForPageLabel("1 / 1")
            waitFor { scenario.onActivity { assertTrue(it.findViewById<ZoomableRecyclerView>(R.id.pages).pageViewFor(0)?.pageRotation != null) } }
            tapField(scenario, TestForms.vorname)
            onView(withId(R.id.formInput)).perform(replaceText("Draft"))
            onView(withId(R.id.formDone)).perform(click())
            waitFor { assertEquals("Draft", changed(scenario)["Vorname"]) }
        }
        Thread.sleep(1000)
        openUri(uri).use { scenario ->
            waitFor { onView(withText(R.string.restore_draft_title)).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withText(R.string.restore)).inRoot(isDialog()).perform(click())
            waitFor { assertEquals("Draft", changed(scenario)["Vorname"]) }
        }
        assertFalse(TestForms.values(file("form-draft.pdf"))["Vorname"] == "Draft")
    }
}
