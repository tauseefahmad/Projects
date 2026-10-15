package com.example.pdfreader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.pdfreader.core.OutlineNode
import com.example.pdfreader.core.OutlineRow
import com.example.pdfreader.core.OutlineTree

/** The PDF's table of contents as a collapsible tree. */
class OutlineAdapter(private val onEntryClick: (OutlineNode) -> Unit) : RecyclerView.Adapter<OutlineAdapter.Holder>() {

    private var roots: List<OutlineNode> = emptyList()
    private val expanded = mutableSetOf<String>()
    private var rows: List<OutlineRow> = emptyList()

    /** Id of the section containing the current page; highlighted. */
    private var currentSection: String? = null

    fun submit(outline: List<OutlineNode>) {
        roots = outline
        expanded.clear()
        refresh()
    }

    val isEmpty: Boolean get() = roots.isEmpty()

    /** Highlights the section the reader is in and expands its parents. */
    fun showCurrentPage(page: Int) {
        val section = OutlineTree.sectionFor(roots, page)
        if (section == currentSection) return
        currentSection = section
        section?.let { expanded.addAll(OutlineTree.ancestors(it)) }
        refresh()
    }

    fun positionOfCurrentSection(): Int = rows.indexOfFirst { it.id == currentSection }

    private fun refresh() {
        rows = OutlineTree.visibleRows(roots, expanded)
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val toggle: ImageView = view.findViewById(R.id.outlineToggle)
        val title: TextView = view.findViewById(R.id.outlineTitle)
        val page: TextView = view.findViewById(R.id.outlinePage)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_outline, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val density = holder.itemView.resources.displayMetrics.density
        holder.itemView.setPaddingRelative(((8 + 16 * row.depth) * density).toInt(), 0, (16 * density).toInt(), 0)
        holder.title.text = row.node.title
        holder.page.text = row.node.page?.let { (it + 1).toString() } ?: ""
        holder.itemView.isSelected = row.id == currentSection
        holder.toggle.visibility = if (row.hasChildren) View.VISIBLE else View.INVISIBLE
        holder.toggle.rotation = if (row.expanded) 90f else 0f
        holder.toggle.contentDescription = holder.itemView.context.getString(
            if (row.expanded) R.string.collapse_section else R.string.expand_section, row.node.title
        )
        holder.toggle.setOnClickListener {
            if (!expanded.remove(row.id)) expanded.add(row.id)
            refresh()
        }
        holder.itemView.setOnClickListener {
            if (row.node.page != null) onEntryClick(row.node) else holder.toggle.performClick()
        }
    }
}
