package com.example.pdfreader

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import com.example.pdfreader.core.ZoomMath
import com.example.pdfreader.core.ZoomState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class FitMode { FIT_WIDTH, FIT_PAGE }

/**
 * Zoom levels for the reader: fit width / fit page / actual size / presets, pinch results,
 * keyboard zoom, the "125%" chip, and sharp re-rendering of the visible area when zoomed in.
 */
class ZoomController(
    private val list: ZoomableRecyclerView,
    private val zoomLabel: TextView,
    private val scope: CoroutineScope,
    /** Adapter position of the item the reader is on. */
    private val currentItem: () -> Int,
    /** Scrolls so [item] is at the top of the screen. */
    private val scrollToItem: (Int) -> Unit,
) {
    var document: PdfDocument? = null
    var adapter: PageAdapter? = null

    private var detailJob: Job? = null
    private val hideLabel = Runnable { zoomLabel.animate().alpha(0f).setDuration(250).withEndAction { zoomLabel.isVisible = false } }

    init {
        list.onZoomSettled = { onGestureZoomSettled() }
        list.viewportChangedListeners += {
            detailJob?.cancel()
            forEachPageView { it.setDetail(null) }
        }
        list.onViewportSettled = { scheduleDetail() }
    }

    val state: ZoomState
        get() = ZoomState(adapter?.layoutScale ?: 1f, list.zoom)

    fun currentPercent(): Int {
        val (base, actual) = pageWidths() ?: return 100
        return ZoomMath.percent(state, base, actual)
    }

    /** Applies a fit mode; [announce] shows the zoom chip (off when a document first opens). */
    fun apply(mode: FitMode, announce: Boolean = true) = when (mode) {
        FitMode.FIT_WIDTH -> fitWidth(announce)
        FitMode.FIT_PAGE -> fitPage(announce)
    }

    fun fitWidth(announce: Boolean = true) {
        val adapter = adapter ?: return
        val base = itemBaseWidth() ?: return
        apply(ZoomMath.forDisplayWidth(adapter.availableWidth().toFloat(), base, ZoomableRecyclerView.MAX_ZOOM), alignStart = true, announce)
    }

    fun fitPage(announce: Boolean = true) {
        val adapter = adapter ?: return
        val boxes = adapter.baseBoxes(currentItem().coerceIn(0, adapter.itemCount - 1))
        val width = boxes.sumOf { it.width }.toFloat()
        val height = boxes.maxOf { it.height }.toFloat()
        val target = ZoomMath.fitPageWidth(width, height, adapter.availableHeight().toFloat())
        apply(ZoomMath.forDisplayWidth(target, width, ZoomableRecyclerView.MAX_ZOOM), alignStart = true, announce)
    }

    fun setPercent(percent: Int) {
        val (base, actual) = pageWidths() ?: return
        apply(ZoomMath.forPercent(percent, base, actual, ZoomableRecyclerView.MAX_ZOOM), alignStart = false)
    }

    /** Steps to the next preset in [direction] (+1 in, -1 out). */
    fun step(direction: Int) {
        val next = ZoomMath.nextPreset(currentPercent(), direction) ?: return
        setPercent(next)
    }

    /** Resets to 1x without a re-layout; used when a new layout is built. */
    fun reset() {
        detailJob?.cancel()
        list.resetZoom()
    }

    private fun apply(target: ZoomState, alignStart: Boolean, announce: Boolean = true) {
        val adapter = adapter ?: return
        val item = currentItem()
        if (adapter.layoutScale != target.layoutScale) {
            adapter.layoutScale = target.layoutScale
            scrollToItem(item)
        }
        list.setDrawZoom(target.drawZoom, alignStart)
        afterZoomChange(announce)
    }

    /** After a pinch or double-tap: move any zoom-out into the layout, and vice versa. */
    private fun onGestureZoomSettled() {
        val adapter = adapter ?: return
        val normalized = ZoomMath.normalize(state, ZoomableRecyclerView.MAX_ZOOM)
        if (normalized.layoutScale != adapter.layoutScale) {
            val item = currentItem()
            adapter.layoutScale = normalized.layoutScale
            scrollToItem(item)
            list.setDrawZoom(normalized.drawZoom, alignStart = false)
        }
        afterZoomChange()
    }

    private fun afterZoomChange(announce: Boolean = true) {
        // Re-render page bitmaps in 0.5 steps (max 2x) so text stays sharp while panning.
        adapter?.renderScale = ((list.zoom.coerceAtMost(2f) * 2).roundToInt() / 2f).coerceAtLeast(1f)
        if (announce) showLabel()
        scheduleDetail()
    }

    private fun showLabel() {
        zoomLabel.text = zoomLabel.context.getString(R.string.zoom_percent, currentPercent())
        zoomLabel.removeCallbacks(hideLabel)
        zoomLabel.animate().cancel()
        zoomLabel.alpha = 1f
        zoomLabel.isVisible = true
        zoomLabel.postDelayed(hideLabel, 1200)
    }

    /** The current item's width at layout scale 1. */
    private fun itemBaseWidth(): Float? {
        val adapter = adapter ?: return null
        if (adapter.itemCount == 0 || adapter.availableWidth() == 0) return null
        return adapter.baseBoxes(currentItem().coerceIn(0, adapter.itemCount - 1)).sumOf { it.width }.toFloat()
    }

    /** The current page's width at layout scale 1, and its actual-size (100%) width, in pixels. */
    private fun pageWidths(): Pair<Float, Float>? {
        val adapter = adapter ?: return null
        val document = document ?: return null
        if (adapter.itemCount == 0 || adapter.availableWidth() == 0) return null
        val item = currentItem().coerceIn(0, adapter.itemCount - 1)
        val page = adapter.spreads.items[item].first
        val base = adapter.baseBoxes(item).first().width.toFloat()
        // 100% shows one PDF point (1/72 inch) as one density-independent pixel.
        val actual = document.pageSizes[page].width * list.resources.displayMetrics.density
        return base to actual
    }

    // --- Sharp detail when zoomed in -------------------------------------------------------

    /** Re-renders the sharp detail, e.g. after dark pages was toggled. */
    fun refreshDetail() = scheduleDetail()

    private fun forEachPageView(action: (PageView) -> Unit) {
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until row.childCount) (row.getChildAt(j) as? PageView)?.let(action)
        }
    }

    private fun scheduleDetail() {
        detailJob?.cancel()
        if (list.zoom < DETAIL_MIN_ZOOM) {
            forEachPageView { it.setDetail(null) }
            return
        }
        detailJob = scope.launch {
            delay(DETAIL_DELAY_MS)
            try {
                renderDetail()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Document closed mid-render; the regular bitmaps stay on screen.
            }
        }
    }

    /** Renders the visible part of each visible page at screen resolution onto its PageView. */
    private suspend fun renderDetail() {
        val document = document ?: return
        val zoom = list.zoom
        val visible = list.visibleContentRect()
        val maxPixels = list.width.toLong() * list.height * 2
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until row.childCount) {
                val image = row.getChildAt(j) as? PageView ?: continue
                val page = image.page
                if (page < 0 || image.visibility != View.VISIBLE || image.width == 0) continue
                val pageRect = RectF(
                    (row.left + image.left).toFloat(), (row.top + image.top).toFloat(),
                    (row.left + image.right).toFloat(), (row.top + image.bottom).toFloat(),
                )
                val region = RectF(pageRect)
                if (!region.intersect(visible)) continue
                val width = (region.width() * zoom).roundToInt()
                val height = (region.height() * zoom).roundToInt()
                if (width <= 0 || height <= 0 || width.toLong() * height > maxPixels) continue
                val bitmap = document.renderRegion(
                    page, width, height,
                    pageWidthPx = pageRect.width() * zoom,
                    offsetX = (region.left - pageRect.left) * zoom,
                    offsetY = (region.top - pageRect.top) * zoom,
                )
                if (image.page == page) {
                    // Store the region relative to the page view itself.
                    image.setDetail(bitmap, RectF(region).apply { offset(-pageRect.left, -pageRect.top) })
                }
            }
        }
    }

    private companion object {
        const val DETAIL_MIN_ZOOM = 1.2f
        const val DETAIL_DELAY_MS = 120L
    }
}
