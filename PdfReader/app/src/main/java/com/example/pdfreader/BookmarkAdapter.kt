package com.example.pdfreader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.pdfreader.core.Bookmark

/** The user's bookmarks for the open file. Tap to jump, long-press to rename, × to delete. */
class BookmarkAdapter(
    private val onOpen: (Bookmark) -> Unit,
    private val onRename: (Bookmark) -> Unit,
    private val onDelete: (Bookmark) -> Unit,
) : RecyclerView.Adapter<BookmarkAdapter.Holder>() {

    var bookmarks: List<Bookmark> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.bookmarkTitle)
        val page: TextView = view.findViewById(R.id.bookmarkPage)
        val delete: ImageButton = view.findViewById(R.id.bookmarkDelete)
    }

    override fun getItemCount() = bookmarks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val bookmark = bookmarks[position]
        val context = holder.itemView.context
        holder.title.text = bookmark.title
        holder.page.text = context.getString(R.string.page_thumbnail, bookmark.page + 1)
        holder.delete.contentDescription = context.getString(R.string.delete_bookmark, bookmark.title)
        holder.itemView.setOnClickListener { onOpen(bookmark) }
        holder.itemView.setOnLongClickListener {
            onRename(bookmark)
            true
        }
        holder.delete.setOnClickListener { onDelete(bookmark) }
    }
}
