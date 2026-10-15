package com.example.pdfreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.pdfreader.core.BlankSize
import com.example.pdfreader.core.PageRef
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Page management: a grid of the document's pages to select, reorder (drag or "Move to"),
 * rotate, delete, duplicate, insert (blank, other PDF, image) and extract, with undo. Saving
 * writes a new PDF over the file (when allowed) or to a copy.
 */
class OrganizerActivity : AppCompatActivity() {

    private val model: OrganizerViewModel by viewModels()
    private lateinit var toolbar: MaterialToolbar
    private lateinit var grid: RecyclerView
    private lateinit var adapter: OrganizerAdapter
    private lateinit var touchHelper: ItemTouchHelper

    private val documentUri: Uri by lazy { requireNotNull(intent.getParcelableExtraCompat<Uri>(EXTRA_URI)) }
    private val documentName: String by lazy { intent.getStringExtra(EXTRA_NAME) ?: "Document.pdf" }
    private val canOverwrite: Boolean by lazy { intent.getBooleanExtra(EXTRA_CAN_OVERWRITE, false) }

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) insertFile(uri, isImage = false)
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) insertFile(uri, isImage = true)
    }
    private var pendingExtract: List<PageRef>? = null
    private val createCopy = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val pages = pendingExtract ?: model.editor?.pages
        pendingExtract = null
        if (uri != null && pages != null) save(uri, pages, isFullDocument = pages === model.editor?.pages, replacesOriginal = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (ReaderSettings(this).theme == AppTheme.SEPIA) setTheme(R.style.Theme_PdfReader_Sepia)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_organizer)
        toolbar = findViewById(R.id.organizerToolbar)
        grid = findViewById(R.id.organizerGrid)
        val progress = findViewById<ProgressBar>(R.id.organizerProgress)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.organizerRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        toolbar.inflateMenu(R.menu.organizer_menu)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.setOnMenuItemClickListener { onMenuItem(it.itemId) }

        adapter = OrganizerAdapter(model, lifecycleScope, ::toggleSelection) { touchHelper.startDrag(it) }
        val columns = (resources.displayMetrics.widthPixels / resources.displayMetrics.density / 130).toInt().coerceAtLeast(2)
        grid.layoutManager = GridLayoutManager(this, columns)
        grid.adapter = adapter
        touchHelper = ItemTouchHelper(DragCallback()).also { it.attachToRecyclerView(grid) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })

        model.load(documentUri)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.version.collect { refresh() } }
                launch { model.busy.collect { progress.isVisible = it } }
                launch {
                    model.messages.collect { message ->
                        if (message != null) {
                            Toast.makeText(this@OrganizerActivity, message, Toast.LENGTH_LONG).show()
                            model.messages.value = null
                        }
                    }
                }
            }
        }
    }

    private fun refresh() {
        val editor = model.editor
        adapter.submit(editor?.pages ?: emptyList())
        val count = model.selection.size
        toolbar.title = if (count > 0) getString(R.string.selected_count, count) else getString(R.string.organize_pages)
        val menu = toolbar.menu
        menu.findItem(R.id.org_undo).isEnabled = editor?.canUndo == true
        menu.findItem(R.id.org_redo).isEnabled = editor?.canRedo == true
        menu.findItem(R.id.org_save).isEnabled = editor?.isModified == true
    }

    private fun toggleSelection(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        if (!model.selection.remove(position)) model.selection.add(position)
        model.changed()
    }

    private fun requireSelection(): Set<Int>? {
        if (model.selection.isEmpty()) {
            Toast.makeText(this, R.string.select_pages_first, Toast.LENGTH_SHORT).show()
            return null
        }
        return model.selection.toSet()
    }

    /** New pages go after the last selected page, or at the end. */
    private fun insertPosition(): Int = (model.selection.maxOrNull()?.plus(1)) ?: (model.editor?.pages?.size ?: 0)

    private fun onMenuItem(id: Int): Boolean {
        val editor = model.editor ?: return false
        when (id) {
            R.id.org_undo -> model.edit { undo() }
            R.id.org_redo -> model.edit { redo() }
            R.id.org_rotate -> requireSelection()?.let { s -> model.edit { rotate(s, 90) } }
            R.id.org_rotate_left -> requireSelection()?.let { s -> model.edit { rotate(s, 270) } }
            R.id.org_rotate_180 -> requireSelection()?.let { s -> model.edit { rotate(s, 180) } }
            R.id.org_duplicate -> requireSelection()?.let { s ->
                model.selection.clear()
                model.edit { duplicate(s) }
            }
            R.id.org_delete -> requireSelection()?.let { s ->
                if (s.size >= editor.pages.size) {
                    Toast.makeText(this, R.string.cannot_delete_all, Toast.LENGTH_SHORT).show()
                } else {
                    model.selection.clear()
                    model.edit { delete(s) }
                    Snackbar.make(grid, getString(R.string.deleted_pages, s.size), Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo) { model.edit { undo() } }
                        .show()
                }
            }
            R.id.org_move -> requireSelection()?.let(::showMoveDialog)
            R.id.org_extract -> requireSelection()?.let { s ->
                pendingExtract = editor.selected(s)
                createCopy.launch(DocumentExport.safeFileName(documentName.removeSuffix(".pdf") + " (pages)"))
            }
            R.id.org_insert_blank_a4 -> insertBlank(BlankSize.A4.width, BlankSize.A4.height)
            R.id.org_insert_blank_letter -> insertBlank(BlankSize.LETTER.width, BlankSize.LETTER.height)
            R.id.org_insert_blank_same -> {
                val neighbour = editor.pages.getOrNull(insertPosition() - 1) ?: editor.pages.firstOrNull()
                val size = neighbour?.let(model::pageSize)
                if (size == null) insertBlank(BlankSize.A4.width, BlankSize.A4.height) else insertBlank(size.width.toFloat(), size.height.toFloat())
            }
            R.id.org_insert_pdf -> pickPdf.launch(arrayOf("application/pdf"))
            R.id.org_insert_image -> pickImage.launch("image/*")
            R.id.org_select_all -> {
                model.selection.addAll(editor.pages.indices)
                model.changed()
            }
            R.id.org_select_none -> {
                model.selection.clear()
                model.changed()
            }
            R.id.org_save -> if (canOverwrite) {
                save(documentUri, editor.pages, isFullDocument = true, replacesOriginal = true)
            } else {
                Toast.makeText(this, R.string.save_needs_copy, Toast.LENGTH_LONG).show()
                createCopy.launch(DocumentExport.safeFileName(documentName))
            }
            R.id.org_save_copy -> createCopy.launch(DocumentExport.safeFileName(documentName))
            else -> return false
        }
        return true
    }

    private fun insertBlank(width: Float, height: Float) {
        val at = insertPosition()
        model.edit { insert(at, listOf(PageRef.blank(width, height))) }
    }

    private fun insertFile(uri: Uri, isImage: Boolean) {
        val at = insertPosition()
        model.setBusy(true)
        lifecycleScope.launch {
            try {
                val pdf = if (isImage) withContext(Dispatchers.IO) { PageAssembler.imageToPdf(this@OrganizerActivity, uri) } else uri
                val source = model.addSource(pdf)
                val count = model.renderers[source].pageCount
                model.edit { insert(at, List(count) { PageRef(source, it) }) }
            } catch (e: Exception) {
                Toast.makeText(this@OrganizerActivity, R.string.error_open, Toast.LENGTH_LONG).show()
            } finally {
                model.setBusy(false)
            }
        }
    }

    private fun showMoveDialog(selection: Set<Int>) {
        val editor = model.editor ?: return
        val input = EditText(this).apply {
            id = R.id.move_input
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.move_to_hint, editor.pages.size)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.move_pages)
            .setView(FrameLayout(this).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            })
            .setPositiveButton(R.string.go) { _, _ ->
                val position = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                model.edit { move(selection, position - 1) }
                // Keep the moved pages selected at their new positions.
                val start = (position - 1).coerceIn(0, editor.pages.size - selection.size)
                model.selection.clear()
                model.selection.addAll(start until start + selection.size)
                model.changed()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun save(destination: Uri, pages: List<PageRef>, isFullDocument: Boolean, replacesOriginal: Boolean) {
        model.setBusy(true)
        lifecycleScope.launch {
            try {
                model.writeTo(destination, pages)
                Toast.makeText(this@OrganizerActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                if (isFullDocument) model.editor?.markSaved()
                model.changed()
                if (replacesOriginal) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RELOAD, true))
                    finish()
                }
            } catch (e: Exception) {
                Toast.makeText(this@OrganizerActivity, if (replacesOriginal) R.string.save_needs_copy else R.string.copy_failed, Toast.LENGTH_LONG).show()
                if (replacesOriginal) createCopy.launch(DocumentExport.safeFileName(documentName))
            } finally {
                model.setBusy(false)
            }
        }
    }

    private fun confirmLeave() {
        if (model.editor?.isModified != true) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_changes_title)
            .setMessage(R.string.discard_changes_message)
            .setPositiveButton(R.string.discard) { _, _ -> finish() }
            .setNegativeButton(R.string.keep_editing, null)
            .show()
    }

    /** Drag to reorder: the dragged page (and any other selected pages) moves on drop. */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0,
    ) {
        private var from = -1
        private var to = -1

        override fun isLongPressDragEnabled() = false

        override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val a = source.bindingAdapterPosition
            val b = target.bindingAdapterPosition
            if (from < 0) from = a
            to = b
            adapter.pages.add(b, adapter.pages.removeAt(a))
            adapter.notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val start = from
            val end = to
            from = -1
            to = -1
            if (start < 0 || end < 0 || start == end) {
                model.changed()
                return
            }
            // Move the dragged page together with the rest of the selection, if it was selected.
            val moving = if (start in model.selection) model.selection.toSet() else setOf(start)
            val others = (0 until (model.editor?.pages?.size ?: 0)).filter { it != start }
            val target = others.take(end).count { it !in moving }
            model.selection.clear()
            model.edit { move(moving, target) }
            model.selection.addAll(target until target + moving.size)
            model.changed()
        }
    }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_CAN_OVERWRITE = "can_overwrite"
        const val EXTRA_RELOAD = "reload"

        fun intent(context: Context, uri: Uri, name: String, canOverwrite: Boolean) =
            Intent(context, OrganizerActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_CAN_OVERWRITE, canOverwrite)
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String): T? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java) else getParcelableExtra(name)
