package com.example.pdfreader

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Shows one PDF page per row, rendering lazily as rows scroll into view. */
class PageAdapter(
    private val document: PdfDocument,
    private val scope: CoroutineScope,
) : RecyclerView.Adapter<PageAdapter.PageHolder>() {

    /** Extra resolution multiplier while zoomed in, so text stays crisp. */
    var renderScale = 1f
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_RERENDER)
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

    class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.pageImage)
        var job: Job? = null
    }

    override fun getItemCount() = document.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
        PageHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false))

    override fun onBindViewHolder(holder: PageHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_FILTER }) {
            applyFilter(holder.image)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val parent = recyclerView ?: return
        val rowPadding = holder.itemView.paddingLeft + holder.itemView.paddingRight
        val width = parent.width - parent.paddingLeft - parent.paddingRight - rowPadding
        if (width <= 0) return bindLater(holder, position)

        val size = document.pageSizes[position]
        val height = (width.toLong() * size.height / size.width).toInt()
        holder.image.layoutParams = holder.image.layoutParams.apply { this.height = height }
        holder.image.contentDescription = "Page ${position + 1}"
        applyFilter(holder.image)

        val renderWidth = (width * renderScale).toInt().coerceAtMost(MAX_RENDER_WIDTH)
        val key = "$position@$renderWidth"
        holder.job?.cancel()
        val cached = cache.get(key)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            return
        }

        // Keep showing an older (blurrier) render of the same page while the sharp one loads.
        if (holder.image.tag != position) holder.image.setImageDrawable(null)
        holder.image.tag = position
        holder.job = scope.launch {
            try {
                val bitmap = document.renderPage(position, renderWidth)
                cache.put(key, bitmap)
                if (holder.bindingAdapterPosition == position) holder.image.setImageBitmap(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Document closed or page failed to render; leave the blank page.
            }
        }
    }

    /** The first bind can happen before the list has a width; retry after layout. */
    private fun bindLater(holder: PageHolder, position: Int) {
        holder.itemView.post {
            if (holder.bindingAdapterPosition == position) onBindViewHolder(holder, position)
        }
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.job?.cancel()
        holder.image.setImageDrawable(null)
        holder.image.tag = null
    }

    fun clearCache() = cache.evictAll()

    private fun applyFilter(image: ImageView) {
        image.colorFilter = if (darkPages) INVERT else null
    }

    private companion object {
        const val PAYLOAD_RERENDER = "rerender"
        const val PAYLOAD_FILTER = "filter"
        const val MAX_RENDER_WIDTH = 2400

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
}
