package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoomMathTest {

    private val max = 8f

    @Test
    fun smallerTargetShrinksLayout() {
        assertEquals(ZoomState(layoutScale = 0.5f), ZoomMath.forDisplayWidth(500f, 1000f, max))
    }

    @Test
    fun largerTargetUsesDrawZoom() {
        assertEquals(ZoomState(drawZoom = 2f), ZoomMath.forDisplayWidth(2000f, 1000f, max))
    }

    @Test
    fun drawZoomIsCapped() {
        assertEquals(ZoomState(drawZoom = 8f), ZoomMath.forDisplayWidth(50_000f, 1000f, max))
    }

    @Test
    fun layoutScaleHasAFloor() {
        assertEquals(ZoomMath.MIN_LAYOUT_SCALE, ZoomMath.forDisplayWidth(1f, 1000f, max).layoutScale)
    }

    @Test
    fun normalizeMovesPinchOutIntoLayout() {
        assertEquals(ZoomState(layoutScale = 0.6f), ZoomMath.normalize(ZoomState(1f, 0.6f), max))
    }

    @Test
    fun normalizeGrowsShrunkLayoutBeforeDrawZoom() {
        assertEquals(ZoomState(layoutScale = 0.75f), ZoomMath.normalize(ZoomState(0.5f, 1.5f), max))
        assertEquals(ZoomState(drawZoom = 1.5f), ZoomMath.normalize(ZoomState(0.5f, 3f), max))
    }

    @Test
    fun percentIsRelativeToActualSize() {
        // A page shown 1000px wide at 1x whose actual (100%) size is 800px.
        assertEquals(125, ZoomMath.percent(ZoomState(), 1000f, 800f))
        assertEquals(250, ZoomMath.percent(ZoomState(drawZoom = 2f), 1000f, 800f))
        assertEquals(63, ZoomMath.percent(ZoomState(layoutScale = 0.5f), 1000f, 800f))
    }

    @Test
    fun forPercentRoundTrips() {
        for (preset in ZoomMath.PRESETS) {
            val state = ZoomMath.forPercent(preset, 1000f, 800f, max)
            assertEquals(preset, ZoomMath.percent(state, 1000f, 800f))
        }
    }

    @Test
    fun nextPresetSkipsCurrentLevel() {
        assertEquals(125, ZoomMath.nextPreset(100, 1))
        assertEquals(75, ZoomMath.nextPreset(100, -1))
        assertEquals(150, ZoomMath.nextPreset(137, 1))
        assertEquals(125, ZoomMath.nextPreset(137, -1))
        assertEquals(125, ZoomMath.nextPreset(101, 1))
        assertNull(ZoomMath.nextPreset(400, 1))
        assertNull(ZoomMath.nextPreset(50, -1))
    }

    @Test
    fun fitPageShrinksOnlyTallItems() {
        assertEquals(500f, ZoomMath.fitPageWidth(1000f, 2000f, 1000f), 0.01f)
        assertEquals(1000f, ZoomMath.fitPageWidth(1000f, 800f, 1000f), 0.01f)
    }
}
