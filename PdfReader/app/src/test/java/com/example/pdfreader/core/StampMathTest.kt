package com.example.pdfreader.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class StampMathTest {

    @Test
    fun fillsPageNumberTemplates() {
        assertEquals("Page 3 of 12", StampMath.fill("Page {n} of {total}", 2, 12))
        assertEquals("- 12 -", StampMath.fill("- {n} -", 1, 5, startNumber = 11))
        assertEquals("a.pdf · 2026-10-15", StampMath.fill("{file} · {date}", 0, 1, date = "2026-10-15", file = "a.pdf"))
    }

    @Test
    fun placesStampsAtCornersAndCentres() {
        assertEquals(20f to 20f, StampMath.place(StampPosition.BOTTOM_LEFT, 600f, 800f, 100f, 10f, 20f))
        assertEquals(250f to 20f, StampMath.place(StampPosition.BOTTOM_CENTER, 600f, 800f, 100f, 10f, 20f))
        assertEquals(480f to 770f, StampMath.place(StampPosition.TOP_RIGHT, 600f, 800f, 100f, 10f, 20f))
    }

    @Test
    fun rotatedTextIsCentred() {
        val angle = 45f
        val (x, y) = StampMath.centeredRotated(600f, 800f, 200f, 40f, angle)
        // The text box centre (100, 20) in text space must map to the page centre.
        val a = Math.toRadians(angle.toDouble())
        val cx = x + cos(a) * 100 - sin(a) * 20
        val cy = y + sin(a) * 100 + cos(a) * 20
        assertEquals(300.0, cx, 0.01)
        assertEquals(400.0, cy, 0.01)
    }

    @Test
    fun unrotatedTextCentring() {
        assertEquals(200f to 380f, StampMath.centeredRotated(600f, 800f, 200f, 40f, 0f))
    }

    @Test
    fun cropTrimsMarginsButNeverInverts() {
        assertArrayEquals(floatArrayOf(10f, 20f, 590f, 780f), StampMath.crop(0f, 0f, 600f, 800f, 10f, 20f, 10f, 20f), 0.001f)
        val tiny = StampMath.crop(0f, 0f, 100f, 100f, 80f, 80f, 80f, 80f)
        assertEquals(1f, tiny[2] - tiny[0], 0.001f)
    }

    @Test
    fun fitCentresOnTarget() {
        // Letter onto A4: limited by width.
        val (scale, dx, dy) = StampMath.fit(612f, 792f, 595f, 842f)
        assertEquals(595f / 612f, scale, 0.0001f)
        assertEquals(0f, dx, 0.01f)
        assertEquals((842f - 792f * scale) / 2, dy, 0.01f)
    }

    @Test
    fun millimetres() {
        assertEquals(28.35f, StampMath.mmToPoints(10f), 0.01f)
    }
}
