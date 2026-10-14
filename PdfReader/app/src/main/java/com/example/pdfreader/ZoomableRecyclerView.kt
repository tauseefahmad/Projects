package com.example.pdfreader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView

/**
 * A vertical RecyclerView that supports pinch-to-zoom, double-tap zoom and panning.
 *
 * Zoom is applied at draw time (the list itself always lays out at 1x). Vertical scrolling
 * still goes through RecyclerView so flinging works; touch coordinates are divided by the
 * zoom so a finger drag moves the content 1:1 on screen. Horizontal panning, and vertical
 * panning that is left over at the ends of the list, adjust [transX]/[transY].
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

    private var pinching = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var animator: ValueAnimator? = null

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
            doOnEndCompat { onZoomSettled?.invoke(zoom) }
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
                pinching = false
                lastPanX = e.x
                lastPanY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Stop the list from scrolling while two fingers are down.
                val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                super.onTouchEvent(cancel)
                cancel.recycle()
                pinching = true
                return true
            }
        }

        if (pinching) {
            // After a pinch, remaining single-finger movement pans until the finger lifts.
            if (e.actionMasked == MotionEvent.ACTION_MOVE && e.pointerCount == 1 && !scaleDetector.isInProgress) {
                panBy(e.x - lastPanX, e.y - lastPanY)
            }
            if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                val remaining = if (e.actionIndex == 0) 1 else 0
                lastPanX = e.getX(remaining)
                lastPanY = e.getY(remaining)
            } else {
                lastPanX = e.x
                lastPanY = e.y
            }
            return true
        }

        if (zoom > 1f && e.actionMasked == MotionEvent.ACTION_MOVE) {
            transX += e.x - lastPanX
            // At either end of the list, vertical drags pan the zoomed viewport instead.
            val dy = e.y - lastPanY
            if ((dy < 0 && !canScrollVertically(1)) || (dy > 0 && !canScrollVertically(-1))) transY += dy
            clampTranslation()
            invalidate()
        }
        lastPanX = e.x
        lastPanY = e.y

        // Let RecyclerView scroll and fling, with movement scaled to match the zoom.
        val scaled = MotionEvent.obtain(e)
        scaled.transform(Matrix().apply { setScale(1f / zoom, 1f / zoom) })
        val handled = super.onTouchEvent(scaled)
        scaled.recycle()
        return handled
    }

    /** Pans the zoomed viewport; vertical movement past its edges scrolls the list. */
    private fun panBy(dx: Float, dy: Float) {
        transX += dx
        val before = transY
        transY += dy
        clampTranslation()
        val leftover = dy - (transY - before)
        if (leftover != 0f) scrollBy(0, (-leftover / zoom).toInt())
        invalidate()
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

    private fun ValueAnimator.doOnEndCompat(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
