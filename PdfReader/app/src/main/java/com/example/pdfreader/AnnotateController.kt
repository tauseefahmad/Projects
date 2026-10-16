package com.example.pdfreader

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.pdfreader.core.Annotation
import com.example.pdfreader.core.AnnotationFilter
import com.example.pdfreader.core.AnnotationSummary
import com.example.pdfreader.core.AnnotationTransform
import com.example.pdfreader.core.Box
import com.example.pdfreader.core.Geometry
import com.example.pdfreader.core.MarkupKind
import com.example.pdfreader.core.PalmRejection
import com.example.pdfreader.core.PenPressure
import com.example.pdfreader.core.PointerKind
import com.example.pdfreader.core.Pt
import com.example.pdfreader.core.ShapeKind
import com.example.pdfreader.core.StampKind
import com.example.pdfreader.core.typeName
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.hypot

/** The annotation tools, in toolbar order. */
enum class AnnotTool(val icon: Int, val label: Int) {
    SELECT(R.drawable.ic_pointer, R.string.tool_select),
    HIGHLIGHT(R.drawable.ic_highlight, R.string.tool_highlight),
    UNDERLINE(R.drawable.ic_underline, R.string.tool_underline),
    STRIKEOUT(R.drawable.ic_strikeout, R.string.tool_strikeout),
    SQUIGGLY(R.drawable.ic_squiggly, R.string.tool_squiggly),
    PEN(R.drawable.ic_edit, R.string.tool_pen),
    HIGHLIGHTER(R.drawable.ic_brush, R.string.tool_highlighter),
    ERASER(R.drawable.ic_eraser, R.string.tool_eraser),
    TEXT(R.drawable.ic_text_box, R.string.tool_text),
    NOTE(R.drawable.ic_note, R.string.tool_note),
    RECTANGLE(R.drawable.ic_shape_rect, R.string.tool_rectangle),
    ELLIPSE(R.drawable.ic_shape_ellipse, R.string.tool_ellipse),
    LINE(R.drawable.ic_shape_line, R.string.tool_line),
    ARROW(R.drawable.ic_shape_arrow, R.string.tool_arrow),
    STAMP(R.drawable.ic_stamp, R.string.tool_stamp),
    IMAGE(R.drawable.ic_image, R.string.tool_image);

    val markup: MarkupKind?
        get() = when (this) {
            HIGHLIGHT -> MarkupKind.HIGHLIGHT
            UNDERLINE -> MarkupKind.UNDERLINE
            STRIKEOUT -> MarkupKind.STRIKEOUT
            SQUIGGLY -> MarkupKind.SQUIGGLY
            else -> null
        }

    val shape: ShapeKind?
        get() = when (this) {
            RECTANGLE -> ShapeKind.RECTANGLE
            ELLIPSE -> ShapeKind.ELLIPSE
            LINE -> ShapeKind.LINE
            ARROW -> ShapeKind.ARROW
            else -> null
        }

    /** Whether the line-width (or font-size) control applies. */
    val hasWidth get() = this == PEN || this == HIGHLIGHTER || this == TEXT || shape != null

    companion object {
        fun forMarkup(kind: MarkupKind) = entries.first { it.markup == kind }
    }
}

/** Remembered tool choices: color and width per tool, the author name, finger drawing. */
class AnnotationPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)

    fun color(tool: AnnotTool): Int = prefs.getInt("annot_color_${tool.name}", defaultColor(tool))
    fun setColor(tool: AnnotTool, color: Int) = prefs.edit().putInt("annot_color_${tool.name}", color).apply()

    fun width(tool: AnnotTool): Float = prefs.getFloat("annot_width_${tool.name}", defaultWidth(tool))
    fun setWidth(tool: AnnotTool, width: Float) = prefs.edit().putFloat("annot_width_${tool.name}", width).apply()

    var author: String
        get() = prefs.getString("annot_author", "").orEmpty()
        set(value) = prefs.edit().putString("annot_author", value).apply()

    var fingerDrawing: Boolean
        get() = prefs.getBoolean("annot_finger_drawing", false)
        set(value) = prefs.edit().putBoolean("annot_finger_drawing", value).apply()

    var tool: AnnotTool
        get() = runCatching { AnnotTool.valueOf(prefs.getString("annot_tool", "") ?: "") }.getOrDefault(AnnotTool.PEN)
        set(value) = prefs.edit().putString("annot_tool", value.name).apply()

    companion object {
        val PALETTE = intArrayOf(
            0xFFFFEB3B.toInt(), 0xFF8BC34A.toInt(), 0xFF4FC3F7.toInt(), 0xFFF48FB1.toInt(),
            0xFFF44336.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFF8E24AA.toInt(),
            0xFFFF9800.toInt(), 0xFF000000.toInt(), 0xFF757575.toInt(), 0xFFFFFFFF.toInt(),
        )

        fun defaultColor(tool: AnnotTool): Int = when (tool) {
            AnnotTool.HIGHLIGHT, AnnotTool.HIGHLIGHTER, AnnotTool.NOTE -> 0xFFFFEB3B.toInt()
            AnnotTool.UNDERLINE, AnnotTool.PEN -> 0xFF1E88E5.toInt()
            AnnotTool.TEXT -> 0xFF000000.toInt()
            AnnotTool.STAMP -> 0xFF43A047.toInt()
            else -> 0xFFF44336.toInt()
        }

        fun defaultWidth(tool: AnnotTool): Float = when (tool) {
            AnnotTool.HIGHLIGHTER -> 12f
            AnnotTool.TEXT -> 12f
            else -> 2f
        }
    }
}

