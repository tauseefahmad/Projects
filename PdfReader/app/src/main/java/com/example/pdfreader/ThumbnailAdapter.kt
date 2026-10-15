package com.example.pdfreader

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.pdfreader.core.PageRotation
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Small previews of every page for the side panel; tap one to jump there. */
class ThumbnailAdapter(
    private val document: PdfDocument,
    private val scope: CoroutineScope,
    private val onPageClick: (Int) -> Unit,
) : RecyclerView.Adapter<ThumbnailAdapter.Holder>() {

    /** The page the reader is on; its thumbnail is outlined. */
    var currentPage = 0
        set(value) {
            if (field == value) return
            val old = field
            field = value
            notifyItemChanged(old)
            notifyItemChanged(value)
        }

    /** Clockwise view rotation, matching the reader. */
    var rotation = 0
        set(value) {
            if (field == value) return
            field = value
            cache.evictAll()
            notifyDataSetChanged()
        }

    var darkPages = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    // Thumbnails are tiny, so a small budget holds many of them.
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.thumbnailCard)
        val image: ImageView = view.findViewById(R.id.thumbnailImage)
        val label: TextView = view.findViewById(R.id.thumbnailLabel)
        var job: Job? = null
    }

    override fun getItemCount() = document.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_thumbnail, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val density = holder.itemView.resources.displayMetrics.density
        val size = document.pageSizes[position]
        val turn = PageRotation(size.width.toFloat(), size.height.toFloat(), rotation)
        // Fit the page into a 96×136dp box, keeping its shape.
        val boxWidth = THUMB_WIDTH_DP * density
        val boxHeight = THUMB_HEIGHT_DP * density
        val scale = minOf(boxWidth / turn.displayWidth, boxHeight / turn.displayHeight)
        val width = (turn.displayWidth * scale).toInt().coerceAtLeast(1)
        val height = (turn.displayHeight * scale).toInt().coerceAtLeast(1)
        holder.image.layoutParams = holder.image.layoutParams.apply {
            this.width = width
            this.height = height
        }
        holder.image.colorFilter = if (darkPages) PageFilters.INVERT else null

        val selected = position == currentPage
        holder.itemView.isSelected = selected
        holder.card.strokeWidth = if (selected) (3 * density).toInt() else 0
        holder.label.text = (position + 1).toString()
        holder.itemView.contentDescription = holder.itemView.context.getString(R.string.page_thumbnail, position + 1)
        holder.itemView.setOnClickListener { onPageClick(holder.bindingAdapterPosition) }

        holder.job?.cancel()
        val cached = cache.get(position)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            return
        }
        holder.image.setImageDrawable(null)
        holder.job = scope.launch {
            try {
                val bitmap = document.renderPage(position, width, rotation)
                cache.put(position, bitmap)
                if (holder.bindingAdapterPosition == position) holder.image.setImageBitmap(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the blank thumbnail.
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.image.setImageDrawable(null)
    }

    private companion object {
        const val THUMB_WIDTH_DP = 96
        const val THUMB_HEIGHT_DP = 136
    }
}
