package com.example.pdfreader

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(val uri: Uri, val name: String, val page: Int)

/** Remembers the last few documents and the page the reader was on. */
class RecentFiles(context: Context) {

    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    fun load(): List<RecentFile> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                RecentFile(Uri.parse(item.getString("uri")), item.getString("name"), item.optInt("page", 0))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun lastPage(uri: Uri): Int = load().firstOrNull { it.uri == uri }?.page ?: 0

    /** Moves (or adds) [file] to the top of the list. */
    fun remember(file: RecentFile) {
        save(listOf(file) + load().filter { it.uri != file.uri })
    }

    fun remove(uri: Uri) = save(load().filter { it.uri != uri })

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun save(files: List<RecentFile>) {
        val array = JSONArray()
        files.take(MAX).forEach {
            array.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("page", it.page))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "files"
        const val MAX = 8
    }
}
