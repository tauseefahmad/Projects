package com.example.pdfreader.core

import kotlin.math.abs

/**
 * Zoom is split in two parts:
 * - [layoutScale] (≤ 1) shrinks pages in the list layout, so zoomed-out views show more pages;
 * - [drawZoom] (≥ 1) magnifies the laid-out list at draw time for zooming in.
 * The visible size of a page is its base size × layoutScale × drawZoom.
 */
data class ZoomState(val layoutScale: Float = 1f, val drawZoom: Float = 1f) {
    val total: Float get() = layoutScale * drawZoom
}

object ZoomMath {

    /** Preset zoom levels, in percent of actual size. */
    val PRESETS = listOf(50, 75, 100, 125, 150, 200, 300, 400)

    const val MIN_LAYOUT_SCALE = 0.1f

    /**
     * The zoom that shows an item [targetWidth] wide, given it is [baseWidth] wide at 1x.
     * Shrinking uses the layout; enlarging uses draw zoom so the list keeps fewer, bigger rows.
     */
    fun forDisplayWidth(targetWidth: Float, baseWidth: Float, maxDrawZoom: Float): ZoomState {
        if (baseWidth <= 0f) return ZoomState()
        val factor = targetWidth / baseWidth
        return if (factor <= 1f) {
            ZoomState(layoutScale = factor.coerceAtLeast(MIN_LAYOUT_SCALE))
        } else {
            ZoomState(drawZoom = factor.coerceAtMost(maxDrawZoom))
        }
    }

    /**
     * Moves zoom into the layout where possible: after pinching out below 1x the shrink becomes
     * a smaller layout; after pinching in on a shrunk layout, the layout grows back first.
     */
    fun normalize(state: ZoomState, maxDrawZoom: Float): ZoomState {
        val total = state.total
        return if (total <= 1f) {
            ZoomState(layoutScale = total.coerceAtLeast(MIN_LAYOUT_SCALE))
        } else {
            ZoomState(drawZoom = total.coerceAtMost(maxDrawZoom))
        }
    }

    /** Zoom in percent of actual size, where [actualWidth] is the page's 100% width on screen. */
    fun percent(state: ZoomState, baseWidth: Float, actualWidth: Float): Int =
        if (actualWidth <= 0f) 100 else Math.round(state.total * baseWidth / actualWidth * 100)

    /** The zoom for a percentage of actual size. */
    fun forPercent(percent: Int, baseWidth: Float, actualWidth: Float, maxDrawZoom: Float): ZoomState =
        forDisplayWidth(actualWidth * percent / 100f, baseWidth, maxDrawZoom)

    /** The next preset above (direction > 0) or below the current percentage, or null at the end. */
    fun nextPreset(currentPercent: Int, direction: Int): Int? = if (direction > 0) {
        PRESETS.firstOrNull { it > currentPercent && abs(it - currentPercent) > 1 }
    } else {
        PRESETS.lastOrNull { it < currentPercent && abs(it - currentPercent) > 1 }
    }

    /**
     * Fit-page width for an item [baseWidth]×[baseHeight] in a viewport [viewportHeight] tall:
     * shrinks tall items so they fit vertically, never enlarges.
     */
    fun fitPageWidth(baseWidth: Float, baseHeight: Float, viewportHeight: Float): Float =
        if (baseHeight <= viewportHeight || baseHeight <= 0f) baseWidth else baseWidth * viewportHeight / baseHeight
}
