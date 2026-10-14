package com.example.pdfreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var pages: ZoomableRecyclerView
    private lateinit var pageIndicator: TextView
    private lateinit var progress: ProgressBar
    private lateinit var emptyState: ScrollView
    private lateinit var recentSection: LinearLayout
    private lateinit var recentList: LinearLayout

    private var adapter: PageAdapter? = null
    private var shownDocument: PdfDocument? = null
    private var darkPages = false
    private val hideIndicator = Runnable { pageIndicator.animate().alpha(0f).setDuration(300) }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep access across restarts so the file can be reopened from "Recent".
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Provider doesn't offer persistable access; it still works for this session.
            }
            openUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        pages = findViewById(R.id.pages)
        pageIndicator = findViewById(R.id.pageIndicator)
        progress = findViewById(R.id.progress)
        emptyState = findViewById(R.id.emptyState)
        recentSection = findViewById(R.id.recentSection)
        recentList = findViewById(R.id.recentList)

        darkPages = savedInstanceState?.getBoolean(KEY_DARK_PAGES) ?: false

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.menu.findItem(R.id.action_dark_pages).isChecked = darkPages
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open -> launchPicker()
                R.id.action_go_to_page -> showGoToPageDialog()
                R.id.action_dark_pages -> {
                    darkPages = !darkPages
                    item.isChecked = darkPages
                    adapter?.darkPages = darkPages
                }
                R.id.action_close -> viewModel.close()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        findViewById<View>(R.id.openButton).setOnClickListener { launchPicker() }
        findViewById<View>(R.id.clearRecent).setOnClickListener {
            viewModel.recentFiles.clear()
            showRecentFiles()
        }

        pages.layoutManager = LinearLayoutManager(this)
        pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updatePageIndicator()
        })
        pages.onZoomSettled = { zoom ->
            // Re-render in 0.5 steps (max 2x) so text stays sharp without exhausting memory.
            adapter?.renderScale = ((zoom.coerceAtMost(2f) * 2).roundToInt() / 2f).coerceAtLeast(1f)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DARK_PAGES, darkPages)
    }

    override fun onStop() {
        super.onStop()
        if (shownDocument != null) {
            viewModel.updatePage(currentPage())
            viewModel.savePage()
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(::openUri)
    }

    private fun launchPicker() = openDocument.launch(arrayOf("application/pdf"))

    private fun openUri(uri: Uri) = viewModel.open(uri, displayName(uri))

    private fun render(state: ReaderState) {
        progress.isVisible = state is ReaderState.Loading
        val ready = state as? ReaderState.Ready
        pages.isVisible = ready != null
        emptyState.isVisible = state is ReaderState.Empty || state is ReaderState.Error
        toolbar.menu.findItem(R.id.action_go_to_page).isVisible = ready != null
        toolbar.menu.findItem(R.id.action_close).isVisible = ready != null
        toolbar.title = ready?.name ?: getString(R.string.app_name)

        if (ready == null) {
            showDocument(null)
            pageIndicator.isVisible = false
            showRecentFiles()
        } else if (ready.document !== shownDocument) {
            showDocument(ready.document)
            (pages.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(viewModel.currentPage, 0)
            pages.post { updatePageIndicator() }
        }

        if (state is ReaderState.Error) {
            Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            viewModel.errorShown()
        }
    }

    private fun showDocument(document: PdfDocument?) {
        shownDocument = document
        pages.resetZoom()
        adapter = document?.let { PageAdapter(it, lifecycleScope).also { a -> a.darkPages = darkPages } }
        pages.adapter = adapter
    }

    private fun currentPage(): Int {
        val child = pages.findChildViewUnder(pages.toContentX(pages.width / 2f), pages.toContentY(pages.height / 2f))
            ?: return (pages.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)
        return pages.getChildAdapterPosition(child).coerceAtLeast(0)
    }

    private fun updatePageIndicator() {
        val document = shownDocument ?: return
        val page = currentPage()
        viewModel.updatePage(page)
        pageIndicator.text = getString(R.string.page_indicator, page + 1, document.pageCount)
        pageIndicator.isVisible = true
        pageIndicator.animate().cancel()
        pageIndicator.alpha = 1f
        pageIndicator.removeCallbacks(hideIndicator)
        pageIndicator.postDelayed(hideIndicator, 1500)
    }

    private fun showGoToPageDialog() {
        val document = shownDocument ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.page_number_hint, document.pageCount)
            setText((currentPage() + 1).toString())
            selectAll()
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.go_to_page)
            .setView(container)
            .setPositiveButton(R.string.go) { _, _ ->
                val page = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                (pages.layoutManager as LinearLayoutManager)
                    .scrollToPositionWithOffset((page - 1).coerceIn(0, document.pageCount - 1), 0)
                pages.post { updatePageIndicator() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRecentFiles() {
        val recent = viewModel.recentFiles.load()
        recentSection.isVisible = recent.isNotEmpty()
        recentList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        recent.forEach { file ->
            val row = inflater.inflate(R.layout.item_recent, recentList, false) as TextView
            row.text = getString(R.string.recent_item, file.name, file.page + 1)
            row.setOnClickListener { viewModel.open(file.uri, file.name) }
            recentList.addView(row)
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { return it }
                }
            }
        } catch (e: Exception) {
            // Fall back to the last path segment below.
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"
    }

    private companion object {
        const val KEY_DARK_PAGES = "dark_pages"
    }
}
