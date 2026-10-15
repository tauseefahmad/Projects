package com.example.pdfreader

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.pdfreader.core.SearchMatch
import com.example.pdfreader.core.TextSearch

/** Search hits with their surrounding text; the match is shown in bold. */
class SearchResultAdapter(
    private val textFor: (page: Int) -> String?,
    private val onClick: (index: Int) -> Unit,
) : RecyclerView.Adapter<SearchResultAdapter.Holder>() {

    var matches: List<SearchMatch> = emptyList()
        private set
    var currentIndex = -1
        private set

    fun submit(matches: List<SearchMatch>, currentIndex: Int) {
        val changed = matches != this.matches
        this.matches = matches
        this.currentIndex = currentIndex
        if (changed) notifyDataSetChanged() else notifyItemRangeChanged(0, itemCount)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val page: TextView = view.findViewById(R.id.resultPage)
        val snippet: TextView = view.findViewById(R.id.resultSnippet)
    }

    override fun getItemCount() = matches.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val match = matches[position]
        holder.page.text = holder.itemView.context.getString(R.string.page_thumbnail, match.page + 1)
        val text = textFor(match.page)
        holder.snippet.text = if (text == null) "" else {
            val snippet = TextSearch.snippet(text, match.start, match.end)
            SpannableString(snippet.text).apply {
                setSpan(StyleSpan(Typeface.BOLD), snippet.matchStart, snippet.matchEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        holder.itemView.isSelected = position == currentIndex
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
    }
}
