package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormBuilderTest {

    @Test
    fun uniqueNamesAvoidExistingFields() {
        assertEquals("Name", FormBuilder.uniqueName("Name", listOf("Email")))
        assertEquals("Name_2", FormBuilder.uniqueName(" Name ", listOf("Name")))
        assertEquals("Name_3", FormBuilder.uniqueName("Name", listOf("Name", "Name_2")))
        // Dots separate parent and child names in AcroForms.
        assertEquals("a_b", FormBuilder.uniqueName("a.b", emptyList()))
        assertEquals("Field", FormBuilder.uniqueName("  ", emptyList()))
    }

    @Test
    fun optionsAreSplitAndCleaned() {
        assertEquals(listOf("Red", "Green", "Blue"), FormBuilder.parseOptions("Red, Green,\nBlue, , Red"))
    }

    @Test
    fun marksAreCentredOnTheTap() {
        val tick = FormMarks.tick(Pt(100f, 100f), 10f).flatten()
        val box = Box.around(tick)
        assertTrue(box.left >= 95f && box.right <= 105f && box.top >= 95f && box.bottom <= 105f)
        val cross = FormMarks.cross(Pt(50f, 60f), 8f)
        assertEquals(2, cross.size)
        assertEquals(Box(46f, 56f, 54f, 64f), Box.around(cross.flatten()))
    }

    @Test
    fun builtFieldsBehaveLikeAnnotations() {
        val field = Annotation.Field("f", 0, 0, "", 0, box = Box(10f, 10f, 110f, 30f), kind = FieldKind.CHOICE, name = "Colour", options = listOf("Red", "Blue"))
        assertEquals(Box(15f, 20f, 115f, 40f), field.moved(5f, 10f).bounds)
        assertEquals(Box(0f, 0f, 50f, 10f), AnnotationTransform.resized(field, Box(0f, 0f, 50f, 10f)).bounds)
        assertEquals("Form field", field.typeName())
        val json = AnnotationJson.encodeDraft(EditDraft(listOf(field)))
        assertEquals(listOf(field), AnnotationJson.decodeDraft(json)?.annotations)
    }

    @Test
    fun copiesGoToOtherPagesAndStayOnThem() {
        val image = Annotation.Image("i", 0, 0, "", 0, box = Box(400f, 700f, 550f, 750f), imagePath = "sig.png")
        val copy = AnnotationTransform.copyTo(image, 3, "j")
        assertEquals(3, copy.page)
        assertEquals("j", copy.id)
        assertEquals(image.box, copy.bounds)
        // A smaller page: moved back inside.
        val small = AnnotationTransform.copyTo(image, 1, "k", pageWidth = 500f, pageHeight = 720f)
        assertEquals(Box(350f, 670f, 500f, 720f), small.bounds)
        val history = AnnotationHistory(listOf(image))
        history.addAll(listOf(copy, small))
        assertEquals(3, history.items.size)
        history.undo()
        assertEquals(listOf(image), history.items)
    }
}
