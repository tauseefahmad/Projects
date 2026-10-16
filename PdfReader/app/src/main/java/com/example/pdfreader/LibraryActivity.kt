package com.example.pdfreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The document library: every PDF opened in the app, with favorites, tags, sorting, list or
 * grid, and search by name and by the text inside all files (including OCR'd text).
 */
class LibraryActivity : AppCompatActivity() {

    private sealed class Row {
        data class Doc(val entry: LibraryEntry) : Row()
        data class Hit(val hit: LibraryHit) : Row()
        data class Header(val title: String) : Row()
    }

    private lateinit var store: LibraryStore
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var filters: ChipGroup
    private lateinit var search: EditText
    private val prefs by lazy { getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE) }

    private var filter: String? = null // null = all, FAVORITES, or a tag
    private var hits: List<LibraryHit> = emptyList()
    private var searchJob: Job? = null
    private var rows: List<Row> = emptyList()

    private var grid: Boolean
        get() = prefs.getBoolean("library_grid", false)
        set(value) = prefs.edit().putBoolean("library_grid", value).apply()

    private var sort: String
        get() = prefs.getString("library_sort", "recent") ?: "recent"
        set(value) = prefs.edit().putString("library_sort", value).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (ReaderSettings(this).theme == AppTheme.SEPIA) setTheme(R.style.Theme_PdfReader_Sepia)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        store = LibraryStore(this)
        list = findViewById(R.id.libraryList)
        empty = findViewById(R.id.libraryEmpty)
        filters = findViewById(R.id.libraryFilters)
        search = findViewById(R.id.librarySearch)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.libraryRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = findViewById<MaterialToolbar>(R.id.libraryToolbar)
        toolbar.inflateMenu(R.menu.library_menu)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.library_layout -> {
                    grid = !grid
                    applyLayout()
                }
                R.id.sort_recent -> sort = "recent"
                R.id.sort_name -> sort = "name"
                R.id.sort_size -> sort = "size"
            }
            if (item.itemId != R.id.library_sort) refresh()
            true
        }
        toolbar.menu.findItem(when (sort) { "name" -> R.id.sort_name; "size" -> R.id.sort_size; else -> R.id.sort_recent }).isChecked = true
        list.adapter = adapter
        applyLayout()
        search.doAfterTextChanged {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(250)
                val query = search.text.toString().trim()
                hits = if (query.length < 2) emptyList() else withContext(Dispatchers.IO) { runCatching { LibraryIndex.get(this@LibraryActivity).search(query) }.getOrDefault(emptyList()) }
                refresh()
            }
        }
        refresh()
    }

    private fun applyLayout() {
        val columns = if (grid) (resources.displayMetrics.widthPixels / resources.displayMetrics.density / 180).toInt().coerceAtLeast(2) else 1
        list.layoutManager = if (columns == 1) LinearLayoutManager(this) else GridLayoutManager(this, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (rows.getOrNull(position) is Row.Doc) 1 else columns
            }
        }
    }

    private fun refresh() {
        val all = store.all()
        // Filter chips: all, favorites, then tags.
        filters.removeAllViews()
        fun chip(label: String, key: String?) = Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = filter == key
            setOnClickListener {
                filter = key
                refresh()
            }
            filters.addView(this)
        }
        chip(getString(R.string.library_all), null)
        chip(getString(R.string.library_favorites), FAVORITES)
        for (tag in store.allTags()) chip("#$tag", tag)

        val query = search.text.toString().trim().lowercase()
        val docs = all.filter { e ->
            when (filter) {
                null -> true
                FAVORITES -> e.favorite
                else -> filter in e.tags
            }
        }.filter { query.isEmpty() || query in it.name.lowercase() || it.tags.any { t -> query in t.lowercase() } }
            .let { l ->
                when (sort) {
                    "name" -> l.sortedBy { it.name.lowercase() }
                    "size" -> l.sortedByDescending { it.size }
                    else -> l.sortedByDescending { it.lastOpened }
                }
            }
        rows = buildList {
            docs.forEach { add(Row.Doc(it)) }
            if (hits.isNotEmpty()) {
                add(Row.Header(getString(R.string.library_text_matches, hits.size)))
                hits.forEach { add(Row.Hit(it)) }
            }
        }
        empty.isVisible = rows.isEmpty()
        empty.setText(if (all.isEmpty()) R.string.library_empty else R.string.library_no_match)
        adapter.notifyDataSetChanged()
    }

    private fun open(uri: Uri, name: String, page: Int, phrase: String? = null) {
        setResult(RESULT_OK, Intent().setData(uri).putExtra(EXTRA_NAME, name).putExtra(EXTRA_PAGE, page).putExtra(EXTRA_PHRASE, phrase))
        finish()
    }

    private fun details(e: LibraryEntry) = listOfNotNull(
        e.pages.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.page_count, it, it) },
        e.size.takeIf { it >= 0 }?.let { Formatter.formatShortFileSize(this, it) },
        DateUtils.getRelativeTimeSpanString(e.lastOpened).toString(),
        e.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
    ).joinToString(" · ")

    private fun editTags(e: LibraryEntry) {
        val input = EditText(this).apply {
            id = R.id.library_tags_input
            setText(e.tags.joinToString(", "))
            setHint(R.string.library_tags_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_tags)
            .setView(android.widget.FrameLayout(this).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            })
            .setPositiveButton(R.string.ok) { _, _ ->
                val tags = input.text.toString().split(',', ' ', '#').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                store.update(e.copy(tags = tags))
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun entryMenu(e: LibraryEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(e.name)
            .setItems(arrayOf(getString(R.string.library_tags), getString(R.string.library_remove))) { _, which ->
                if (which == 0) editTags(e) else {
                    store.remove(e.uri)
                    lifecycleScope.launch(Dispatchers.IO) { runCatching { LibraryIndex.get(this@LibraryActivity).remove(e.uri) } }
                    refresh()
                }
            }
            .show()
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = if (viewType == 1) {
                TextView(parent.context).apply {
                    setPadding(12, 24, 12, 8)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                }
            } else {
                LayoutInflater.from(parent.context).inflate(R.layout.item_library, parent, false)
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            when (val row = rows[position]) {
                is Row.Header -> (view as TextView).text = row.title
                is Row.Doc -> {
                    val e = row.entry
                    view.findViewById<TextView>(R.id.libraryName).text = e.name
                    view.findViewById<TextView>(R.id.libraryDetails).text = details(e)
                    view.findViewById<ImageButton>(R.id.libraryStar).apply {
                        isVisible = true
                        setImageResource(if (e.favorite) R.drawable.ic_star else R.drawable.ic_star_border)
                        contentDescription = getString(if (e.favorite) R.string.library_unfavorite else R.string.library_favorite, e.name)
                        setOnClickListener {
                            store.update(e.copy(favorite = !e.favorite))
                            refresh()
                        }
                    }
                    view.setOnClickListener { open(e.uri, e.name, -1) }
                    view.setOnLongClickListener {
                        entryMenu(e)
                        true
                    }
                }
                is Row.Hit -> {
                    val h = row.hit
                    view.findViewById<TextView>(R.id.libraryName).text = getString(R.string.library_hit_title, h.name, h.page + 1)
                    view.findViewById<TextView>(R.id.libraryDetails).text = h.snippet.replace('\n', ' ')
                    view.findViewById<View>(R.id.libraryStar).isVisible = false
                    view.setOnClickListener { open(h.uri, h.name, h.page, search.text.toString().trim()) }
                    view.setOnLongClickListener(null)
                }
            }
        }
    }

    companion object {
        private const val FAVORITES = "\u0000favorites"
        const val EXTRA_NAME = "name"
        const val EXTRA_PAGE = "page"
        const val EXTRA_PHRASE = "phrase"
    }
}
