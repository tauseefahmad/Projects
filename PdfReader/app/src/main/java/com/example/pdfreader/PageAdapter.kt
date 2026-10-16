package com.example.pdfreader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RectF
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.example.pdfreader.core.PageBox
import com.example.pdfreader.core.PageFit
import com.example.pdfreader.core.PageRotation
import com.example.pdfreader.core.Spreads
import com.example.pdfreader.core.ViewMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Shows the document as list items of one page, or two pages side by side in spread mode.
 * Pages render lazily as items scroll into view; RecyclerView keeps only the visible items
 * (plus a small prefetch buffer) alive, so long documents don't hold every page in memory.
 */
class PageAdapter(
    private val document: PdfDocument,
    private val scope: CoroutineScope,
    val spreads: Spreads,
    private val viewMode: ViewMode,
) : RecyclerView.Adapter<PageAdapter.ItemHolder>() {

    /** Extra resolution multiplier while zoomed in, so text stays crisp. */
    var renderScale = 1f
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_RERENDER)
        }

    /** Clockwise view rotation in degrees (0, 90, 180, 270); the file itself is unchanged. */
    var rotation = 0
        set(value) {
            if (field == value) return
            field = value
            cache.evictAll()
            notifyDataSetChanged()
        }

    /** A page's size in points as displayed (width and height swap when turned a quarter). */
    fun displaySize(page: Int): PageBox {
        val size = document.pageSizes[page]
        val turn = PageRotation(size.width.toFloat(), size.height.toFloat(), rotation)
        return PageBox(turn.displayWidth.toInt(), turn.displayHeight.toInt())
    }

    /** Shrinks every page in the layout (≤ 1); used when zoomed out below fit-to-width. */
    var layoutScale = 1f
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    var darkPages = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_FILTER)
        }

    /** Tints pages like warm paper (sepia theme); inverting for dark pages takes precedence. */
    var sepiaPages = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_FILTER)
        }

    // Budget ~1/6 of the app heap for rendered pages.
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
    }

    class ItemHolder(val row: LinearLayout, val images: List<PageView>) : RecyclerView.ViewHolder(row) {
        val jobs = arrayOfNulls<Job>(images.size)
        val highlightJobs = arrayOfNulls<Job>(images.size)
    }

    /** Supplies search highlights for a page: (all matches, current match), in PDF points. */
    var highlightProvider: (suspend (page: Int) -> Pair<List<RectF>, List<RectF>>)? = null

    /** Annotations drawn over every page. */
    var annotationLayer: PageView.AnnotationLayer? = null
    var formLayer: PageView.FormLayer? = null

    /** Re-fetches highlights for the visible pages, e.g. after a new search. */
    fun refreshHighlights() = notifyItemRangeChanged(0, itemCount, PAYLOAD_HIGHLIGHTS)

    override fun getItemCount() = spreads.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemHolder {
        val density = parent.resources.displayMetrics.density
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val single = viewMode == ViewMode.SINGLE_PAGE
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (single) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val h = (ROW_PADDING_H_DP * density).toInt()
            val v = (ROW_PADDING_V_DP * density).toInt()
            setPadding(h, v, h, v)
        }
        val images = List(2) { index ->
            PageView(parent.context).apply {
                setBackgroundColor(Color.WHITE)
                elevation = 2 * density
                scaleType = ImageView.ScaleType.FIT_XY
                annotationLayer = this@PageAdapter.annotationLayer
                formLayer = this@PageAdapter.formLayer
                layoutParams = LinearLayout.LayoutParams(0, 0).apply {
                    if (index == 1) marginStart = (GAP_DP * density).toInt()
                }
                row.addView(this)
            }
        }
        return ItemHolder(row, images)
    }

    override fun onBindViewHolder(holder: ItemHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_FILTER || it == PAYLOAD_HIGHLIGHTS }) {
            if (PAYLOAD_FILTER in payloads) holder.images.forEach(::applyFilter)
            if (PAYLOAD_HIGHLIGHTS in payloads) {
                holder.images.forEachIndexed { slot, image -> if (image.page >= 0) bindHighlights(holder, slot, image.page) }
            }
        } else {
            onBindViewHolder(holder, position)
        }
    }

    /** Space above the pages within an item, in pixels. */
    fun rowPaddingTop(): Int = recyclerView?.let { (ROW_PADDING_V_DP * it.resources.displayMetrics.density).toInt() } ?: 0

    /** Width available to an item's pages, in pixels (0 before the list is laid out). */
    fun availableWidth(): Int {
        val parent = recyclerView ?: return 0
        val padding = 2 * (ROW_PADDING_H_DP * parent.resources.displayMetrics.density).toInt()
        return (parent.width - parent.paddingLeft - parent.paddingRight - padding).coerceAtLeast(0)
    }

    /** Height available to an item's pages, in pixels. */
    fun availableHeight(): Int {
        val parent = recyclerView ?: return 0
        val padding = 2 * (ROW_PADDING_V_DP * parent.resources.displayMetrics.density).toInt()
        return (parent.height - parent.paddingTop - parent.paddingBottom - padding).coerceAtLeast(0)
    }

    private fun gapPx(): Int = recyclerView?.let { (GAP_DP * it.resources.displayMetrics.density).toInt() } ?: 0

    /** Page sizes of item [position] at layout scale 1 (fit to width, or fit to screen when paged). */
    fun baseBoxes(position: Int): List<PageBox> = PageFit.fit(
        spreads.items[position].map { displaySize(it) },
        availableWidth(),
        if (viewMode == ViewMode.SINGLE_PAGE) availableHeight() else null,
        gapPx(),
    )

    override fun onBindViewHolder(holder: ItemHolder, position: Int) {
        if (recyclerView == null) return
        if (availableWidth() <= 0) return bindLater(holder, position)

        val pages = spreads.items[position].toList()
        val boxes = baseBoxes(position).map {
            PageBox((it.width * layoutScale).toInt().coerceAtLeast(1), (it.height * layoutScale).toInt().coerceAtLeast(1))
        }

        holder.images.forEachIndexed { slot, image ->
            holder.jobs[slot]?.cancel()
            holder.highlightJobs[slot]?.cancel()
            image.setDetail(null)
            if (slot >= pages.size) {
                image.visibility = View.GONE
                image.setImageDrawable(null)
                image.tag = null
                image.page = -1
                image.clearOverlays()
                return@forEachIndexed
            }
            val page = pages[slot]
            val box = boxes[slot]
            image.visibility = View.VISIBLE
            image.layoutParams = image.layoutParams.apply {
                width = box.width
                height = box.height
            }
            image.contentDescription = "Page ${page + 1}"
            if (image.page != page) image.clearOverlays()
            image.page = page
            val size = document.pageSizes[page]
            image.pageRotation = PageRotation(size.width.toFloat(), size.height.toFloat(), rotation)
            applyFilter(image)
            bindPage(holder, slot, page, box.width)
            bindHighlights(holder, slot, page)
        }
    }

    private fun bindPage(holder: ItemHolder, slot: Int, page: Int, displayWidth: Int) {
        val image = holder.images[slot]
        val renderWidth = (displayWidth * renderScale).toInt().coerceIn(1, MAX_RENDER_WIDTH)
        val key = "$page@$renderWidth"
        val cached = cache.get(key)
        if (cached != null) {
            image.setImageBitmap(cached)
            image.tag = page
            return
        }

        // Keep showing an older (blurrier) render of the same page while the sharp one loads.
        if (image.tag != page) image.setImageDrawable(null)
        image.tag = page
        holder.jobs[slot] = scope.launch {
            try {
                val bitmap = document.renderPage(page, renderWidth, rotation)
                cache.put(key, bitmap)
                if (image.tag == page) image.setImageBitmap(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Document closed or page failed to render; leave the blank page.
            }
        }
    }

    private fun bindHighlights(holder: ItemHolder, slot: Int, page: Int) {
        val image = holder.images[slot]
        holder.highlightJobs[slot]?.cancel()
        val provider = highlightProvider
        if (provider == null) {
            image.highlights = emptyList()
            image.currentHighlights = emptyList()
            return
        }
        holder.highlightJobs[slot] = scope.launch {
            val (all, current) = try {
                provider(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<RectF>() to emptyList()
            }
            if (image.page == page) {
                image.highlights = all
                image.currentHighlights = current
            }
        }
    }

    /** The first bind can happen before the list has a size; retry after layout. */
    private var waitingForLayout = false

    private fun bindLater(@Suppress("UNUSED_PARAMETER") holder: ItemHolder, @Suppress("UNUSED_PARAMETER") position: Int) {
        // Wait for the list's next real layout rather than re-posting (which could spin forever
        // and never let the main thread go idle while the list has no size).
        val list = recyclerView ?: return
        if (waitingForLayout) return
        waitingForLayout = true
        list.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (r - l <= 0) return
                v.removeOnLayoutChangeListener(this)
                waitingForLayout = false
                v.post { notifyDataSetChanged() }
            }
        })
    }

    override fun onViewRecycled(holder: ItemHolder) {
        holder.jobs.forEach { it?.cancel() }
        holder.highlightJobs.forEach { it?.cancel() }
        holder.images.forEach {
            it.setImageDrawable(null)
            it.tag = null
            it.page = -1
            it.clearOverlays()
        }
    }

    private fun applyFilter(image: PageView) {
        image.colorFilter = PageFilters.forPages(darkPages, sepiaPages)
    }

    private companion object {
        const val PAYLOAD_RERENDER = "rerender"
        const val PAYLOAD_FILTER = "filter"
        const val PAYLOAD_HIGHLIGHTS = "highlights"
        const val MAX_RENDER_WIDTH = 2400
        const val GAP_DP = 6
        const val ROW_PADDING_H_DP = 8
        const val ROW_PADDING_V_DP = 4
    }
}

object PageFilters {
    fun forPages(invert: Boolean, sepia: Boolean) = when {
        invert -> INVERT
        sepia -> SEPIA
        else -> null
    }

    /** Warm paper tone: white becomes cream, black stays black. */
    val SEPIA = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )

    /** Inverts page colors for night reading. */
    val INVERT = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
}