/**
 * Annotate mode: the tool bar under the pages, drawing with the pen or a finger, selecting,
 * moving and resizing annotations, the annotation panel, and saving into the PDF.
 */
class AnnotateController(
    private val activity: AppCompatActivity,
    /** The screen's view model; a function because it isn't available while the activity is constructed. */
    private val viewModelProvider: () -> ReaderViewModel,
    /** Whether the file itself may be overwritten (otherwise saving makes a copy). */
    private val canOverwrite: (ReaderState.Ready) -> Boolean,
    private val goToPage: (Int) -> Unit,
    private val openPanel: () -> Unit,
    /** Annotate mode turned on or off; the screen hides or shows its reading controls. */
    private val onModeChanged: (Boolean) -> Unit,
) : ZoomableRecyclerView.PenHandler, PageView.AnnotationLayer {

    private val context: Context get() = activity
    private val viewModel: ReaderViewModel get() = viewModelProvider()
    private val prefs by lazy { AnnotationPrefs(activity) }
    private val palm = PalmRejection()

    private lateinit var pages: ZoomableRecyclerView
    private lateinit var bar: View
    private lateinit var toolRow: LinearLayout
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton
    private lateinit var colorButton: View
    private lateinit var colorSwatch: View
    private lateinit var widthButton: ImageButton
    private lateinit var selectionActions: View
    private lateinit var editTextButton: View
    private lateinit var panelFilters: ChipGroup
    private lateinit var panelList: RecyclerView
    private lateinit var panelEmpty: TextView
    private val toolButtons = mutableMapOf<AnnotTool, ImageButton>()

    private var ready: ReaderState.Ready? = null
    private val session: AnnotationSession? get() = ready?.annotations
    private val sessionListener: () -> Unit = { onAnnotationsChanged() }

    val isActive: Boolean get() = viewModel.annotating
    var tool: AnnotTool = AnnotTool.PEN
        private set

    private var selectedId: String? = null
    private var filter = AnnotationFilter()

    // Stamp and image waiting to be placed.
    private var stampKind = StampKind.APPROVED
    private var stampLabel = StampKind.APPROVED.label
    private var pendingImage: Triple<File, Int, Int>? = null

    private val panelAdapter = AnnotationAdapter(
        onOpen = { a ->
            goToPage(a.page)
            if (isActive) select(a.id)
            (activity as? MainActivity)?.closeSidePanel()
        },
        onDelete = { a -> session?.remove(a.id) },
    )

    private val saveCopyLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) finishSaveCopy(uri, viewModel.pendingFlattenCopy)
    }

    private val pickImage = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            if (pendingImage == null) setTool(AnnotTool.SELECT)
            return@registerForActivityResult
        }
        activity.lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) { runCatching { AnnotationSession.importImage(context, uri) }.getOrNull() }
            if (imported == null) {
                Toast.makeText(context, R.string.image_failed, Toast.LENGTH_LONG).show()
                if (pendingImage == null) setTool(AnnotTool.SELECT)
            } else {
                pendingImage = imported
                Toast.makeText(context, R.string.tap_to_place_image, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val exitOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = finish()
    }

    // --- Setup ------------------------------------------------------------------------------

    fun attach(pages: ZoomableRecyclerView, root: View) {
        this.pages = pages
        bar = root.findViewById(R.id.annotateBar)
        toolRow = root.findViewById(R.id.annotTools)
        undoButton = root.findViewById(R.id.annotUndo)
        redoButton = root.findViewById(R.id.annotRedo)
        colorButton = root.findViewById(R.id.annotColor)
        colorSwatch = root.findViewById(R.id.annotColorSwatch)
        widthButton = root.findViewById(R.id.annotWidth)
        selectionActions = root.findViewById(R.id.annotSelectionActions)
        editTextButton = root.findViewById(R.id.annotEditText)
        panelFilters = root.findViewById(R.id.annotationFilters)
        panelList = root.findViewById(R.id.annotationList)
        panelEmpty = root.findViewById(R.id.annotationsEmpty)
        panelList.layoutManager = LinearLayoutManager(context)
        panelList.adapter = panelAdapter

        for (t in AnnotTool.entries) {
            val size = (48 * context.resources.displayMetrics.density).toInt()
            val button = ImageButton(context).apply {
                setImageResource(t.icon)
                setBackgroundResource(R.drawable.tool_bg)
                contentDescription = context.getString(t.label)
                TooltipCompat.setTooltipText(this, contentDescription)
                layoutParams = LinearLayout.LayoutParams(size, size)
                setOnClickListener { chooseTool(t) }
            }
            toolButtons[t] = button
            toolRow.addView(button)
        }
        undoButton.setOnClickListener { session?.undo() }
        redoButton.setOnClickListener { session?.redo() }
        colorButton.setOnClickListener { showColorPicker() }
        TooltipCompat.setTooltipText(colorButton, colorButton.contentDescription)
        widthButton.setOnClickListener { showWidthPicker() }
        root.findViewById<View>(R.id.annotComment).setOnClickListener { selected?.let(::editComment) }
        editTextButton.setOnClickListener { (selected as? Annotation.FreeText)?.let(::editFreeText) }
        root.findViewById<View>(R.id.annotDelete).setOnClickListener { deleteSelected() }
        root.findViewById<View>(R.id.annotList).setOnClickListener { openPanel() }
        root.findViewById<View>(R.id.annotMore).setOnClickListener(::showMoreMenu)
        root.findViewById<View>(R.id.annotDone).setOnClickListener { finish() }
        root.findViewById<View>(R.id.exportAnnotations).setOnClickListener { exportSummary() }
        activity.onBackPressedDispatcher.addCallback(activity, exitOnBack)
        palm.fingerDrawing = prefs.fingerDrawing
        tool = prefs.tool
    }

    /** The open document changed (opened, reloaded or closed). */
    fun bind(newReady: ReaderState.Ready?) {
        session?.listeners?.remove(sessionListener)
        ready = newReady
        selectedId = null
        clearGesture()
        val session = newReady?.annotations
        session?.listeners?.add(sessionListener)
        if (session == null) {
            if (isActive) setActive(false)
        } else {
            applyMode()
        }
        refreshPanel()
        session?.pendingDraft?.let { draft -> offerDraft(session, draft.size) }
    }

    private fun offerDraft(session: AnnotationSession, count: Int) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.restore_draft_title)
            .setMessage(context.resources.getQuantityString(R.plurals.restore_draft_message, count, count))
            .setCancelable(false)
            .setPositiveButton(R.string.restore) { _, _ ->
                session.restoreDraft()
                setActive(true)
            }
            .setNegativeButton(R.string.discard) { _, _ -> session.discardDraft() }
            .show()
    }

    // --- Mode -------------------------------------------------------------------------------

    fun start() {
        if (session == null) {
            Toast.makeText(context, R.string.annotations_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        setActive(true)
    }

    private fun setActive(on: Boolean) {
        viewModel.annotating = on && session != null
        if (!isActive) {
            selectedId = null
            clearGesture()
        }
        applyMode()
    }

    private fun applyMode() {
        val on = isActive
        bar.isVisible = on
        pages.penHandler = if (on) this else null
        exitOnBack.isEnabled = on
        onModeChanged(on)
        updateBar()
        invalidatePages()
    }

    /** Leaves annotate mode, offering to save first. */
    fun finish() {
        val session = session
        if (session == null || !session.hasChanges) {
            setActive(false)
            return
        }
        val ready = ready ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.save_changes_title)
            .setMessage(R.string.save_changes_message)
            .setPositiveButton(R.string.save) { _, _ ->
                setActive(false)
                save(copy = !canOverwrite(ready), flatten = false)
            }
            .setNeutralButton(R.string.save_copy) { _, _ ->
                setActive(false)
                save(copy = true, flatten = false)
            }
            .setNegativeButton(R.string.not_now) { _, _ ->
                session.writeDraftNow()
                setActive(false)
            }
            .show()
    }

    private fun chooseTool(t: AnnotTool) {
        when (t) {
            AnnotTool.STAMP -> showStampPicker()
            AnnotTool.IMAGE -> {
                setTool(t)
                pickImage.launch("image/*")
            }
            else -> setTool(t)
        }
    }

    fun setTool(t: AnnotTool) {
        tool = t
        prefs.tool = t
        if (t != AnnotTool.SELECT) selectedId = null
        updateBar()
        invalidatePages()
    }

    private fun updateBar() {
        if (!::bar.isInitialized) return
        for ((t, button) in toolButtons) button.isSelected = t == tool
        val session = session
        undoButton.isEnabled = session?.history?.canUndo == true
        redoButton.isEnabled = session?.history?.canRedo == true
        undoButton.alpha = if (undoButton.isEnabled) 1f else 0.38f
        redoButton.alpha = if (redoButton.isEnabled) 1f else 0.38f
        val selected = selected
        selectionActions.isVisible = selected != null
        editTextButton.isVisible = selected is Annotation.FreeText
        val color = selected?.color ?: prefs.color(tool)
        colorButton.isVisible = selected?.let { it !is Annotation.Image } ?: (tool != AnnotTool.SELECT && tool != AnnotTool.ERASER && tool != AnnotTool.IMAGE)
        colorSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((1.5f * context.resources.displayMetrics.density).toInt(), 0x80000000.toInt())
        }
        widthButton.isVisible = selected?.let { it is Annotation.Ink || it is Annotation.Shape || it is Annotation.FreeText } ?: tool.hasWidth
    }

    private fun onAnnotationsChanged() {
        if (selectedId != null && session?.history?.find(selectedId!!) == null) selectedId = null
        updateBar()
        invalidatePages()
        refreshPanel()
    }

    private fun invalidatePages() {
        if (::pages.isInitialized) pages.forEachPageView { it.invalidate() }
    }

    // --- AnnotationLayer -------------------------------------------------------------------

    /** Annotations hidden while being erased or dragged (the preview shows the moved one). */
    private val hidden = mutableSetOf<String>()

    override fun annotationsOn(page: Int): List<Annotation> {
        val items = session?.items ?: return emptyList()
        return items.filter { it.page == page && it.id !in hidden }
    }

    private var previewAnnotation: Annotation? = null
    override val preview: Annotation? get() = previewAnnotation

    override val selected: Annotation?
        get() = if (isActive) selectedId?.let { session?.history?.find(it) } else null

    private fun select(id: String?) {
        selectedId = id
        if (id != null && tool != AnnotTool.SELECT) {
            tool = AnnotTool.SELECT
            prefs.tool = tool
        }
        updateBar()
        invalidatePages()
    }

    // --- Pen input -------------------------------------------------------------------------

    private enum class Gesture { INK, SHAPE, MARKUP, ERASE, MOVE, RESIZE, TEXT_BOX, PLACE }

    private var gesture: Gesture? = null
    private var gestureView: PageView? = null
    private var gesturePage = -1
    private var gestureKind = PointerKind.FINGER
    private var startScreen = Pt(0f, 0f)
    private var start = Pt(0f, 0f)
    private var current = Pt(0f, 0f)
    private var moved = false
    private val points = mutableListOf<Pt>()
    private val pressures = mutableListOf<Float>()
    private var original: Annotation? = null
    private var wasSelected = false
    private var markupText: PageText? = null

    private fun pagePoint(view: PageView, screenX: Float, screenY: Float): Pt {
        val local = pages.localPointIn(view, screenX, screenY)
        val p = view.toPagePoint(local.x, local.y)
        val r = view.pageRotation
        return if (r == null) Pt(p.x, p.y) else Pt(p.x.coerceIn(0f, r.width), p.y.coerceIn(0f, r.height))
    }

    /** About [dp] screen pixels, in page points at the current zoom. */
    private fun pointsFor(view: PageView, dp: Float) =
        dp * context.resources.displayMetrics.density / (view.scale * pages.zoom).coerceAtLeast(0.01f)

    override fun onPenDown(x: Float, y: Float, pressure: Float, kind: PointerKind): Boolean {
        val session = session ?: return false
        if (!palm.draws(kind)) return false
        val (view, _) = pages.pageViewAt(x, y) ?: return false
        if (view.pageRotation == null) return false
        val effective = if (kind == PointerKind.STYLUS_ERASER) AnnotTool.ERASER else tool
        gestureView = view
        gesturePage = view.page
        gestureKind = kind
        startScreen = Pt(x, y)
        start = pagePoint(view, x, y)
        current = start
        moved = false
        points.clear()
        pressures.clear()
        gesture = when {
            effective == AnnotTool.SELECT -> {
                val sel = selected?.takeIf { it.page == view.page }
                val local = pages.localPointIn(view, x, y)
                val frame = sel?.let { view.selectionFrame(it) }
                if (sel != null && frame != null && AnnotationTransform.canResize(sel) &&
                    hypot(local.x - frame.right, local.y - frame.bottom) <= view.handleRadius * 1.8f
                ) {
                    original = sel
                    Gesture.RESIZE
                } else {
                    val hit = session.history.hit(view.page, start.x, start.y, pointsFor(view, 8f)) ?: return false.also { gestureView = null }
                    wasSelected = hit.id == selectedId
                    original = hit
                    select(hit.id)
                    Gesture.MOVE
                }
            }
            effective == AnnotTool.ERASER -> {
                eraseAt(start, view)
                Gesture.ERASE
            }
            effective == AnnotTool.PEN || effective == AnnotTool.HIGHLIGHTER -> {
                points += start
                if (kind == PointerKind.STYLUS) pressures += pressure
                Gesture.INK
            }
            effective.markup != null -> {
                loadMarkupText(view.page)
                Gesture.MARKUP
            }
            effective.shape != null -> Gesture.SHAPE
            effective == AnnotTool.TEXT -> Gesture.TEXT_BOX
            else -> Gesture.PLACE
        }
        if (selectedId != null && gesture != Gesture.MOVE && gesture != Gesture.RESIZE) {
            selectedId = null
            updateBar()
        }
        invalidatePages()
        return true
    }

    override fun onPenMove(x: Float, y: Float, pressure: Float) {
        val view = gestureView ?: return
        if (!moved && hypot(x - startScreen.x, y - startScreen.y) < TOUCH_SLOP_DP * context.resources.displayMetrics.density) return
        moved = true
        val p = pagePoint(view, x, y)
        current = p
        when (gesture) {
            Gesture.INK -> {
                if (Geometry.distance(p.x, p.y, points.last()) >= pointsFor(view, 1f)) {
                    points += p
                    if (gestureKind == PointerKind.STYLUS) pressures += pressure
                }
                previewAnnotation = newInk(points.toList())
            }
            Gesture.SHAPE -> previewAnnotation = newShape(start, p)
            Gesture.MARKUP -> previewAnnotation = newMarkup(start, p)
            Gesture.ERASE -> eraseAt(p, view)
            Gesture.MOVE -> previewAnnotation = original?.moved(p.x - start.x, p.y - start.y)?.also { hidden.add(it.id) }
            Gesture.RESIZE -> previewAnnotation = original?.let { resizedTo(it, view, x, y) }?.also { hidden.add(it.id) }
            Gesture.TEXT_BOX -> previewAnnotation = Annotation.Shape(
                PREVIEW_ID, view.page, 0xFF757575.toInt(), "", 0, "", ShapeKind.RECTANGLE, listOf(start, p), 0.75f,
            )
            Gesture.PLACE, null -> Unit
        }
        view.invalidate()
    }

    override fun onPenUp(x: Float, y: Float) {
        val view = gestureView
        val session = session
        val g = gesture
        if (view == null || session == null || g == null) {
            clearGesture()
            return
        }
        when (g) {
            Gesture.INK -> {
                val stroke = Geometry.smooth(points.toList())
                session.add(newInk(stroke).let {
                    if (pressures.isNotEmpty()) it.copy(width = PenPressure.width(it.width, pressures)) else it
                })
            }
            Gesture.SHAPE -> if (moved) {
                val shape = newShape(start, current)
                if (shape.bounds.width > 3 || shape.bounds.height > 3) session.add(shape)
            }
            Gesture.MARKUP -> {
                val markup = if (markupText != null) newMarkup(start, current) else null
                if (markup != null && markup.lines.isNotEmpty()) session.add(markup)
                else if (markupText != null) Toast.makeText(context, R.string.no_text_here, Toast.LENGTH_SHORT).show()
            }
            Gesture.ERASE -> if (hidden.isNotEmpty()) session.removeAll(hidden.toSet())
            Gesture.MOVE -> {
                val moved = previewAnnotation
                if (this.moved && moved != null) {
                    session.replace(moved)
                } else if (wasSelected) {
                    // Tapping the selected annotation again opens its text or comment.
                    original?.let { a -> if (a is Annotation.FreeText) editFreeText(a) else editComment(a) }
                } else {
                    (original as? Annotation.Note)?.let(::editComment)
                }
            }
            Gesture.RESIZE -> previewAnnotation?.let { session.replace(it) }
            Gesture.TEXT_BOX -> {
                val box = if (moved) AnnotationTransform.boxOf(start, current).takeIf { it.width > 12 && it.height > 8 } else null
                createFreeText(view.page, start, box)
            }
            Gesture.PLACE -> place(view, start)
        }
        clearGesture()
    }

    override fun onPenCancel() = clearGesture()

    private fun clearGesture() {
        gesture = null
        gestureView = null
        previewAnnotation = null
        original = null
        hidden.clear()
        markupText = null
        invalidatePages()
    }

    private fun eraseAt(p: Pt, view: PageView) {
        val history = session?.history ?: return
        val slop = pointsFor(view, 6f)
        val hit = history.items.lastOrNull { it.page == view.page && it.id !in hidden && history.hits(it, p.x, p.y, slop) } ?: return
        hidden += hit.id
    }

    /** Resizes so the selection frame's corner follows the finger (works in rotated views). */
    private fun resizedTo(a: Annotation, view: PageView, screenX: Float, screenY: Float): Annotation {
        val b = a.bounds
        val frame = view.toLocalRect(RectF(b.left, b.top, b.right, b.bottom))
        val local = pages.localPointIn(view, screenX, screenY)
        val corner1 = view.toPagePoint(frame.left, frame.top)
        val corner2 = view.toPagePoint(local.x.coerceAtLeast(frame.left + 1), local.y.coerceAtLeast(frame.top + 1))
        return AnnotationTransform.resized(a, AnnotationTransform.boxOf(Pt(corner1.x, corner1.y), Pt(corner2.x, corner2.y)))
    }

    private fun loadMarkupText(page: Int) {
        val structure = ready?.structure ?: return
        activity.lifecycleScope.launch {
            val text = try {
                structure.pageTextWithPositions(page)
            } catch (e: Exception) {
                PageText.EMPTY
            }
            if (gesture != Gesture.MARKUP || gesturePage != page) return@launch
            markupText = text
            if (moved) {
                previewAnnotation = newMarkup(start, current)
                gestureView?.invalidate()
            }
        }
    }

    // --- Creating annotations ------------------------------------------------------------

    private fun now() = System.currentTimeMillis()

    private fun newInk(stroke: List<Pt>): Annotation.Ink {
        val highlighter = tool == AnnotTool.HIGHLIGHTER && gestureKind != PointerKind.STYLUS_ERASER
        val t = if (highlighter) AnnotTool.HIGHLIGHTER else AnnotTool.PEN
        return Annotation.Ink(
            AnnotationIO.newId(), gesturePage, prefs.color(t), prefs.author, now(),
            strokes = listOf(stroke), width = prefs.width(t), opacity = if (highlighter) 0.4f else 1f,
        )
    }

    private fun newShape(a: Pt, b: Pt): Annotation.Shape {
        val kind = tool.shape ?: ShapeKind.RECTANGLE
        return Annotation.Shape(AnnotationIO.newId(), gesturePage, prefs.color(tool), prefs.author, now(), kind = kind, points = listOf(a, b), width = prefs.width(tool))
    }

    private fun newMarkup(a: Pt, b: Pt): Annotation.Markup? {
        val text = markupText ?: return null
        val kind = tool.markup ?: return null
        val i = com.example.pdfreader.core.TextGeometry.indexAt(text.boxes, a.x, a.y)
        val j = com.example.pdfreader.core.TextGeometry.indexAt(text.boxes, b.x, b.y)
        if (i < 0 || j < 0) return null
        val range = com.example.pdfreader.core.TextGeometry.rangeBetween(i, j)
        return markupFor(gesturePage, kind, text.boxesFor(range.first, range.last + 1), text.text.substring(range.first, (range.last + 1).coerceAtMost(text.text.length)))
    }

    private fun markupFor(page: Int, kind: MarkupKind, rects: List<RectF>, text: String): Annotation.Markup? {
        if (rects.isEmpty()) return null
        val color = prefs.color(AnnotTool.forMarkup(kind))
        return Annotation.Markup(
            AnnotationIO.newId(), page, color, prefs.author, now(), "", kind,
            rects.map { Box(it.left, it.top, it.right, it.bottom) }, text.trim(),
        )
    }

    /** Adds a highlight (or other markup) over text selected in the reader. */
    fun addMarkup(page: Int, kind: MarkupKind, rects: List<RectF>, text: String) {
        val session = session ?: return
        val markup = markupFor(page, kind, rects, text) ?: return
        session.add(markup)
        if (!isActive) setActive(true)
        select(markup.id)
    }

    private fun createFreeText(page: Int, at: Pt, box: Box?) {
        textDialog(R.string.tool_text, "", R.string.text_hint) { text ->
            if (text.isBlank()) return@textDialog
            val size = prefs.width(AnnotTool.TEXT)
            val width = box?.width ?: DEFAULT_TEXT_WIDTH
            val layout = AnnotationPainter.textLayout(text, size, 0, width - 4)
            val height = maxOf(box?.height ?: 0f, layout.height + size * 0.6f)
            val a = Annotation.FreeText(
                AnnotationIO.newId(), page, prefs.color(AnnotTool.TEXT), prefs.author, now(),
                box = Box(box?.left ?: at.x, box?.top ?: at.y, (box?.left ?: at.x) + width, (box?.top ?: at.y) + height),
                text = text, fontSize = size,
            )
            session?.add(a)
            select(a.id)
        }
    }

    private fun place(view: PageView, at: Pt) {
        val session = session ?: return
        val r = view.pageRotation ?: return
        fun fit(w: Float, h: Float): Box {
            val left = (at.x - w / 2).coerceIn(0f, (r.width - w).coerceAtLeast(0f))
            val top = (at.y - h / 2).coerceIn(0f, (r.height - h).coerceAtLeast(0f))
            return Box(left, top, left + w, top + h)
        }
        when (tool) {
            AnnotTool.NOTE -> textDialog(R.string.tool_note, "", R.string.note_hint) { text ->
                val size = Annotation.Note.ICON_SIZE
                val box = fit(size, size)
                val note = Annotation.Note(AnnotationIO.newId(), view.page, prefs.color(AnnotTool.NOTE), prefs.author, now(), text, Pt(box.left, box.top))
                session.add(note)
                select(note.id)
            }
            AnnotTool.STAMP -> {
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
                val subtitle = listOf(date, prefs.author).filter { it.isNotBlank() }.joinToString(" · ")
                val stamp = Annotation.Stamp(
                    AnnotationIO.newId(), view.page, stampColor(), prefs.author, now(),
                    box = fit(STAMP_WIDTH, STAMP_HEIGHT), kind = stampKind, label = stampLabel, subtitle = subtitle,
                )
                session.add(stamp)
                select(stamp.id)
            }
            AnnotTool.IMAGE -> {
                val (file, w, h) = pendingImage ?: run {
                    pickImage.launch("image/*")
                    return
                }
                val width = minOf(IMAGE_WIDTH, r.width * 0.8f)
                val height = width * h / w.coerceAtLeast(1)
                val image = Annotation.Image(AnnotationIO.newId(), view.page, 0, prefs.author, now(), box = fit(width, height), imagePath = file.path)
                pendingImage = null
                session.add(image)
                select(image.id)
            }
            else -> Unit
        }
    }

    private fun stampColor() = when (stampKind) {
        StampKind.APPROVED, StampKind.PAID -> 0xFF2E7D32.toInt()
        StampKind.DRAFT -> 0xFF1565C0.toInt()
        StampKind.CONFIDENTIAL -> 0xFFC62828.toInt()
        StampKind.CUSTOM -> prefs.color(AnnotTool.STAMP)
    }

    // --- Taps and keys -----------------------------------------------------------------------

    /**
     * A tap that no pen gesture took. In annotate mode it clears the selection; while reading,
     * tapping a sticky note or commented annotation shows its comment. Returns true if handled.
     */
    fun onTap(x: Float, y: Float): Boolean {
        val session = session ?: return false
        if (isActive) {
            if (selectedId != null) {
                select(null)
                return true
            }
            return false
        }
        val (view, local) = pages.pageViewAt(x, y) ?: return false
        val p = view.toPagePoint(local.x, local.y)
        val hit = session.history.hit(view.page, p.x, p.y, pointsFor(view, 8f)) ?: return false
        if (hit !is Annotation.Note && hit.note.isBlank()) return false
        showInfo(hit)
        return true
    }

    private fun showInfo(a: Annotation) {
        val date = a.modified.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
        val by = listOfNotNull(a.author.takeIf { it.isNotBlank() }, date).joinToString(" · ")
        MaterialAlertDialogBuilder(context)
            .setTitle(a.typeName())
            .setMessage(listOf(a.note.ifBlank { AnnotationSummary.content(a) }, by).filter { it.isNotBlank() }.joinToString("\n\n"))
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.edit) { _, _ ->
                setActive(true)
                select(a.id)
            }
            .show()
    }

    /** Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z) undo and redo; Delete removes the selection. */
    fun handleKey(event: KeyEvent): Boolean {
        val session = session ?: return false
        val action: (() -> Unit)? = when {
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_Z && event.isShiftPressed -> session::redo
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_Z -> session::undo
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_Y -> session::redo
            isActive && selectedId != null && (event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL) -> ::deleteSelected
            isActive && event.keyCode == KeyEvent.KEYCODE_ESCAPE -> { { if (selectedId != null) select(null) else finish() } }
            else -> null
        }
        if (action == null) return false
        if (event.action == KeyEvent.ACTION_DOWN) action()
        return true
    }

    private fun deleteSelected() {
        val id = selectedId ?: return
        selectedId = null
        session?.remove(id)
    }

    // --- Editing ---------------------------------------------------------------------------

    private fun textDialog(title: Int, initial: String, hint: Int, onDone: (String) -> Unit) {
        val input = EditText(context).apply {
            id = R.id.annotation_text_input
            setText(initial)
            setSelection(initial.length)
            setHint(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(padded(input))
            .setPositiveButton(R.string.ok) { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun editComment(a: Annotation) {
        textDialog(R.string.annot_comment, a.note, R.string.note_hint) { text ->
            session?.replace(a.withNote(text.trim()))
        }
    }

    private fun editFreeText(a: Annotation.FreeText) {
        textDialog(R.string.annot_edit_text, a.text, R.string.text_hint) { text ->
            if (text.isBlank()) {
                session?.remove(a.id)
            } else {
                val layout = AnnotationPainter.textLayout(text, a.fontSize, 0, a.box.width - 4)
                val height = maxOf(a.box.height, layout.height + a.fontSize * 0.6f)
                session?.replace(a.copy(text = text, box = a.box.copy(bottom = a.box.top + height)))
            }
        }
    }

    private fun showColorPicker() {
        val density = context.resources.displayMetrics.density
        val grid = android.widget.GridLayout(context).apply {
            columnCount = 4
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.annot_color)
            .setView(grid)
            .setNegativeButton(R.string.cancel, null)
            .create()
        val names = context.resources.getStringArray(R.array.palette_names)
        AnnotationPrefs.PALETTE.forEachIndexed { i, color ->
            val size = (44 * density).toInt()
            grid.addView(FrameLayout(context).apply {
                contentDescription = names.getOrElse(i) { "#%06X".format(color and 0xFFFFFF) }
                val inner = View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke((1.5f * density).toInt(), 0x80000000.toInt())
                    }
                }
                addView(inner, FrameLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt(), android.view.Gravity.CENTER))
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
                }
                isClickable = true
                setOnClickListener {
                    applyColor(color)
                    dialog.dismiss()
                }
            })
        }
        dialog.show()
    }

    private fun applyColor(color: Int) {
        val sel = selected
        if (sel != null) {
            session?.replace(sel.withColor(color))
            // Later annotations of the same kind use it too.
            toolFor(sel)?.let { prefs.setColor(it, color) }
        } else {
            prefs.setColor(tool, color)
        }
        updateBar()
    }

    private fun toolFor(a: Annotation): AnnotTool? = when (a) {
        is Annotation.Markup -> AnnotTool.forMarkup(a.kind)
        is Annotation.Ink -> if (a.opacity < 1f) AnnotTool.HIGHLIGHTER else AnnotTool.PEN
        is Annotation.Shape -> AnnotTool.entries.firstOrNull { it.shape == a.kind }
        is Annotation.FreeText -> AnnotTool.TEXT
        is Annotation.Note -> AnnotTool.NOTE
        is Annotation.Stamp -> AnnotTool.STAMP
        is Annotation.Image -> null
    }

    private fun showWidthPicker() {
        val sel = selected
        val isText = sel is Annotation.FreeText || (sel == null && tool == AnnotTool.TEXT)
        val current = when (sel) {
            is Annotation.Ink -> sel.width
            is Annotation.Shape -> sel.width
            is Annotation.FreeText -> sel.fontSize
            else -> prefs.width(tool)
        }
        val (from, to) = if (isText) 6f to 48f else 0.5f to 24f
        val slider = Slider(context).apply {
            id = R.id.annotation_width_slider
            valueFrom = from
            valueTo = to
            stepSize = 0.5f
            value = (Math.round(current.coerceIn(from, to) * 2) / 2f)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(if (isText) R.string.font_size else R.string.annot_width)
            .setView(padded(slider))
            .setPositiveButton(R.string.ok) { _, _ ->
                val value = slider.value
                when (sel) {
                    is Annotation.Ink -> session?.replace(sel.copy(width = value))
                    is Annotation.Shape -> session?.replace(sel.copy(width = value))
                    is Annotation.FreeText -> {
                        val layout = AnnotationPainter.textLayout(sel.text, value, 0, sel.box.width - 4)
                        session?.replace(sel.copy(fontSize = value, box = sel.box.copy(bottom = sel.box.top + maxOf(layout.height + value * 0.6f, 8f))))
                    }
                    else -> prefs.setWidth(tool, value)
                }
                sel?.let(::toolFor)?.let { prefs.setWidth(it, value) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showStampPicker() {
        val kinds = listOf(StampKind.APPROVED, StampKind.DRAFT, StampKind.CONFIDENTIAL, StampKind.PAID, StampKind.CUSTOM)
        val labels = kinds.map { if (it == StampKind.CUSTOM) context.getString(R.string.stamp_custom) else it.label }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.tool_stamp)
            .setItems(labels) { _, which ->
                val kind = kinds[which]
                if (kind == StampKind.CUSTOM) {
                    textDialog(R.string.stamp_custom, "", R.string.stamp_text_hint) { text ->
                        if (text.isBlank()) return@textDialog
                        stampKind = kind
                        stampLabel = text.trim().uppercase()
                        setTool(AnnotTool.STAMP)
                    }
                } else {
                    stampKind = kind
                    stampLabel = kind.label
                    setTool(AnnotTool.STAMP)
                    Toast.makeText(context, R.string.tap_to_place_stamp, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showMoreMenu(anchor: View) {
        val ready = ready ?: return
        val session = session ?: return
        PopupMenu(context, anchor).apply {
            menu.add(0, 1, 0, if (canOverwrite(ready)) R.string.save else R.string.save_copy)
            if (canOverwrite(ready)) menu.add(0, 2, 1, R.string.save_copy)
            menu.add(0, 3, 2, R.string.save_flattened_copy)
            menu.add(0, 4, 3, R.string.export_summary)
            menu.add(0, 5, 4, R.string.author_name)
            menu.add(0, 6, 5, R.string.draw_with_finger).apply {
                isCheckable = true
                isChecked = prefs.fingerDrawing
            }
            menu.add(0, 7, 6, R.string.discard_changes).isEnabled = session.hasChanges
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> save(copy = !canOverwrite(ready), flatten = false)
                    2 -> save(copy = true, flatten = false)
                    3 -> save(copy = true, flatten = true)
                    4 -> exportSummary()
                    5 -> textDialog(R.string.author_name, prefs.author, R.string.author_hint) { prefs.author = it.trim() }
                    6 -> {
                        prefs.fingerDrawing = !prefs.fingerDrawing
                        palm.fingerDrawing = prefs.fingerDrawing
                    }
                    7 -> MaterialAlertDialogBuilder(context)
                        .setMessage(R.string.discard_annotations_message)
                        .setPositiveButton(R.string.discard) { _, _ -> session.discardChanges() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                true
            }
            show()
        }
    }

    // --- Saving ------------------------------------------------------------------------------

    /** Asked before saving, e.g. to point out unfilled required form fields; runs its argument to go ahead. */
    var confirmSave: (proceed: () -> Unit) -> Unit = { it() }

    /** Saves into the file itself, or (with [copy]) into a new file chosen by the user. */
    fun save(copy: Boolean, flatten: Boolean) = confirmSave { saveNow(copy, flatten) }

    private fun saveNow(copy: Boolean, flatten: Boolean) {
        val ready = ready ?: return
        val session = ready.annotations ?: return
        if (!copy && !flatten && canOverwrite(ready)) {
            activity.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val temp = File(context.cacheDir, "annotated-${UUID.randomUUID()}.pdf")
                        try {
                            temp.outputStream().use { session.writeTo(ready.contentUri, it, flatten = false) }
                            val output = context.contentResolver.openOutputStream(ready.uri, "wt") ?: throw java.io.IOException("Cannot write")
                            output.use { out -> temp.inputStream().use { it.copyTo(out) } }
                        } finally {
                            temp.delete()
                        }
                    }.isSuccess
                }
                if (ok) {
                    session.onSaved()
                    Toast.makeText(context, R.string.annotations_saved, Toast.LENGTH_SHORT).show()
                    viewModel.reload()
                } else {
                    Toast.makeText(context, R.string.annotations_save_failed, Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        viewModel.pendingFlattenCopy = flatten
        val base = DocumentExport.safeFileName(ready.name).removeSuffix(".pdf").removeSuffix(".PDF")
        saveCopyLauncher.launch(context.getString(if (flatten) R.string.flattened_file_name else R.string.annotated_file_name, base))
    }

    private fun finishSaveCopy(uri: Uri, flatten: Boolean) {
        val ready = ready ?: return
        val session = ready.annotations ?: return
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("Cannot write")
                    output.use { session.writeTo(ready.contentUri, it, flatten) }
                }.isSuccess
            }
            if (!ok) {
                Toast.makeText(context, R.string.annotations_save_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(context, R.string.annotations_saved, Toast.LENGTH_SHORT).show()
            if (!flatten) {
                // The annotations now live in the copy; continue there.
                session.onSaved()
                viewModel.open(uri, displayName(uri))
            }
        }
    }

    private fun displayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"

    // --- Panel -------------------------------------------------------------------------------

    fun refreshPanel() {
        if (!::panelList.isInitialized) return
        val items = session?.items.orEmpty()
        val types = items.map { it.typeName() }.distinct().sorted()
        val authors = items.map { it.author }.filter { it.isNotBlank() }.distinct().sorted()
        // Drop filters for types that no longer exist.
        filter = AnnotationFilter(filter.types intersect types.toSet(), filter.authors intersect authors.toSet())
        rebuildChips(types, authors)
        val shown = filter.apply(items)
        panelAdapter.items = shown
        panelEmpty.isVisible = shown.isEmpty()
        panelList.isVisible = shown.isNotEmpty()
    }

    private var chipKey: List<String> = emptyList()

    private fun rebuildChips(types: List<String>, authors: List<String>) {
        val key = types + listOf("|") + authors
        if (key == chipKey) return
        chipKey = key
        panelFilters.removeAllViews()
        fun chip(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Chip(context).apply {
            text = label
            isCheckable = true
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
            panelFilters.addView(this)
        }
        for (type in types) chip(type, type in filter.types) { on ->
            filter = filter.copy(types = if (on) filter.types + type else filter.types - type)
            refreshPanel()
        }
        // Authors only matter when several people annotated the file.
        if (authors.size > 1) for (author in authors) chip(author, author in filter.authors) { on ->
            filter = filter.copy(authors = if (on) filter.authors + author else filter.authors - author)
            refreshPanel()
        }
    }

    /** Shares a plain-text summary of the (filtered) annotations. */
    fun exportSummary() {
        val ready = ready ?: return
        val items = filter.apply(session?.items.orEmpty())
        if (items.isEmpty()) {
            Toast.makeText(context, R.string.no_annotations, Toast.LENGTH_SHORT).show()
            return
        }
        val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val text = AnnotationSummary.text(ready.name, items) { format.format(Date(it)) }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.summary_subject, ready.name))
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.export_summary)))
    }

    private fun padded(view: View) = FrameLayout(context).apply {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private companion object {
        const val PREVIEW_ID = "preview"
        const val TOUCH_SLOP_DP = 4f
        const val DEFAULT_TEXT_WIDTH = 180f
        const val STAMP_WIDTH = 150f
        const val STAMP_HEIGHT = 50f
        const val IMAGE_WIDTH = 150f
    }
}
