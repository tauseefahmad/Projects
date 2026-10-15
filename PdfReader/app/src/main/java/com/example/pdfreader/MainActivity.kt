package com.example.pdfreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.pdfreader.core.Navigation
import com.example.pdfreader.core.SpreadMode
import com.example.pdfreader.core.Spreads
import com.example.pdfreader.core.ViewMode
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import android.view.Menu
import com.example.pdfreader.core.ZoomMath

class MainActivity : AppCompatActivity() {

    private val viewModel: ReaderViewModel by viewModels()
    private val settings by lazy { ReaderSettings(this) }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var pages: ZoomableRecyclerView
    private lateinit var fastScroll: FastScrollHandle
    private lateinit var navBar: View
    private lateinit var prevPage: ImageButton
    private lateinit var nextPage: ImageButton
    private lateinit var pageLabel: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var emptyState: ScrollView
    private lateinit var recentSection: LinearLayout
    private lateinit var recentList: LinearLayout
    private lateinit var zoom: ZoomController
    private lateinit var drawer: DrawerLayout
    private lateinit var thumbnails: RecyclerView
    private var thumbnailAdapter: ThumbnailAdapter? = null

    private var adapter: PageAdapter? = null
    private var shownDocument: PdfDocument? = null
    private val snapHelper = PagerSnapHelper()

    /**
     * The item the user last navigated to with buttons, keys or the go-to dialog. Near the end
     * of the list an item may not be able to scroll to the top, so detecting it on screen would
     * leave "Next" stuck; this remembers it until the user moves the list by hand.
     */
    private var explicitItem: Int? = null

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
        fastScroll = findViewById(R.id.fastScroll)
        navBar = findViewById(R.id.navBar)
        prevPage = findViewById(R.id.prevPage)
        nextPage = findViewById(R.id.nextPage)
        pageLabel = findViewById(R.id.pageLabel)
        progress = findViewById(R.id.progress)
        emptyState = findViewById(R.id.emptyState)
        recentSection = findViewById(R.id.recentSection)
        recentList = findViewById(R.id.recentList)
        drawer = findViewById(R.id.drawer)
        thumbnails = findViewById(R.id.thumbnails)
        thumbnails.layoutManager = GridLayoutManager(this, 2)
        setUpSidePanel()
        zoom = ZoomController(pages, findViewById(R.id.zoomLabel), lifecycleScope, ::currentItem) { scrollToItem(it) }

