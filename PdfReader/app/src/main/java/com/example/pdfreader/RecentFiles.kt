package com.example.pdfreader

import android.content.Context
import android.net.Uri
import com.example.pdfreader.core.ZoomState
import org.json.JSONArray
import org.json.JSONObject

/** A recently opened document with where the reader left off: page and zoom. */
data class RecentFile(
    val uri: Uri,
    val name: String,
    val page: Int,
    val zoom: ZoomState? = null,
)

/** Remembers the last few documents and the page the reader was on. */
class RecentFiles(context: Context) {

    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    fun load(): List<RecentFile> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val zoom = if (item.has("layoutScale")) {
                    ZoomState(item.getDouble("layoutScale").toFloat(), item.getDouble("drawZoom").toFloat())
                } else {
                    null
                }
                RecentFile(Uri.parse(item.getString("uri")), item.getString("name"), item.optInt("page", 0), zoom)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun find(uri: Uri): RecentFile? = load().firstOrNull { it.uri == uri }

    /** Moves (or adds) [file] to the top of the list. */
    fun remember(file: RecentFile) {
        save(listOf(file) + load().filter { it.uri != file.uri })
    }

    fun remove(uri: Uri) = save(load().filter { it.uri != uri })

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun save(files: List<RecentFile>) {
        val array = JSONArray()
        files.take(MAX).forEach {
            val item = JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("page", it.page)
            it.zoom?.let { zoom -> item.put("layoutScale", zoom.layoutScale.toDouble()).put("drawZoom", zoom.drawZoom.toDouble()) }
            array.put(item)
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "files"
        const val MAX = 8
    }
}
