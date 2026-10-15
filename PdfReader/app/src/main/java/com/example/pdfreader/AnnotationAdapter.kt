package com.example.pdfreader

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.pdfreader.core.Annotation
import com.example.pdfreader.core.AnnotationSummary
import com.example.pdfreader.core.typeName

/** The annotation panel: every annotation in reading order. Tap to go to it, × to delete. */
class AnnotationAdapter(
    private val onOpen: (Annotation) -> Unit,
    private val onDelete: (Annotation) -> Unit,
) : RecyclerView.Adapter<AnnotationAdapter.Holder>() {

    var items: List<Annotation> = emptyList()
        set(value) {
            field = AnnotationSummary.sorted(value)
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val color: View = view.findViewById(R.id.annotationColor)
        val title: TextView = view.findViewById(R.id.annotationTitle)
        val content: TextView = view.findViewById(R.id.annotationContent)
        val note: TextView = view.findViewById(R.id.annotationNote)
        val delete: ImageButton = view.findViewById(R.id.annotationDelete)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_annotation, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val a = items[position]
        val context = holder.itemView.context
        holder.color.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(a.color)
            setStroke((context.resources.displayMetrics.density).toInt(), 0x66000000)
        }
        val by = a.author.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        holder.title.text = context.getString(R.string.annotation_row_title, a.typeName(), a.page + 1) + by
        val content = AnnotationSummary.content(a)
        holder.content.text = content
        holder.content.isVisible = content.isNotEmpty()
        holder.note.text = a.note
        holder.note.isVisible = a.note.isNotBlank()
        holder.delete.contentDescription = context.getString(R.string.annot_delete_named, a.typeName(), a.page + 1)
        holder.itemView.setOnClickListener { onOpen(a) }
        holder.delete.setOnClickListener { onDelete(a) }
    }
}
