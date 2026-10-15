package com.example.pdfreader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SnapHelper
import com.example.pdfreader.core.PointerKind
import kotlin.math.abs

/**
 * A RecyclerView (vertical or horizontal) with pinch-to-zoom, double-tap zoom and panning.
 *
 * Zoom is applied at draw time; the list itself always lays out at 1x. At 1x RecyclerView
 * handles touches normally. While zoomed, this view pans the zoomed viewport itself and hands
 * any movement beyond the viewport's edge to the list as scrolling (with a fling on release),
 * so you can pan around a page and then carry on to the next one.
 */
class ZoomableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {

    var zoom = 1f
        private set
    private var transX = 0f
    private var transY = 0f

    /** Called once a pinch or double-tap zoom has settled, so pages can re-render sharper. */
    var onZoomSettled: ((Float) -> Unit)? = null

    /** Called when a finger touches the list, i.e. the user starts moving it by hand. */
    var onTouchStart: (() -> Unit)? = null

    /** Long-press at a screen point (e.g. to start selecting text). */
    var onLongPress: ((x: Float, y: Float) -> Unit)? = null

    /** A confirmed single tap (not part of a double-tap) at a screen point. */
    var onSingleTap: ((x: Float, y: Float) -> Unit)? = null

    /** Called whenever zoom, pan or scroll position changes (e.g. detail renders become stale). */
    val viewportChangedListeners = mutableListOf<() -> Unit>()

    /** Called when the viewport comes to rest after a gesture, fling or scroll. */
    var onViewportSettled: (() -> Unit)? = null

    /** In paged mode, used to settle on a page after a zoomed drag. */
    var snapHelper: SnapHelper? = null

    /**
     * Receives touches while annotating. Touches it accepts draw instead of scrolling; a
     * second finger turns a finger stroke into a pinch, while a stylus ignores other fingers
     * (a resting palm).
     */
    interface PenHandler {
        /** A touch began at a screen point; return true to take it. */
        fun onPenDown(x: Float, y: Float, pressure: Float, kind: PointerKind): Boolean
        fun onPenMove(x: Float, y: Float, pressure: Float)
        fun onPenUp(x: Float, y: Float)
        /** The touch became something else (a pinch) or was cancelled. */
        fun onPenCancel()
    }

    var penHandler: PenHandler? = null
        set(value) {
            if (field != null && penActive) field?.onPenCancel()
            penActive = false
            field = value
        }

    private var penActive = false
    private var penPointerId = -1
    private var penKind = PointerKind.FINGER
    /** The rest of this gesture skips the tap/long-press detector (it missed the down event). */
    private var skipGestureDetector = false

    private var pinching = false
    private var manualGesture = false
    private var lastX = 0f
    private var lastY = 0f
    private var scrollRemainder = 0f
    private var velocityTracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    private val viewConfig = ViewConfiguration.get(context)

