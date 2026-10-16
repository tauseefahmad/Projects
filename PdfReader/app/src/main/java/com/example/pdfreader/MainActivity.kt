package com.example.pdfreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.appcompat.app.AppCompatDelegate
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
import android.graphics.RectF
import android.view.inputmethod.EditorInfo
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.content.IntentCompat
import com.example.pdfreader.core.Downloads
import com.example.pdfreader.core.Formatting
import com.example.pdfreader.core.Links
import com.example.pdfreader.core.PageRotation
import com.example.pdfreader.core.SearchMatch
import com.example.pdfreader.core.SearchOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    internal lateinit var textSelection: TextSelectionController
    private lateinit var drawer: DrawerLayout
    private lateinit var thumbnails: RecyclerView
    private var thumbnailAdapter: ThumbnailAdapter? = null
    private lateinit var outlineList: RecyclerView
    private lateinit var bookmarkList: RecyclerView
    private lateinit var sideProgress: View
    private lateinit var sideEmpty: TextView
    private lateinit var sideTabs: TabLayout
    private lateinit var annotationsPanel: View
    private val outlineAdapter = OutlineAdapter { node -> node.page?.let { openFromPanel(it) } }
    private lateinit var bookmarkAdapter: BookmarkAdapter
    private var outlineLoaded = false
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var searchCount: TextView
    private lateinit var searchResultsList: RecyclerView
    private var searchMatchCase = false
    private var searchWholeWord = false
    private var searchDebounce: Job? = null
    private var suppressSearchInput = false
    private val searchResultAdapter = SearchResultAdapter(
        textFor = { page -> shownReady?.structure?.cachedPageText(page) },
        onClick = { index ->
            search.select(index)
            drawer.closeDrawer(GravityCompat.START)
        },
    )
    private val search = SearchController(lifecycleScope, object : SearchController.Listener {
        override fun onSearchUpdated() = onSearchChanged()
        override fun onCurrentMatchChanged(match: SearchMatch) = showMatch(match)
    })
    private var shownReady: ReaderState.Ready? = null

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
            // Keep access across restarts so the file can be reopened from "Recent" (and, where
            // the provider allows it, saved in place after organizing pages).
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // Provider doesn't offer persistable access; it still works for this session.
                }
            }
            openUri(uri)
        }
    }

    private val saveCopy = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { destination ->
        val ready = shownReady ?: return@registerForActivityResult
        if (destination == null) return@registerForActivityResult
        lifecycleScope.launch {
            val message = try {
                DocumentExport.saveCopy(this@MainActivity, ready.uri, destination)
                R.string.copy_saved
            } catch (e: Exception) {
                R.string.copy_failed
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private val organize = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.getBooleanExtra(OrganizerActivity.EXTRA_RELOAD, false) == true) {
            viewModel.updatePage(currentPage())
            viewModel.reload()
        }
    }

    /** Whether we may overwrite [uri]: our own files, or a grant that includes writing. */
    private fun canWrite(uri: Uri): Boolean =
        uri.authority == "$packageName.files" ||
            checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val tools = ToolsController(
        activity = this,
        current = {
            shownReady?.let {
                ToolsController.CurrentDocument(it.uri, it.contentUri, it.name, it.contentUri == it.uri && canWrite(it.uri), it.document.pageCount)
            }
        },
        openResult = { uri, name -> viewModel.open(uri, name) },
        reloadCurrent = {
            viewModel.updatePage(currentPage())
            viewModel.reload()
        },
    )

    private val annotate = AnnotateController(
        activity = this,
        viewModelProvider = { viewModel },
        canOverwrite = { it.contentUri == it.uri && canWrite(it.uri) },
        goToPage = { goToPage(it) },
        openPanel = {
            sideTabs.getTabAt(TAB_ANNOTATIONS)?.select()
            openSidePanel()
        },
        onModeChanged = ::onAnnotateModeChanged,
    )

    private val forms = FormController(this) { goToPage(it) }

    /** Keeps the Save button in step with unsaved annotations and form entries. */
    private val saveButtonListener: () -> Unit = { updateSaveButton() }

    private val closeFormOnBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            forms.handleBack()
        }
    }

    /** For tests: the open document's annotations and the annotate-mode controller. */
    internal val annotationSession: AnnotationSession? get() = shownReady?.annotations
    internal val annotateController: AnnotateController get() = annotate
    internal val formController: FormController get() = forms

    private fun openOrganizer() {
        val ready = shownReady ?: return
        // Decrypted copies of protected files can only be saved as a copy, never over the original.
        val inPlace = ready.contentUri == ready.uri && canWrite(ready.uri)
        organize.launch(OrganizerActivity.intent(this, ready.contentUri, ready.name, inPlace))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (settings.theme == AppTheme.SEPIA) setTheme(R.style.Theme_PdfReader_Sepia)
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
        setUpSearch()
        zoom = ZoomController(pages, findViewById(R.id.zoomLabel), lifecycleScope, ::currentItem) { scrollToItem(it) }
        textSelection = TextSelectionController(this, pages, findViewById(R.id.selectionHandles), lifecycleScope) { shownReady?.structure }
        pages.viewportChangedListeners += { textSelection.onViewportChanged() }
        pages.onLongPress = { x, y -> textSelection.startAt(x, y) }
        textSelection.onMarkup = annotate::addMarkup
        pages.onSingleTap = ::onPageTap
        onBackPressedDispatcher.addCallback(this, exitFullScreenOnBack)

        val root = findViewById<View>(R.id.root)
        val sidePanel = findViewById<View>(R.id.sidePanel)
        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            // The keyboard pushes the bottom bars (e.g. the form field bar) up above it.
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            sidePanel.setPadding(bars.left, bars.top, 0, bars.bottom)
            insets
        }

        setUpMenu()
        annotate.attach(pages, drawer)
        annotate.confirmSave = forms::confirmSave
        forms.onSignField = annotate::signField
        forms.attach(pages, drawer)
        forms.onEditingChanged = { editing ->
            closeFormOnBack.isEnabled = editing
            navBar.isVisible = !editing && shownReady != null && !viewModel.fullScreen && !viewModel.annotating
        }
        onBackPressedDispatcher.addCallback(this, closeFormOnBack)

        findViewById<View>(R.id.openButton).setOnClickListener { launchPicker() }
        findViewById<View>(R.id.openUrlButton).setOnClickListener { showOpenUrlDialog() }
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
        // The list holds keyboard focus so page keys reach dispatchKeyEvent. Blocking its
        // children from focus stops Android from consuming the first key press to move focus
        // when leaving touch mode.
        pages.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

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
        shownReady?.annotations?.writeDraftNow()
        if (shownDocument != null) {
            viewModel.updatePage(currentPage())
            viewModel.updateZoom(zoom.state)
            viewModel.savePage()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While typing (search box, dialogs) keys belong to the text field; Esc closes search.
        if (currentFocus is EditText) {
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && searchBar.isVisible) {
                if (event.action == KeyEvent.ACTION_UP) closeSearch()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (annotate.handleKey(event)) return true
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
                        NavAction.SEARCH -> if (event.repeatCount == 0) openSearch()
                        NavAction.ROTATE -> if (event.repeatCount == 0) rotateView()
                        NavAction.FULL_SCREEN -> if (event.repeatCount == 0) setFullScreen(!viewModel.fullScreen)
                        NavAction.EXIT_FULL_SCREEN -> setFullScreen(false)
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
        outlineList = findViewById(R.id.outline)
        bookmarkList = findViewById(R.id.bookmarks)
        sideProgress = findViewById(R.id.sideProgress)
        sideEmpty = findViewById(R.id.sideEmpty)
        annotationsPanel = findViewById(R.id.annotationsPanel)
        outlineList.layoutManager = LinearLayoutManager(this)
        outlineList.adapter = outlineAdapter
        bookmarkAdapter = BookmarkAdapter(
            onOpen = { openFromPanel(it.page) },
            onRename = ::showRenameBookmarkDialog,
            onDelete = { bookmark -> shownReady?.let { setBookmarks(viewModel.bookmarks.toggle(it.uri, bookmark.page, bookmark.title)) } },
        )
        bookmarkList.layoutManager = LinearLayoutManager(this)
        bookmarkList.adapter = bookmarkAdapter

        val tabs = findViewById<TabLayout>(R.id.sideTabs)
        sideTabs = tabs
        // Icon tabs fit the narrow panel; the labels are their spoken descriptions and tooltips.
        for ((icon, label) in listOf(
            R.drawable.ic_grid to R.string.tab_pages,
            R.drawable.ic_toc to R.string.tab_outline,
            R.drawable.ic_bookmark_border to R.string.tab_bookmarks,
            R.drawable.ic_search to R.string.tab_search,
            R.drawable.ic_list to R.string.tab_annotations,
        )) {
            tabs.addTab(tabs.newTab().setIcon(icon).setContentDescription(label))
        }
        searchResultsList = findViewById(R.id.searchResults)
        searchResultsList.layoutManager = LinearLayoutManager(this)
        searchResultsList.adapter = searchResultAdapter
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showSideTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
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

    private fun showSideTab(position: Int) {
        thumbnails.isVisible = position == TAB_PAGES
        outlineList.isVisible = position == TAB_OUTLINE && !outlineAdapter.isEmpty
        bookmarkList.isVisible = position == TAB_BOOKMARKS && bookmarkAdapter.itemCount > 0
        searchResultsList.isVisible = position == TAB_SEARCH && searchResultAdapter.itemCount > 0
        annotationsPanel.isVisible = position == TAB_ANNOTATIONS
        if (position == TAB_ANNOTATIONS) annotate.refreshPanel()
        sideProgress.isVisible = false
        sideEmpty.isVisible = false
        when (position) {
            TAB_OUTLINE -> if (outlineLoaded) showOutlineState() else loadOutline()
            TAB_BOOKMARKS -> if (bookmarkAdapter.itemCount == 0) showSideEmpty(R.string.no_bookmarks)
            TAB_SEARCH -> if (searchResultAdapter.itemCount == 0) {
                showSideEmpty(if (search.query.isEmpty()) R.string.search_results_empty else R.string.no_results)
            }
        }
    }

    private fun showSideEmpty(message: Int) {
        sideEmpty.setText(message)
        sideEmpty.isVisible = true
    }

    private fun loadOutline() {
        val ready = shownReady ?: return
        sideProgress.isVisible = true
        lifecycleScope.launch {
            val outline = try {
                ready.structure.outline()
            } catch (e: Exception) {
                emptyList()
            }
            if (shownReady !== ready) return@launch
            outlineLoaded = true
            outlineAdapter.submit(outline)
            outlineAdapter.showCurrentPage(currentPage())
            if (sideTabs.selectedTabPosition == TAB_OUTLINE) showOutlineState()
        }
    }

    private fun showOutlineState() {
        sideProgress.isVisible = false
        outlineList.isVisible = !outlineAdapter.isEmpty
        if (outlineAdapter.isEmpty) {
            showSideEmpty(R.string.no_outline)
        } else {
            val position = outlineAdapter.positionOfCurrentSection()
            if (position >= 0) (outlineList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, outlineList.height / 3)
        }
    }

    private fun openFromPanel(page: Int) {
        goToPage(page)
        drawer.closeDrawer(GravityCompat.START)
    }

    private fun setBookmarks(bookmarks: List<com.example.pdfreader.core.Bookmark>) {
        bookmarkAdapter.bookmarks = bookmarks
        updateBookmarkButton()
        if (sideTabs.selectedTabPosition == TAB_BOOKMARKS) showSideTab(TAB_BOOKMARKS)
    }

    private fun toggleBookmark() {
        val ready = shownReady ?: return
        val page = currentPage()
        val wasBookmarked = bookmarkAdapter.bookmarks.any { it.page == page }
        setBookmarks(viewModel.bookmarks.toggle(ready.uri, page, getString(R.string.page_thumbnail, page + 1)))
        if (!wasBookmarked) Toast.makeText(this, getString(R.string.bookmark_added, page + 1), Toast.LENGTH_SHORT).show()
    }

    private fun updateBookmarkButton() {
        val item = toolbar.menu.findItem(R.id.action_bookmark) ?: return
        val marked = shownReady != null && bookmarkAdapter.bookmarks.any { it.page == currentPage() }
        item.setIcon(if (marked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_border)
        item.setTitle(if (marked) R.string.remove_bookmark else R.string.add_bookmark)
    }

    private fun showRenameBookmarkDialog(bookmark: com.example.pdfreader.core.Bookmark) {
        val ready = shownReady ?: return
        val input = EditText(this).apply {
            id = R.id.bookmark_title_input
            setText(bookmark.title)
            selectAll()
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_bookmark)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                setBookmarks(viewModel.bookmarks.rename(ready.uri, bookmark.page, input.text.toString().trim()))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun closeSidePanel() = drawer.closeDrawer(GravityCompat.START)

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
                R.id.action_open_url -> showOpenUrlDialog()
                R.id.action_go_to_page -> showGoToPageDialog()
                R.id.action_bookmark -> toggleBookmark()
                R.id.action_rotate -> rotateView()
                R.id.action_organize -> openOrganizer()
                R.id.action_annotate -> annotate.start()
                R.id.action_sign -> annotate.start(AnnotTool.SIGN)
                R.id.action_build_form -> annotate.start(AnnotTool.FIELD)
                R.id.action_save -> shownReady?.let { annotate.save(copy = !(it.contentUri == it.uri && canWrite(it.uri)), flatten = false) }
                R.id.form_autofill -> forms.autofill()
                R.id.form_profile -> forms.editProfile()
                R.id.form_highlight -> item.isChecked = forms.toggleHighlight()
                R.id.form_clear -> forms.clear()
                R.id.form_flatten -> annotate.save(copy = true, flatten = true)
                R.id.tool_merge -> tools.showMerge()
                R.id.tool_split -> tools.showSplit()
                R.id.tool_crop -> tools.showCrop()
                R.id.tool_resize -> tools.showResize()
                R.id.tool_stamp -> tools.showStamp()
                R.id.tool_watermark -> tools.showWatermark()
                R.id.action_full_screen -> {
                    if (annotate.isActive) annotate.finish()
                    if (!annotate.isActive) setFullScreen(true)
                }
                R.id.action_keep_screen_on -> {
                    settings.keepScreenOn = !settings.keepScreenOn
                    updateKeepScreenOn()
                    syncMenuChecks()
                }
                R.id.theme_system -> setAppTheme(AppTheme.SYSTEM)
                R.id.theme_light -> setAppTheme(AppTheme.LIGHT)
                R.id.theme_dark -> setAppTheme(AppTheme.DARK)
                R.id.theme_sepia -> setAppTheme(AppTheme.SEPIA)
                R.id.action_properties -> showProperties()
                R.id.action_share -> shareDocument()
                R.id.action_print -> printDocument()
                R.id.action_save_copy -> shownReady?.let { saveCopy.launch(DocumentExport.safeFileName(it.name)) }
                R.id.action_search -> openSearch()
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
                R.id.action_close -> {
                    viewModel.updatePage(currentPage())
                    viewModel.updateZoom(zoom.state)
                    viewModel.close()
                }
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
        menu.findItem(
            when (settings.theme) {
                AppTheme.SYSTEM -> R.id.theme_system
                AppTheme.LIGHT -> R.id.theme_light
                AppTheme.DARK -> R.id.theme_dark
                AppTheme.SEPIA -> R.id.theme_sepia
            }
        ).isChecked = true
        menu.findItem(R.id.action_volume_keys).isChecked = settings.volumeKeysTurnPages
        menu.findItem(R.id.action_keep_screen_on).isChecked = settings.keepScreenOn
    }

    private fun setAppTheme(theme: AppTheme) {
        if (theme == settings.theme) return
        val wasSepia = settings.theme == AppTheme.SEPIA
        settings.theme = theme
        AppCompatDelegate.setDefaultNightMode(theme.nightMode)
        // Night-mode changes recreate the screen when they change its colors; sepia is a
        // separate theme, so entering or leaving it always needs a fresh screen.
        if (wasSepia || theme == AppTheme.SEPIA) recreate()
    }

    /** Applies a layout setting change while keeping the reader on the same page. */
    private fun changeLayout(update: () -> Unit) {
        val page = if (shownDocument != null) currentPage() else 0
        update()
        syncMenuChecks()
        shownDocument?.let { applyLayout(it, page) }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let(::openUri)
            // "Share" from another app (e.g. a messenger) with a PDF attached.
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::openUri)
        }
    }

    private fun launchPicker() = openDocument.launch(arrayOf("application/pdf"))

    private fun openUri(uri: Uri) = viewModel.open(uri, displayName(uri))

    private fun render(state: ReaderState) {
        progress.isVisible = state is ReaderState.Loading || state is ReaderState.Downloading
        val ready = state as? ReaderState.Ready
        pages.isVisible = ready != null
        navBar.isVisible = ready != null && !viewModel.fullScreen && !viewModel.annotating
        emptyState.isVisible = state is ReaderState.Empty || state is ReaderState.Error || state is ReaderState.NeedsPassword
        toolbar.menu.findItem(R.id.action_go_to_page).isVisible = ready != null
        toolbar.menu.findItem(R.id.action_zoom).isVisible = ready != null
        toolbar.menu.findItem(R.id.action_bookmark).isVisible = ready != null
        toolbar.menu.findItem(R.id.action_search).isVisible = ready != null
        toolbar.menu.findItem(R.id.action_rotate).isVisible = ready != null
        for (id in listOf(R.id.action_annotate, R.id.action_document, R.id.action_full_screen, R.id.action_organize, R.id.action_tools)) {
            toolbar.menu.findItem(id).isVisible = ready != null
        }
        for (id in listOf(R.id.action_annotate, R.id.action_sign, R.id.action_build_form)) {
            toolbar.menu.findItem(id).isVisible = ready != null && !viewModel.annotating
        }
        toolbar.menu.findItem(R.id.form_highlight).isChecked = ProfileStore(this).highlightFields
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
        if (ready !== shownReady) {
            shownReady = ready
            // Full screen survives rotation, but ends when the document closes.
            setFullScreen(ready != null && viewModel.fullScreen)
            textSelection.clear()
            closeSearch()
            search.structure = ready?.structure
            search.pageCount = ready?.document?.pageCount ?: 0
            outlineLoaded = false
            outlineAdapter.submit(emptyList())
            bookmarkAdapter.bookmarks = ready?.let { viewModel.bookmarks.load(it.uri) } ?: emptyList()
            sideTabs.getTabAt(TAB_PAGES)?.select()
            showSideTab(TAB_PAGES)
            updateBookmarkButton()
            shownSession?.listeners?.remove(saveButtonListener)
            shownSession = ready?.annotations
            shownSession?.listeners?.add(saveButtonListener)
            annotate.bind(ready)
            forms.bind(ready)
            toolbar.menu.findItem(R.id.action_form).isVisible = forms.hasForm
            updateSaveButton()
        }

        if (state is ReaderState.Error) {
            MaterialAlertDialogBuilder(this)
                .setMessage(state.message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            viewModel.errorShown()
        }
        if (state is ReaderState.NeedsPassword && state !== passwordPromptFor) showPasswordDialog(state)
    }

    /** The password request a dialog is showing for, so it isn't shown twice. */
    private var passwordPromptFor: ReaderState.NeedsPassword? = null

    private fun showPasswordDialog(request: ReaderState.NeedsPassword) {
        passwordPromptFor = request
        val input = EditText(this).apply {
            id = R.id.password_input
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.password_hint)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.password_title)
            .setMessage(if (request.wrongPassword) R.string.password_wrong else R.string.password_message)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(R.string.open) { _, _ -> viewModel.open(request.uri, request.name, input.text.toString()) }
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.close() }
            .show()
        input.requestFocus()
    }

    private fun showOpenUrlDialog() {
        val input = EditText(this).apply {
            id = R.id.url_input
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.url_hint)
            isSingleLine = true
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_url)
            .setView(container)
            .setPositiveButton(R.string.open) { _, _ ->
                val url = Downloads.parseUrl(input.text.toString())
                if (url == null) {
                    Toast.makeText(this, R.string.url_invalid, Toast.LENGTH_LONG).show()
                } else {
                    viewModel.download(url)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDocument(document: PdfDocument?) {
        shownDocument = document
        explicitItem = null
        thumbnailAdapter = document?.let { doc ->
            ThumbnailAdapter(doc, lifecycleScope) { page ->
                goToPage(page)
                drawer.closeDrawer(GravityCompat.START)
            }.also {
                it.darkPages = settings.darkPages
                it.sepiaPages = settings.theme == AppTheme.SEPIA
                it.rotation = viewModel.viewRotation
            }
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
            pages.requestFocus()
        }
    }

    /** (Re)builds the list for the current view and spread settings, starting at [page]. */
    private fun applyLayout(document: PdfDocument, page: Int) {
        textSelection.clear()
        zoom.reset()
        val viewMode = settings.viewMode
        val single = viewMode == ViewMode.SINGLE_PAGE
        pages.layoutManager = LinearLayoutManager(this, if (single) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
        snapHelper.attachToRecyclerView(if (single) pages else null)
        pages.snapHelper = if (single) snapHelper else null

        val spreads = Spreads(document.pageCount, settings.spreadMode)
        adapter = PageAdapter(document, lifecycleScope, spreads, viewMode).also {
            it.darkPages = settings.darkPages
            it.sepiaPages = settings.theme == AppTheme.SEPIA
            it.highlightProvider = ::highlightsFor
            it.annotationLayer = annotate
            it.formLayer = forms
            it.rotation = viewModel.viewRotation
        }
        pages.adapter = adapter
        zoom.document = document
        zoom.adapter = adapter

        fastScroll.pageCount = document.pageCount
        fastScroll.isVisible = !single && document.pageCount > 2 && !viewModel.annotating

        scrollToItem(spreads.itemForPage(page))
        val newAdapter = adapter
        // Fit modes need the list's size, so apply the default once it has been laid out.
        pages.post {
            if (adapter !== newAdapter) return@post
            // Reopened documents come back at their saved zoom; otherwise use the fit mode.
            val saved = viewModel.zoomToRestore
            viewModel.zoomToRestore = null
            if (saved != null) zoom.restore(saved) else zoom.apply(settings.fitMode(viewMode), announce = false)
        }
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
    }

    private fun scrollToItem(item: Int, smooth: Boolean = false, offsetPx: Int = 0) {
        explicitItem = item
        if (smooth) {
            pages.smoothScrollToPosition(item)
        } else {
            (pages.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(item, offsetPx)
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
        if (outlineLoaded) outlineAdapter.showCurrentPage(range.first)
        updateBookmarkButton()
    }

    private fun showProperties() {
        val ready = shownReady ?: return
        lifecycleScope.launch {
            val properties = try {
                ready.structure.properties()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.error_open, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val fileSize = fileSize(ready.uri)
            val dateFormat = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            val rows = listOfNotNull(
                R.string.prop_file_name to ready.name,
                properties.title?.let { R.string.prop_title to it },
                properties.author?.let { R.string.prop_author to it },
                properties.subject?.let { R.string.prop_subject to it },
                properties.keywords?.let { R.string.prop_keywords to it },
                R.string.prop_pages to properties.pageCount.toString(),
                R.string.prop_page_size to Formatting.pageSize(properties.firstPageWidth, properties.firstPageHeight),
                fileSize?.let { R.string.prop_file_size to Formatting.bytes(it) },
                properties.created?.let { R.string.prop_created to dateFormat.format(it.time) },
                properties.modified?.let { R.string.prop_modified to dateFormat.format(it.time) },
                properties.creator?.let { R.string.prop_creator to it },
                properties.producer?.let { R.string.prop_producer to it },
                R.string.prop_version to properties.version,
                R.string.prop_encrypted to getString(if (properties.encrypted) R.string.yes else R.string.no),
            )
            val density = resources.displayMetrics.density
            val list = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
            }
            for ((label, value) in rows) {
                list.addView(TextView(this@MainActivity).apply {
                    setText(label)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                    setPadding(0, (10 * density).toInt(), 0, 0)
                })
                list.addView(TextView(this@MainActivity).apply {
                    text = value
                    setTextIsSelectable(true)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                })
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.properties)
                .setView(ScrollView(this@MainActivity).apply { addView(list) })
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun fileSize(uri: Uri): Long? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun shareDocument() {
        val ready = shownReady ?: return
        lifecycleScope.launch {
            try {
                val uri = DocumentExport.shareableCopy(this@MainActivity, ready.uri, ready.name)
                startActivity(DocumentExport.shareIntent(this@MainActivity, uri, ready.name))
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.copy_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun printDocument() {
        val ready = shownReady ?: return
        val printManager = getSystemService(android.print.PrintManager::class.java)
        printManager.print(ready.name, PdfPrintAdapter(this, ready.uri, ready.name, ready.document.pageCount), null)
    }

    /** Turns the displayed pages 90° clockwise, staying on the same page. */
    private fun rotateView() {
        val document = shownDocument ?: return
        val page = currentPage()
        viewModel.viewRotation = (viewModel.viewRotation + 90) % 360
        thumbnailAdapter?.rotation = viewModel.viewRotation
        applyLayout(document, page)
    }

    // --- Taps and links ----------------------------------------------------------------------

    private fun onPageTap(x: Float, y: Float) {
        if (textSelection.isActive) {
            textSelection.clear()
            return
        }
        if (!annotate.isActive && forms.onTap(x, y)) return
        if (annotate.onTap(x, y)) return
        val hit = pages.pageViewAt(x, y)
        val structure = shownReady?.structure
        lifecycleScope.launch {
            // Links win over everything else.
            val link = if (hit != null && structure != null) {
                val (view, local) = hit
                val links = try {
                    structure.links(view.page)
                } catch (e: Exception) {
                    emptyList()
                }
                val point = view.toPagePoint(local.x, local.y)
                Links.hit(links, point.x, point.y)
            } else {
                null
            }
            when {
                link != null -> {
                    link.targetPage?.let { goToPage(it) }
                    link.uri?.let(::confirmOpenLink)
                }
                // Full screen: tap the left/right edge to turn pages, the middle to show the controls.
                viewModel.fullScreen -> when {
                    x < pages.width * EDGE_TAP_ZONE -> stepItems(-1)
                    x > pages.width * (1 - EDGE_TAP_ZONE) -> stepItems(1)
                    else -> setFullScreen(false)
                }
            }
        }
    }

    private var shownSession: AnnotationSession? = null

    private fun updateSaveButton() {
        toolbar.menu.findItem(R.id.action_save)?.isVisible = shownSession?.hasChanges == true
    }

    // --- Annotate mode -------------------------------------------------------------------------

    /** The annotation tools replace the page navigation bar while annotating. */
    private fun onAnnotateModeChanged(on: Boolean) {
        if (on) {
            if (viewModel.fullScreen) setFullScreen(false)
            textSelection.clear()
        }
        navBar.isVisible = !on && shownReady != null && !viewModel.fullScreen
        val document = shownDocument
        fastScroll.isVisible = !on && document != null && settings.viewMode != ViewMode.SINGLE_PAGE && document.pageCount > 2
        for (id in listOf(R.id.action_annotate, R.id.action_sign, R.id.action_build_form)) {
            toolbar.menu.findItem(id)?.isVisible = !on && shownReady != null
        }
    }

    // --- Full screen -------------------------------------------------------------------------

    private val exitFullScreenOnBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFullScreen(false)
    }

    /** Immersive reading: hides the app and system bars and keeps the screen awake. */
    private fun setFullScreen(on: Boolean) {
        val enable = on && shownReady != null
        viewModel.fullScreen = enable
        toolbar.isVisible = !enable
        navBar.isVisible = !enable && shownReady != null && !viewModel.annotating
        if (enable) closeSearch()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enable) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        exitFullScreenOnBack.isEnabled = enable
        updateKeepScreenOn()
    }

    private fun updateKeepScreenOn() {
        val keepOn = shownReady != null && (viewModel.fullScreen || settings.keepScreenOn)
        if (keepOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun confirmOpenLink(rawUri: String) {
        val uri = Links.normalize(rawUri)
        if (!Links.isSafeExternal(uri)) {
            Toast.makeText(this, R.string.link_blocked, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_link_title)
            .setMessage(uri)
            .setPositiveButton(R.string.open_link) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (e: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Search ----------------------------------------------------------------------------

    private fun setUpSearch() {
        searchBar = findViewById(R.id.searchBar)
        searchInput = findViewById(R.id.searchInput)
        searchCount = findViewById(R.id.searchCount)
        findViewById<View>(R.id.searchPrev).setOnClickListener { search.previous() }
        findViewById<View>(R.id.searchNext).setOnClickListener { search.next() }
        findViewById<View>(R.id.searchClose).setOnClickListener { closeSearch() }
        findViewById<View>(R.id.searchOptions).setOnClickListener(::showSearchOptions)
        searchInput.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (!enter) return@setOnEditorActionListener false
            searchDebounce?.cancel()
            // Enter on the same query moves to the next match, like desktop viewers.
            if (searchInput.text.toString().trim() == search.query && search.matches.isNotEmpty()) search.next() else runSearch()
            true
        }
        searchInput.doAfterTextChanged {
            if (suppressSearchInput) return@doAfterTextChanged
            searchDebounce?.cancel()
            searchDebounce = lifecycleScope.launch {
                delay(SEARCH_DELAY_MS)
                runSearch()
            }
        }
    }

    private fun openSearch() {
        if (shownReady == null) return
        searchBar.isVisible = true
        searchInput.requestFocus()
        searchInput.selectAll()
        WindowCompat.getInsetsController(window, searchInput).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeSearch() {
        searchDebounce?.cancel()
        if (::searchBar.isInitialized && searchBar.isVisible) {
            WindowCompat.getInsetsController(window, searchInput).hide(WindowInsetsCompat.Type.ime())
            searchBar.isVisible = false
            pages.requestFocus()
        }
        suppressSearchInput = true
        searchInput.setText("")
        suppressSearchInput = false
        if (search.query.isNotEmpty() || search.matches.isNotEmpty()) search.clear()
    }

    private fun runSearch() {
        search.search(searchInput.text.toString(), SearchOptions(searchMatchCase, searchWholeWord), currentPage())
    }

    private fun showSearchOptions(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.search_options, menu)
            menu.findItem(R.id.option_match_case).isChecked = searchMatchCase
            menu.findItem(R.id.option_whole_word).isChecked = searchWholeWord
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.option_match_case -> searchMatchCase = !searchMatchCase
                    R.id.option_whole_word -> searchWholeWord = !searchWholeWord
                }
                runSearch()
                true
            }
        }.show()
    }

    private fun onSearchChanged() {
        searchCount.text = when {
            search.query.isEmpty() -> ""
            search.matches.isEmpty() -> getString(if (search.searching) R.string.searching else R.string.no_results)
            search.searching -> getString(R.string.search_count_progress, search.currentIndex + 1, search.matches.size)
            else -> getString(R.string.search_count, search.currentIndex + 1, search.matches.size)
        }
        searchResultAdapter.submit(search.matches, search.currentIndex)
        if (sideTabs.selectedTabPosition == TAB_SEARCH) showSideTab(TAB_SEARCH)
        adapter?.refreshHighlights()
    }

    /** Scrolls so the match sits about a third of the way down the screen. */
    private fun showMatch(match: SearchMatch) {
        val adapter = adapter ?: return
        val ready = shownReady ?: return
        val item = adapter.spreads.itemForPage(match.page)
        if (settings.viewMode == ViewMode.SINGLE_PAGE) {
            scrollToItem(item)
            return
        }
        lifecycleScope.launch {
            val boxes = try {
                ready.structure.pageTextWithPositions(match.page).boxesFor(match.start, match.end)
            } catch (e: Exception) {
                emptyList()
            }
            if (this@MainActivity.adapter !== adapter) return@launch
            val box = boxes.firstOrNull()
            if (box == null) {
                scrollToItem(item)
                return@launch
            }
            val slot = adapter.spreads.items[item].indexOf(match.page).coerceAtLeast(0)
            val pageHeightPx = adapter.baseBoxes(item)[slot].height * adapter.layoutScale
            val size = ready.document.pageSizes[match.page]
            val turn = PageRotation(size.width.toFloat(), size.height.toFloat(), adapter.rotation)
            val top = turn.rectToDisplay(box.left, box.top, box.right, box.bottom)[1]
            val y = adapter.rowPaddingTop() + top / turn.displayHeight * pageHeightPx
            scrollToItem(item, offsetPx = (pages.height / 3 - y).toInt())
        }
    }

    private suspend fun highlightsFor(page: Int): Pair<List<RectF>, List<RectF>> {
        val onPage = search.matchesOn(page)
        val structure = shownReady?.structure
        if (onPage.isEmpty() || structure == null) return emptyList<RectF>() to emptyList()
        val text = structure.pageTextWithPositions(page)
        val current = search.current?.takeIf { it.page == page }
        val others = onPage.filter { it != current }.flatMap { text.boxesFor(it.start, it.end) }
        return others to (current?.let { text.boxesFor(it.start, it.end) } ?: emptyList())
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
        const val TAB_PAGES = 0
        const val TAB_OUTLINE = 1
        const val TAB_BOOKMARKS = 2
        const val TAB_SEARCH = 3
        const val TAB_ANNOTATIONS = 4
        const val SEARCH_DELAY_MS = 350L
        const val EDGE_TAP_ZONE = 0.25f
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
