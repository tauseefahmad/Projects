package com.example.pdfreader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
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

    /** In paged mode, used to settle on a page after a zoomed drag. */
    var snapHelper: SnapHelper? = null

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
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (zoom > 1.05f) 1f else DOUBLE_TAP_ZOOM
            animateZoom(target, e.x, e.y)
            return true
        }
    })

    /** Maps a point on screen to the un-zoomed list coordinates. */
    fun toContentY(screenY: Float) = (screenY - transY) / zoom
    fun toContentX(screenX: Float) = (screenX - transX) / zoom

    fun resetZoom() {
        animator?.cancel()
        zoom = 1f
        transX = 0f
        transY = 0f
        invalidate()
        onZoomSettled?.invoke(zoom)
    }

    private fun zoomTo(newZoom: Float, focusX: Float, focusY: Float) {
        val clamped = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = clamped / zoom
        // Keep the content under the focus point stationary.
        transX = focusX - (focusX - transX) * factor
        transY = focusY - (focusY - transY) * factor
        zoom = clamped
        clampTranslation()
        invalidate()
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
        transX = transX.coerceIn(width - width * zoom, 0f)
        transY = transY.coerceIn(height - height * zoom, 0f)
    }

    // Children are plain images, so this view handles every touch itself.
    override fun onInterceptTouchEvent(e: MotionEvent) = true

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scaleDetector.isInProgress) panBy(e.x - lastX, e.y - lastY)
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(remaining)
                lastY = e.getY(remaining)
            }
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
        invalidate()
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
        if (!flung) snapToNearest()
    }

    private fun snapToNearest() {
        val helper = snapHelper ?: return
        val manager = layoutManager ?: return
        val view = helper.findSnapView(manager) ?: return
        val distance = helper.calculateDistanceToFinalSnap(manager, view) ?: return
        if (distance[0] != 0 || distance[1] != 0) smoothScrollBy(distance[0], distance[1])
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
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