        val root = findViewById<View>(R.id.root)
        val sidePanel = findViewById<View>(R.id.sidePanel)
        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            sidePanel.setPadding(bars.left, bars.top, 0, bars.bottom)
            insets
        }

        setUpMenu()

        findViewById<View>(R.id.openButton).setOnClickListener { launchPicker() }
        findViewById<View>(R.id.clearRecent).setOnClickListener {
            viewModel.recentFiles.clear()
            showRecentFiles()
        }

        prevPage.setOnClickListener { stepItems(-1) }
        nextPage.setOnClickListener { stepItems(1) }
        pageLabel.setOnClickListener { showGoToPageDialog() }
        fastScroll.onScrubbed = { page -> goToPage(page) }

        pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updatePageLabel()
        })
        pages.onTouchStart = { explicitItem = null }

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

    override fun onStop() {
        super.onStop()
        if (shownDocument != null) {
            viewModel.updatePage(currentPage())
            viewModel.savePage()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val spreads = adapter?.spreads
        if (spreads != null && shownDocument != null) {
            val action = KeyNavigation.actionFor(event.keyCode, event.isCtrlPressed, event.isShiftPressed, settings.volumeKeysTurnPages)
            // Arrow keys still move focus around the toolbar when it has focus.
            val focusInToolbar = currentFocus?.let { isInside(it, toolbar) } == true
            if (action != null && !focusInToolbar) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (action) {
                        NavAction.NEXT -> stepItems(1)
                        NavAction.PREVIOUS -> stepItems(-1)
                        NavAction.FIRST -> scrollToItem(0)
                        NavAction.LAST -> scrollToItem(spreads.size - 1)
                        NavAction.GO_TO -> if (event.repeatCount == 0) showGoToPageDialog()
                        NavAction.ZOOM_IN -> zoom.step(1)
                        NavAction.ZOOM_OUT -> zoom.step(-1)
                        NavAction.ZOOM_FIT -> zoom.fitWidth()
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isInside(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun setUpSidePanel() {
        val tabs = findViewById<TabLayout>(R.id.sideTabs)
        tabs.addTab(tabs.newTab().setText(R.string.tab_pages))
        toolbar.setNavigationOnClickListener {
            if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START) else openSidePanel()
        }
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) = scrollThumbnailsToCurrent()
        })
        // Close the panel with Back before leaving the app.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = drawer.closeDrawer(GravityCompat.START)
        }.also { callback ->
            drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerOpened(drawerView: View) { callback.isEnabled = true }
                override fun onDrawerClosed(drawerView: View) { callback.isEnabled = false }
            })
        })
    }

    private fun openSidePanel() {
        scrollThumbnailsToCurrent()
        drawer.openDrawer(GravityCompat.START)
    }

    private fun scrollThumbnailsToCurrent() {
        val page = thumbnailAdapter?.currentPage ?: return
        (thumbnails.layoutManager as GridLayoutManager).scrollToPositionWithOffset(page, thumbnails.height / 3)
    }

    private fun setUpMenu() {
        toolbar.inflateMenu(R.menu.main_menu)
        val zoomMenu = toolbar.menu.findItem(R.id.action_zoom).subMenu!!
        ZoomMath.PRESETS.forEachIndexed { index, percent ->
            zoomMenu.add(Menu.NONE, ZOOM_PRESET_ID_BASE + percent, index + 10, getString(R.string.zoom_percent, percent))
        }
        syncMenuChecks()
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open -> launchPicker()
                R.id.action_go_to_page -> showGoToPageDialog()
                R.id.zoom_fit_width -> {
                    settings.setFitMode(settings.viewMode, FitMode.FIT_WIDTH)
                    zoom.fitWidth()
                }
                R.id.zoom_fit_page -> {
                    settings.setFitMode(settings.viewMode, FitMode.FIT_PAGE)
                    zoom.fitPage()
                }
                R.id.zoom_actual_size -> zoom.setPercent(100)
                in ZOOM_PRESET_ID_BASE..ZOOM_PRESET_ID_BASE + 1000 -> zoom.setPercent(item.itemId - ZOOM_PRESET_ID_BASE)
                R.id.view_continuous -> changeLayout { settings.viewMode = ViewMode.CONTINUOUS }
                R.id.view_single_page -> changeLayout { settings.viewMode = ViewMode.SINGLE_PAGE }
                R.id.spread_none -> changeLayout { settings.spreadMode = SpreadMode.NONE }
                R.id.spread_two -> changeLayout { settings.spreadMode = SpreadMode.TWO_PAGE }
                R.id.spread_cover -> changeLayout { settings.spreadMode = SpreadMode.TWO_PAGE_COVER }
                R.id.action_dark_pages -> {
                    settings.darkPages = !settings.darkPages
                    adapter?.darkPages = settings.darkPages
                    thumbnailAdapter?.darkPages = settings.darkPages
                    zoom.refreshDetail()
                    syncMenuChecks()
                }
                R.id.action_volume_keys -> {
                    settings.volumeKeysTurnPages = !settings.volumeKeysTurnPages
                    syncMenuChecks()
                }
                R.id.action_close -> viewModel.close()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    private fun syncMenuChecks() {
        val menu = toolbar.menu
        menu.findItem(if (settings.viewMode == ViewMode.SINGLE_PAGE) R.id.view_single_page else R.id.view_continuous).isChecked = true
        menu.findItem(
            when (settings.spreadMode) {
                SpreadMode.NONE -> R.id.spread_none
                SpreadMode.TWO_PAGE -> R.id.spread_two
                SpreadMode.TWO_PAGE_COVER -> R.id.spread_cover
            }
        ).isChecked = true
        menu.findItem(R.id.action_dark_pages).isChecked = settings.darkPages
        menu.findItem(R.id.action_volume_keys).isChecked = settings.volumeKeysTurnPages
    }

    /** Applies a layout setting change while keeping the reader on the same page. */
    private fun changeLayout(update: () -> Unit) {
        val page = if (shownDocument != null) currentPage() else 0
        update()
        syncMenuChecks()
        shownDocument?.let { applyLayout(it, page) }
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
        navBar.isVisible = ready != null
        emptyState.isVisible = state is ReaderState.Empty || state is ReaderState.Error
        toolbar.menu.findItem(R.id.action_go_to_page).isVisible = ready != null
        toolbar.menu.findItem(R.id.action_zoom).isVisible = ready != null
        toolbar.menu.findItem(R.id.action_close).isVisible = ready != null
        toolbar.title = ready?.name ?: getString(R.string.app_name)
        if (ready != null) {
            toolbar.setNavigationIcon(R.drawable.ic_side_panel)
            toolbar.setNavigationContentDescription(R.string.open_side_panel)
            drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        } else {
            toolbar.navigationIcon = null
            drawer.closeDrawer(GravityCompat.START, false)
            drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        }

        if (ready == null) {
            showDocument(null)
            showRecentFiles()
        } else if (ready.document !== shownDocument) {
            showDocument(ready.document)
        }

        if (state is ReaderState.Error) {
            Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            viewModel.errorShown()
        }
    }

    private fun showDocument(document: PdfDocument?) {
        shownDocument = document
        explicitItem = null
        thumbnailAdapter = document?.let { doc ->
            ThumbnailAdapter(doc, lifecycleScope) { page ->
                goToPage(page)
                drawer.closeDrawer(GravityCompat.START)
            }.also { it.darkPages = settings.darkPages }
        }
        thumbnails.adapter = thumbnailAdapter
        if (document == null) {
            zoom.reset()
            zoom.document = null
            zoom.adapter = null
            adapter = null
            pages.adapter = null
            fastScroll.isVisible = false
        } else {
            applyLayout(document, viewModel.currentPage)
        }
    }

    /** (Re)builds the list for the current view and spread settings, starting at [page]. */
    private fun applyLayout(document: PdfDocument, page: Int) {
        zoom.reset()
        val viewMode = settings.viewMode
        val single = viewMode == ViewMode.SINGLE_PAGE
        pages.layoutManager = LinearLayoutManager(this, if (single) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
        snapHelper.attachToRecyclerView(if (single) pages else null)
        pages.snapHelper = if (single) snapHelper else null

        val spreads = Spreads(document.pageCount, settings.spreadMode)
        adapter = PageAdapter(document, lifecycleScope, spreads, viewMode).also { it.darkPages = settings.darkPages }
        pages.adapter = adapter
        zoom.document = document
        zoom.adapter = adapter

        fastScroll.pageCount = document.pageCount
        fastScroll.isVisible = !single && document.pageCount > 2

        scrollToItem(spreads.itemForPage(page))
        val newAdapter = adapter
        // Fit modes need the list's size, so apply the default once it has been laid out.
        pages.post { if (adapter === newAdapter) zoom.apply(settings.fitMode(viewMode), announce = false) }
    }

    /** Adapter position of the item the reader is on. */
    private fun currentItem(): Int {
        explicitItem?.let { return it }
        val continuous = settings.viewMode == ViewMode.CONTINUOUS
        val itemCount = adapter?.itemCount ?: 0
        // Scrolled to the very end: the last item is fully in view.
        if (continuous && itemCount > 0 && !pages.canScrollVertically(1) && pages.canScrollVertically(-1)) return itemCount - 1
        // Continuous: the item in the upper part of the screen; paged: the one in the middle.
        val probeY = if (continuous) pages.height * 0.25f else pages.height / 2f
        val child = pages.findChildViewUnder(pages.toContentX(pages.width / 2f), pages.toContentY(probeY))
        val position = child?.let { pages.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
        if (position != RecyclerView.NO_POSITION) return position
        return (pages.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
    }

    private fun currentPage(): Int {
        val spreads = adapter?.spreads ?: return 0
        if (spreads.size == 0) return 0
        return spreads.items[currentItem().coerceIn(0, spreads.size - 1)].first
    }

    private fun stepItems(delta: Int) {
        val spreads = adapter?.spreads ?: return
        if (spreads.size == 0) return
        val target = (currentItem() + delta).coerceIn(0, spreads.size - 1)
        scrollToItem(target, smooth = settings.viewMode == ViewMode.SINGLE_PAGE && kotlin.math.abs(delta) == 1)
    }

    private fun goToPage(page: Int) {
        val spreads = adapter?.spreads ?: return
        scrollToItem(spreads.itemForPage(page))
        val newAdapter = adapter
        // Fit modes need the list's size, so apply the default once it has been laid out.
        pages.post { if (adapter === newAdapter) zoom.apply(settings.fitMode(viewMode), announce = false) }
    }

    private fun scrollToItem(item: Int, smooth: Boolean = false) {
        explicitItem = item
        if (smooth) {
            pages.smoothScrollToPosition(item)
        } else {
            (pages.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(item, 0)
        }
        // onScrolled fires after the next layout; also refresh once laid out in case nothing moved.
        pages.post { updatePageLabel() }
    }

    private fun updatePageLabel() {
        val document = shownDocument ?: return
        val spreads = adapter?.spreads ?: return
        if (spreads.size == 0) return
        val item = currentItem().coerceIn(0, spreads.size - 1)
        val range = spreads.items[item]
        viewModel.updatePage(range.first)
        pageLabel.text = if (range.first == range.last) {
            getString(R.string.page_indicator, range.first + 1, document.pageCount)
        } else {
            getString(R.string.page_indicator_spread, range.first + 1, range.last + 1, document.pageCount)
        }
        prevPage.isEnabled = item > 0
        nextPage.isEnabled = item < spreads.size - 1
        prevPage.alpha = if (prevPage.isEnabled) 1f else 0.38f
        nextPage.alpha = if (nextPage.isEnabled) 1f else 0.38f
        fastScroll.fraction = Navigation.fractionForPage(range.first, document.pageCount)
        thumbnailAdapter?.currentPage = range.first
    }

    private fun showGoToPageDialog() {
        val document = shownDocument ?: return
        val input = EditText(this).apply {
            id = R.id.page_input
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
                Navigation.parsePageInput(input.text.toString(), document.pageCount)?.let(::goToPage)
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

    private companion object {
        const val ZOOM_PRESET_ID_BASE = 10_000
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
}