    private val horizontal: Boolean
        get() = (layoutManager as? LinearLayoutManager)?.orientation == LinearLayoutManager.HORIZONTAL

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            animator?.cancel()
            pinching = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            onZoomSettled?.invoke(zoom)
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (!pinching) onLongPress?.invoke(e.x, e.y)
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (zoom > 1.05f) 1f else DOUBLE_TAP_ZOOM
            animateZoom(target, e.x, e.y)
            return true
        }
    })

    /** Maps a point on screen to the un-zoomed list coordinates. */
    fun toContentY(screenY: Float) = (screenY - transY) / zoom
    fun toContentX(screenX: Float) = (screenX - transX) / zoom

    /** Maps un-zoomed list coordinates to a point on screen. */
    fun toScreenX(contentX: Float) = contentX * zoom + transX
    fun toScreenY(contentY: Float) = contentY * zoom + transY

    /** The page view under a screen point and the point in that view's own coordinates. */
    fun pageViewAt(screenX: Float, screenY: Float): Pair<PageView, android.graphics.PointF>? {
        val x = toContentX(screenX)
        val y = toContentY(screenY)
        for (i in 0 until childCount) {
            val row = getChildAt(i) as? android.view.ViewGroup ?: continue
            for (j in 0 until row.childCount) {
                val view = row.getChildAt(j) as? PageView ?: continue
                if (view.page < 0 || view.visibility != VISIBLE) continue
                val left = row.left + view.left
                val top = row.top + view.top
                if (x >= left && x < left + view.width && y >= top && y < top + view.height) {
                    return view to android.graphics.PointF(x - left, y - top)
                }
            }
        }
        return null
    }

    /** The page view currently showing [page], if it is laid out. */
    fun pageViewFor(page: Int): PageView? {
        for (i in 0 until childCount) {
            val row = getChildAt(i) as? android.view.ViewGroup ?: continue
            for (j in 0 until row.childCount) {
                val view = row.getChildAt(j) as? PageView ?: continue
                if (view.page == page && view.visibility == VISIBLE) return view
            }
        }
        return null
    }

    /** Screen position of a point given in [view]'s own coordinates. */
    fun screenPointIn(view: PageView, localX: Float, localY: Float): android.graphics.PointF {
        val row = view.parent as android.view.View
        return android.graphics.PointF(toScreenX(row.left + view.left + localX), toScreenY(row.top + view.top + localY))
    }

    /** A screen point in [view]'s own coordinates (it may lie outside the view). */
    fun localPointIn(view: PageView, screenX: Float, screenY: Float): android.graphics.PointF {
        val row = view.parent as android.view.View
        return android.graphics.PointF(toContentX(screenX) - row.left - view.left, toContentY(screenY) - row.top - view.top)
    }

    /** Calls [action] for every page view on screen. */
    fun forEachPageView(action: (PageView) -> Unit) {
        for (i in 0 until childCount) {
            val row = getChildAt(i) as? android.view.ViewGroup ?: continue
            for (j in 0 until row.childCount) (row.getChildAt(j) as? PageView)?.let(action)
        }
    }

    /** The part of the list (in un-zoomed list coordinates) currently on screen. */
    fun visibleContentRect() = RectF(toContentX(0f), toContentY(0f), toContentX(width.toFloat()), toContentY(height.toFloat()))

    fun resetZoom() {
        animator?.cancel()
        zoom = 1f
        transX = 0f
        transY = 0f
        viewportChanged()
    }

    /**
     * Sets the draw zoom without user interaction. With [alignStart] the viewport moves to the
     * top-left (used for "fit" modes); otherwise it zooms around the center of the screen.
     */
    fun setDrawZoom(newZoom: Float, alignStart: Boolean) {
        animator?.cancel()
        if (alignStart) {
            zoom = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            transX = 0f
            transY = 0f
            clampTranslation()
            viewportChanged()
        } else {
            zoomTo(newZoom, width / 2f, height / 2f)
        }
    }

    private fun viewportChanged() {
        invalidate()
        viewportChangedListeners.forEach { it() }
    }

    private fun zoomTo(newZoom: Float, focusX: Float, focusY: Float) {
        val clamped = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = clamped / zoom
        // Keep the content under the focus point stationary.
        transX = focusX - (focusX - transX) * factor
        transY = focusY - (focusY - transY) * factor
        zoom = clamped
        clampTranslation()
        viewportChanged()
    }

    private fun animateZoom(target: Float, focusX: Float, focusY: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(zoom, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomTo(it.animatedValue as Float, focusX, focusY) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onZoomSettled?.invoke(zoom)
                }
            })
            start()
        }
    }

    private fun clampTranslation() {
        // Zoomed out below 1x (mid-pinch), the shrunk list stays centered.
        transX = if (zoom >= 1f) transX.coerceIn(width - width * zoom, 0f) else (width - width * zoom) / 2f
        transY = if (zoom >= 1f) transY.coerceIn(height - height * zoom, 0f) else (height - height * zoom) / 2f
    }

    // Children are plain images, so this view handles every touch itself.
    override fun onInterceptTouchEvent(e: MotionEvent) = true

    private fun pointerKind(e: MotionEvent, index: Int) = when (e.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.STYLUS_ERASER
        MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE
        else -> PointerKind.FINGER
    }

    /** Routes a touch to [penHandler]; returns true while the pen owns the gesture. */
    private fun handlePen(e: MotionEvent): Boolean {
        val pen = penHandler ?: return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            skipGestureDetector = false
            penActive = false
            val kind = pointerKind(e, 0)
            if (pen.onPenDown(e.x, e.y, e.pressure, kind)) {
                animator?.cancel()
                stopScroll()
                onTouchStart?.invoke()
                penActive = true
                penPointerId = e.getPointerId(0)
                penKind = kind
                return true
            }
            return false
        }
        if (!penActive) return false
        val index = e.findPointerIndex(penPointerId)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (index >= 0) {
                for (h in 0 until e.historySize) pen.onPenMove(e.getHistoricalX(index, h), e.getHistoricalY(index, h), e.getHistoricalPressure(index, h))
                pen.onPenMove(e.getX(index), e.getY(index), e.getPressure(index))
            }
            MotionEvent.ACTION_UP -> {
                penActive = false
                pen.onPenUp(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == penPointerId) {
                penActive = false
                pen.onPenUp(e.getX(e.actionIndex), e.getY(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                penActive = false
                pen.onPenCancel()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (penKind == PointerKind.FINGER) {
                // A second finger: the user wants to pinch or pan, not draw.
                penActive = false
                pen.onPenCancel()
                skipGestureDetector = true
                pinching = false
                manualGesture = true
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                setLastToFocus(e)
                scaleDetector.onTouchEvent(e)
                return true
            }
        }
        return true
    }

    /** The midpoint of the pointers on screen, optionally ignoring one that is lifting. */
    private fun focus(e: MotionEvent, excludeIndex: Int = -1): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == excludeIndex) continue
            x += e.getX(i)
            y += e.getY(i)
            n++
        }
        return if (n == 0) e.x to e.y else x / n to y / n
    }

    private fun setLastToFocus(e: MotionEvent, excludeIndex: Int = -1) {
        val (fx, fy) = focus(e, excludeIndex)
        lastX = fx
        lastY = fy
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (handlePen(e)) return true
        scaleDetector.onTouchEvent(e)
        if (!skipGestureDetector) gestureDetector.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                skipGestureDetector = false
                onTouchStart?.invoke()
                pinching = false
                manualGesture = zoom > 1f
                scrollRemainder = 0f
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                if (manualGesture) stopScroll()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!manualGesture) {
                // Two fingers: stop RecyclerView's own scrolling and take over.
                val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                super.onTouchEvent(cancel)
                cancel.recycle()
                manualGesture = true
            }
        }
        velocityTracker?.addMovement(e)

        if (!manualGesture) {
            lastX = e.x
            lastY = e.y
            return super.onTouchEvent(e)
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> setLastToFocus(e)
            MotionEvent.ACTION_MOVE -> {
                val (fx, fy) = focus(e)
                // One finger pans; two fingers pan with their midpoint while pinching.
                if (e.pointerCount > 1 || !scaleDetector.isInProgress) panBy(fx - lastX, fy - lastY)
                lastX = fx
                lastY = fy
            }
            MotionEvent.ACTION_POINTER_UP -> setLastToFocus(e, excludeIndex = e.actionIndex)
            MotionEvent.ACTION_UP -> {
                settle()
                velocityTracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    /** Pans the zoomed viewport; movement past its edges along the list's axis scrolls the list. */
    private fun panBy(dx: Float, dy: Float) {
        val beforeX = transX
        val beforeY = transY
        transX += dx
        transY += dy
        clampTranslation()
        val leftover = if (horizontal) dx - (transX - beforeX) else dy - (transY - beforeY)
        if (leftover != 0f) {
            scrollRemainder += -leftover / zoom
            val whole = scrollRemainder.toInt()
            if (whole != 0) {
                if (horizontal) scrollBy(whole, 0) else scrollBy(0, whole)
                scrollRemainder -= whole
            }
        }
        viewportChanged()
    }

    /** After a zoomed drag: fling the list if the viewport is at its edge, then snap pages. */
    private fun settle() {
        val tracker = velocityTracker ?: return
        tracker.computeCurrentVelocity(1000, viewConfig.scaledMaximumFlingVelocity.toFloat())
        val velocity = if (horizontal) tracker.xVelocity else tracker.yVelocity
        val trans = if (horizontal) transX else transY
        val size = if (horizontal) width else height
        // Finger moving toward the start (negative velocity) scrolls forward once the viewport is at its far edge.
        val atEdge = if (velocity < 0) trans <= size - size * zoom + 0.5f else trans >= -0.5f
        var flung = false
        if (atEdge && abs(velocity) > viewConfig.scaledMinimumFlingVelocity) {
            val listVelocity = (-velocity / zoom).toInt()
            flung = if (horizontal) fling(listVelocity, 0) else fling(0, listVelocity)
        }
        if (!flung && !snapToNearest()) onViewportSettled?.invoke()
    }

    /** Returns true if a snap scroll started (which settles on its own when it stops). */
    private fun snapToNearest(): Boolean {
        val helper = snapHelper ?: return false
        val manager = layoutManager ?: return false
        val view = helper.findSnapView(manager) ?: return false
        val distance = helper.calculateDistanceToFinalSnap(manager, view) ?: return false
        if (distance[0] == 0 && distance[1] == 0) return false
        smoothScrollBy(distance[0], distance[1])
        return true
    }

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        if (dx != 0 || dy != 0) viewportChanged()
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        if (state == SCROLL_STATE_IDLE) onViewportSettled?.invoke()
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(transX, transY)
        canvas.scale(zoom, zoom)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampTranslation()
    }

    companion object {
        /** Lowest zoom while pinching; on release it becomes a smaller layout instead. */
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 8f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}

