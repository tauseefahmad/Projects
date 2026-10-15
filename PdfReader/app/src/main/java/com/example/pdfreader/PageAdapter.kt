package com.example.pdfreader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.example.pdfreader.core.PageBox
import com.example.pdfreader.core.PageFit
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

    class ItemHolder(val row: LinearLayout, val images: List<ImageView>) : RecyclerView.ViewHolder(row) {
        val jobs = arrayOfNulls<Job>(images.size)
    }

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
            ImageView(parent.context).apply {
                setBackgroundColor(Color.WHITE)
                elevation = 2 * density
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = LinearLayout.LayoutParams(0, 0).apply {
                    if (index == 1) marginStart = (GAP_DP * density).toInt()
                }
                row.addView(this)
            }
        }
        return ItemHolder(row, images)
    }

    override fun onBindViewHolder(holder: ItemHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_FILTER }) {
            holder.images.forEach(::applyFilter)
        } else {
            onBindViewHolder(holder, position)
        }
    }

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
        spreads.items[position].map { PageBox(document.pageSizes[it].width, document.pageSizes[it].height) },
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
            if (slot >= pages.size) {
                image.visibility = View.GONE
                image.setImageDrawable(null)
                image.tag = null
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
            applyFilter(image)
            bindPage(holder, slot, page, box.width)
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
                val bitmap = document.renderPage(page, renderWidth)
                cache.put(key, bitmap)
                if (image.tag == page) image.setImageBitmap(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Document closed or page failed to render; leave the blank page.
            }
        }
    }

    /** The first bind can happen before the list has a size; retry after layout. */
    private fun bindLater(holder: ItemHolder, position: Int) {
        holder.itemView.post {
            if (holder.bindingAdapterPosition == position) onBindViewHolder(holder, position)
        }
    }

    override fun onViewRecycled(holder: ItemHolder) {
        holder.jobs.forEach { it?.cancel() }
        holder.images.forEach {
            it.setImageDrawable(null)
            it.tag = null
        }
    }

    private fun applyFilter(image: ImageView) {
        image.colorFilter = if (darkPages) PageFilters.INVERT else null
    }

    private companion object {
        const val PAYLOAD_RERENDER = "rerender"
        const val PAYLOAD_FILTER = "filter"
        const val MAX_RENDER_WIDTH = 2400
        const val GAP_DP = 6
        const val ROW_PADDING_H_DP = 8
        const val ROW_PADDING_V_DP = 4
    }
}

object PageFilters {
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
