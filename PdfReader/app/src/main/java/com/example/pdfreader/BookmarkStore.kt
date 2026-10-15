package com.example.pdfreader

import android.content.Context
import android.net.Uri
import com.example.pdfreader.core.Bookmark
import com.example.pdfreader.core.Bookmarks
import org.json.JSONArray
import org.json.JSONObject

/** The user's own bookmarks, stored per file. */
class BookmarkStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(uri: Uri): List<Bookmark> {
        val json = prefs.getString(uri.toString(), null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                Bookmark(item.getInt("page"), item.getString("title"), item.optLong("created"))
            }.sortedBy { it.page }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun toggle(uri: Uri, page: Int, defaultTitle: String): List<Bookmark> =
        Bookmarks.toggle(load(uri), page, defaultTitle, System.currentTimeMillis()).also { save(uri, it) }

    fun rename(uri: Uri, page: Int, title: String): List<Bookmark> =
        Bookmarks.rename(load(uri), page, title).also { save(uri, it) }

    private fun save(uri: Uri, bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach { array.put(JSONObject().put("page", it.page).put("title", it.title).put("created", it.createdAt)) }
        prefs.edit().apply {
            if (bookmarks.isEmpty()) remove(uri.toString()) else putString(uri.toString(), array.toString())
        }.apply()
    }

    companion object {
        const val PREFS_NAME = "bookmarks"
    }
}
