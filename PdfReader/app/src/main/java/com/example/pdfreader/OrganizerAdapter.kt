package com.example.pdfreader

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.pdfreader.core.PageRef
import com.example.pdfreader.core.PageRotation
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Large page thumbnails for the organizer grid, showing selection and pending rotation. */
class OrganizerAdapter(
    private val model: OrganizerViewModel,
    private val scope: CoroutineScope,
    private val onClick: (position: Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<OrganizerAdapter.Holder>() {

    /** The list as shown; during a drag it differs from the editor until the drop. */
    var pages: MutableList<PageRef> = mutableListOf()

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun submit(newPages: List<PageRef>) {
        pages = newPages.toMutableList()
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.organizerCard)
        val image: ImageView = view.findViewById(R.id.organizerImage)
        val check: ImageView = view.findViewById(R.id.organizerCheck)
        val label: TextView = view.findViewById(R.id.organizerLabel)
        var job: Job? = null
    }

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_organizer_page, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ref = pages[position]
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density
        val size = model.pageSize(ref)
        val turn = PageRotation(size.width.toFloat(), size.height.toFloat(), ref.rotation)
        val scale = minOf(BOX_WIDTH_DP * density / turn.displayWidth, BOX_HEIGHT_DP * density / turn.displayHeight)
        val width = (turn.displayWidth * scale).toInt().coerceAtLeast(1)
        val height = (turn.displayHeight * scale).toInt().coerceAtLeast(1)
        holder.image.layoutParams = holder.image.layoutParams.apply {
            this.width = width
            this.height = height
        }

        val selected = position in model.selection
        holder.itemView.isSelected = selected
        holder.check.visibility = if (selected) View.VISIBLE else View.GONE
        holder.card.strokeWidth = if (selected) (3 * density).toInt() else 0
        holder.label.text = when {
            ref.isBlank -> "${position + 1} · ${context.getString(R.string.blank_page)}"
            ref.source == 0 -> "${position + 1}"
            else -> "${position + 1} (+)"
        }
        holder.itemView.contentDescription = context.getString(R.string.page_thumbnail, position + 1)
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onStartDrag(holder)
            true
        }

        holder.job?.cancel()
        if (ref.isBlank) {
            holder.image.setImageDrawable(null)
            return
        }
        val key = "${ref.source}/${ref.page}/${ref.rotation}@$width"
        cache.get(key)?.let {
            holder.image.setImageBitmap(it)
            return
        }
        holder.image.setImageDrawable(null)
        holder.job = scope.launch {
            try {
                val bitmap = model.renderers[ref.source].renderPage(ref.page, width, ref.rotation)
                cache.put(key, bitmap)
                if (holder.bindingAdapterPosition == position) holder.image.setImageBitmap(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the page blank.
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.image.setImageDrawable(null)
    }

    private companion object {
        const val BOX_WIDTH_DP = 112
        const val BOX_HEIGHT_DP = 156
    }
}
