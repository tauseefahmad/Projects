package com.example.pdfreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.example.pdfreader.core.TextGeometry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Long-press text selection on one page: drag handles to adjust, then Copy, Share, Select all,
 * or any "process text" app (e.g. Translate) from the floating text toolbar.
 */
class TextSelectionController(
    private val context: Context,
    private val list: ZoomableRecyclerView,
    private val handles: SelectionHandles,
    private val scope: CoroutineScope,
    private val structure: () -> PdfStructure?,
) {
    private var page = -1
    private var text: PageText? = null
    private var start = 0 // inclusive character indices
    private var end = 0
    private var actionMode: ActionMode? = null
    private var processTextApps: List<Intent> = emptyList()

    val isActive: Boolean get() = page >= 0

    /** The selected text, or "" when nothing is selected. */
    val selectedText: String
        get() {
            val pageText = text ?: return ""
            if (!isActive || pageText.text.isEmpty()) return ""
            return pageText.text.substring(start.coerceIn(0, pageText.text.length), (end + 1).coerceIn(0, pageText.text.length)).trim()
        }

    init {
        handles.onDrag = ::dragHandle
        handles.onDragEnd = { actionMode?.invalidateContentRect() ?: startActionMode() }
    }

    /** Selects the word under a long-press, if the press is on (or right next to) text. */
    fun startAt(screenX: Float, screenY: Float) {
        val (view, local) = list.pageViewAt(screenX, screenY) ?: return
        val structure = structure() ?: return
        val pageIndex = view.page
        scope.launch {
            val pageText = try {
                structure.pageTextWithPositions(pageIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            val x = local.x / view.scale
            val y = local.y / view.scale
            val index = TextGeometry.indexAt(pageText.boxes, x, y)
            val box = pageText.boxOf(index) ?: return@launch
            // Ignore presses in empty margins: require being within about a line of the text.
            val slack = max(box.height(), 8f) * 1.5f
            if (x < box.left - slack || x > box.right + slack || y < box.top - slack || y > box.bottom + slack) return@launch
            val word = TextGeometry.wordAt(pageText.text, index)
            clear()
            page = pageIndex
            text = pageText
            start = word.first
            end = word.last
            list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            refresh()
            startActionMode()
        }
    }

    private fun dragHandle(isStart: Boolean, x: Float, y: Float) {
        val pageText = text ?: return
        val view = list.pageViewFor(page) ?: return
        val origin = list.screenPointIn(view, 0f, 0f)
        val scale = view.scale * list.zoom
        val index = TextGeometry.indexAt(pageText.boxes, (x - origin.x) / scale, (y - origin.y) / scale)
        if (index < 0) return
        if (isStart) start = index.coerceAtMost(end) else end = index.coerceAtLeast(start)
        refresh()
        actionMode?.invalidateContentRect()
    }

    fun selectAll() {
        val pageText = text ?: return
        val first = pageText.text.indexOfFirst { !it.isWhitespace() }
        val last = pageText.text.indexOfLast { !it.isWhitespace() }
        if (first < 0) return
        start = first
        end = last
        refresh()
        actionMode?.invalidateContentRect()
    }

    fun clear() {
        list.pageViewFor(page)?.selection = emptyList()
        page = -1
        text = null
        handles.start = null
        handles.end = null
        actionMode?.finish()
        actionMode = null
    }

    /** Keeps the highlight and handles in place while the page scrolls or zooms. */
    fun onViewportChanged() {
        if (!isActive) return
        refresh()
        actionMode?.invalidateContentRect()
    }

    private fun refresh() {
        val pageText = text ?: return
        val view = list.pageViewFor(page)
        if (view == null) {
            handles.start = null
            handles.end = null
            return
        }
        view.selection = pageText.boxesFor(start, end + 1)
        val first = (start..end).firstNotNullOfOrNull { pageText.boxOf(it) }
        val last = (end downTo start).firstNotNullOfOrNull { pageText.boxOf(it) }
        val scale = view.scale
        handles.start = first?.let { list.screenPointIn(view, it.left * scale, it.bottom * scale) }
        handles.end = last?.let { list.screenPointIn(view, it.right * scale, it.bottom * scale) }
    }

    /** Selection bounds in list coordinates, for placing the floating toolbar. */
    private fun selectionBounds(): Rect? {
        val view = list.pageViewFor(page) ?: return null
        val boxes = view.selection
        if (boxes.isEmpty()) return null
        val union = RectF(boxes[0]).apply { boxes.drop(1).forEach(::union) }
        val scale = view.scale
        val topLeft = list.screenPointIn(view, union.left * scale, union.top * scale)
        val bottomRight = list.screenPointIn(view, union.right * scale, union.bottom * scale)
        return Rect(topLeft.x.toInt(), topLeft.y.toInt(), bottomRight.x.toInt(), bottomRight.y.toInt())
    }

    private fun startActionMode() {
        if (!isActive || actionMode != null) return
        actionMode = list.startActionMode(callback, ActionMode.TYPE_FLOATING)
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, R.id.action_share_text, 1, R.string.share)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, android.R.id.selectAll, 2, android.R.string.selectAll)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            // Apps offering to act on selected text, e.g. Google Translate.
            val pm = context.packageManager
            processTextApps = pm.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
                .take(MAX_PROCESS_TEXT_ITEMS)
                .map { info -> Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setClassName(info.activityInfo.packageName, info.activityInfo.name) }
            processTextApps.forEachIndexed { i, intent ->
                val label = pm.resolveActivity(intent, 0)?.loadLabel(pm) ?: return@forEachIndexed
                menu.add(PROCESS_TEXT_GROUP, PROCESS_TEXT_ID_BASE + i, 10 + i, label)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val selected = selectedText
            when (item.itemId) {
                android.R.id.copy -> {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), selected))
                    // Android 13+ shows its own clipboard confirmation.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                    }
                    clear()
                }
                R.id.action_share_text -> {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, selected)
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
                    clear()
                }
                android.R.id.selectAll -> selectAll()
                else -> {
                    val intent = processTextApps.getOrNull(item.itemId - PROCESS_TEXT_ID_BASE) ?: return false
                    context.startActivity(
                        Intent(intent).putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                    )
                    clear()
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            // Closing the toolbar (Back, tapping away) ends the selection, as in text fields.
            if (actionMode === mode) {
                actionMode = null
                clear()
            }
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            outRect.set(selectionBounds() ?: Rect(0, 0, view.width, view.height / 3))
        }
    }

    private companion object {
        const val PROCESS_TEXT_GROUP = 1
        const val PROCESS_TEXT_ID_BASE = 20_000
        const val MAX_PROCESS_TEXT_ITEMS = 6
    }
}
