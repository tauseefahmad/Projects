package com.example.pdfreader

import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.pdfreader.core.AutoOutline
import com.example.pdfreader.core.ContractReview
import com.example.pdfreader.core.DataExtraction
import com.example.pdfreader.core.OutlineEntry
import com.example.pdfreader.core.PassageSearch
import com.example.pdfreader.core.StyledLine
import com.example.pdfreader.core.SummaryLength
import com.example.pdfreader.core.Summarizer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Document insights that run on the phone, without sending text anywhere (phase 7): key-sentence
 * summaries, questions answered with the passages that contain the answer (with page
 * citations), data extraction, contract clause review and automatic bookmarks.
 */
class InsightsController(
    private val activity: AppCompatActivity,
    private val current: () -> ToolsController.CurrentDocument?,
    private val convert: ConvertController,
    /** Jumps to [page] and highlights [phrase] there. */
    private val showPassage: (page: Int, phrase: String) -> Unit,
) {
    private val context get() = activity

    /** Page texts of the document last analyzed, by uri. */
    private var cache: Pair<String, List<String>>? = null

    /** The open dialog (for tests). */
    var dialog: AlertDialog? = null
        private set

    private suspend fun pageTexts(doc: ToolsController.CurrentDocument): List<String> {
        cache?.let { (key, texts) -> if (key == doc.contentUri.toString()) return texts }
        val texts = withContext(Dispatchers.IO) { SecurityTools.load(context, doc.contentUri).use { ConvertTools.pageTexts(it) } }
        cache = doc.contentUri.toString() to texts
        return texts
    }

    private fun withText(block: suspend (ToolsController.CurrentDocument, List<String>) -> Unit) {
        val doc = current() ?: return
        activity.lifecycleScope.launch {
            val texts = try {
                pageTexts(doc)
            } catch (e: Exception) {
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (texts.all { it.isBlank() }) {
                Toast.makeText(context, R.string.insights_no_text, Toast.LENGTH_LONG).show()
                return@launch
            }
            block(doc, texts)
        }
    }

    // --- Summary ----------------------------------------------------------------------------------

    fun showSummary() {
        val labels = arrayOf(context.getString(R.string.summary_paragraph), context.getString(R.string.summary_page), context.getString(R.string.summary_sections))
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.summary_title)
            .setItems(labels) { _, which -> summarize(SummaryLength.entries[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun summarize(length: SummaryLength) = withText { doc, texts ->
        val sentences = withContext(Dispatchers.Default) { Summarizer.summarize(texts, length) }
        if (sentences.isEmpty()) {
            Toast.makeText(context, R.string.insights_no_text, Toast.LENGTH_LONG).show()
            return@withText
        }
        val list = list()
        list.addView(note(R.string.summary_note))
        var lastPage = -1
        for (s in sentences) {
            if (length == SummaryLength.BY_SECTION && s.page != lastPage) {
                lastPage = s.page
                list.addView(heading(context.getString(R.string.page_n, s.page + 1)))
            }
            list.addView(row("• " + s.text, context.getString(R.string.page_short, s.page + 1)) { showPassage(s.page, phrase(s.text)) })
        }
        val markdown = Summarizer.markdown(doc.name, sentences, length)
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.summary_title)
            .setView(scroll(list))
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.summary_export) { _, _ -> saveText(markdown, "text/markdown", "${convert.baseName(doc.name)} summary.md") }
            .show()
    }

    // --- Ask --------------------------------------------------------------------------------------

    fun showAsk() {
        val input = EditText(context).apply {
            id = R.id.ask_input
            setHint(R.string.ask_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ask_title)
            .setView(padded(input))
            .setPositiveButton(R.string.ask_action) { _, _ -> ask(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    fun ask(question: String) = withText { _, texts ->
        if (question.isBlank()) return@withText
        val passages = withContext(Dispatchers.Default) { PassageSearch.search(texts, question) }
        val list = list()
        list.addView(note(R.string.ask_note))
        if (passages.isEmpty()) list.addView(note(R.string.ask_nothing))
        for (p in passages) list.addView(row(p.text, context.getString(R.string.page_short, p.page + 1)) { showPassage(p.page, phrase(p.text)) })
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(question)
            .setView(scroll(list))
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.ask_again) { _, _ -> showAsk() }
            .show()
    }

    // --- Data extraction --------------------------------------------------------------------------

    fun showExtract() = withText { doc, texts ->
        val data = withContext(Dispatchers.Default) { DataExtraction.extract(texts) }
        val list = list()
        list.addView(heading(context.getString(R.string.extract_kind, data.kind.label)))
        if (data.fields.isEmpty()) list.addView(note(R.string.extract_nothing))
        for (f in data.fields) list.addView(row("${f.name}: ${f.value}", context.getString(R.string.page_short, f.page + 1)) { showPassage(f.page, f.value) })
        val base = convert.baseName(doc.name)
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.extract_title)
            .setView(scroll(list))
            .setPositiveButton(R.string.extract_json) { _, _ -> saveText(data.json(), "application/json", "$base data.json") }
            .setNeutralButton(R.string.extract_csv) { _, _ -> saveText(data.csv(), "text/csv", "$base data.csv") }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // --- Contract review --------------------------------------------------------------------------

    fun showContractReview() = withText { _, texts ->
        val findings = withContext(Dispatchers.Default) { ContractReview.review(texts) }
        val list = list()
        list.addView(note(R.string.review_disclaimer))
        if (findings.isEmpty()) list.addView(note(R.string.review_nothing))
        // Flagged clauses first.
        for (f in findings.sortedBy { if (it.warning != null) 0 else 1 }) {
            val title = if (f.warning != null) "⚠ ${f.category}: ${f.warning}" else f.category
            list.addView(heading(title, warn = f.warning != null))
            list.addView(row(f.text, context.getString(R.string.page_short, f.page + 1)) { showPassage(f.page, phrase(f.text)) })
        }
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.review_title)
            .setView(scroll(list))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // --- Automatic bookmarks ----------------------------------------------------------------------

    fun showAutoBookmarks() {
        val doc = current() ?: return
        activity.lifecycleScope.launch {
            val (entries, hasOutline) = try {
                withContext(Dispatchers.IO) {
                    SecurityTools.load(context, doc.contentUri).use { pdf ->
                        AutoOutline.build(styledLines(pdf), pdf.numberOfPages) to (pdf.documentCatalog.documentOutline?.firstChild != null)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (entries.isEmpty()) {
                Toast.makeText(context, R.string.bookmarks_none_found, Toast.LENGTH_LONG).show()
                return@launch
            }
            val list = list()
            if (hasOutline) list.addView(note(R.string.bookmarks_replace_note))
            for (e in entries) {
                list.addView(row("    ".repeat(e.level - 1) + e.title, context.getString(R.string.page_short, e.page + 1)) { showPassage(e.page, e.title) })
            }
            dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.bookmarks_auto_title)
                .setView(scroll(list))
                .setPositiveButton(R.string.bookmarks_add) { _, _ -> writeOutline(doc, entries) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    fun writeOutline(doc: ToolsController.CurrentDocument, entries: List<OutlineEntry>) {
        activity.lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    convert.temp("pdf").also { out ->
                        SecurityTools.load(context, doc.contentUri).use { pdf ->
                            addOutline(pdf, entries)
                            if (pdf.isEncrypted) pdf.isAllSecurityToBeRemoved = true
                            out.outputStream().use { pdf.save(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            convert.replaceOrCopy(doc, file)
        }
    }

    companion object {
        /** Replaces the document's bookmarks with [entries] (nested by level). */
        fun addOutline(pdf: PDDocument, entries: List<OutlineEntry>) {
            val outline = PDDocumentOutline()
            pdf.documentCatalog.documentOutline = outline
            val parents = arrayOfNulls<PDOutlineItem>(4)
            for (e in entries) {
                val page = pdf.getPage(e.page)
                val item = PDOutlineItem().apply {
                    title = e.title
                    destination = PDPageXYZDestination().apply {
                        this.page = page
                        top = (page.cropBox.upperRightY - e.top + 4).toInt()
                    }
                }
                val parent = (e.level - 1 downTo 1).firstNotNullOfOrNull { parents[it] }
                if (parent != null) parent.addLast(item) else outline.addLast(item)
                parents[e.level] = item
                for (deeper in e.level + 1 until parents.size) parents[deeper] = null
            }
            outline.openNode()
        }

        /** Lines of every page with their font size, for finding headings. */
        fun styledLines(pdf: PDDocument): List<StyledLine> {
            val lines = mutableListOf<StyledLine>()
            val stripper = object : PDFTextStripper() {
                private val text = StringBuilder()
                private var size = 0f
                private var chars = 0
                private var bold = 0
                private var top = Float.NaN

                override fun writeString(string: String, textPositions: List<TextPosition>) {
                    text.append(string)
                    for (p in textPositions) {
                        size += p.fontSizeInPt
                        chars++
                        if (p.font?.name?.contains("Bold", ignoreCase = true) == true) bold++
                        if (top.isNaN()) top = p.yDirAdj - p.heightDir
                    }
                }

                override fun writeWordSeparator() {
                    text.append(' ')
                }

                override fun writeLineSeparator() = flush()

                override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage?) {
                    flush()
                    super.endPage(page)
                }

                private fun flush() {
                    if (chars > 0 && text.isNotBlank()) {
                        lines += StyledLine(text.toString().trim(), currentPageNo - 1, top, size / chars, bold * 2 > chars)
                    }
                    text.setLength(0)
                    size = 0f
                    chars = 0
                    bold = 0
                    top = Float.NaN
                }
            }
            stripper.sortByPosition = true
            stripper.getText(pdf)
            return lines
        }
    }

    // --- Views ------------------------------------------------------------------------------------

    private fun saveText(text: String, mime: String, name: String) {
        activity.lifecycleScope.launch {
            val ext = name.substringAfterLast('.')
            val file = withContext(Dispatchers.IO) { convert.temp(ext).also { it.writeText(text) } }
            convert.saveAs(file, mime, name, open = false)
        }
    }

    /** A short, searchable phrase from the start of a passage. */
    private fun phrase(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(5).joinToString(" ")

    private val density get() = context.resources.displayMetrics.density

    private fun list() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    private fun scroll(view: View) = ScrollView(context).apply { addView(view) }

    private fun padded(view: View) = android.widget.FrameLayout(context).apply {
        val pad = (20 * density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(view)
    }

    private fun note(text: Int) = TextView(context).apply {
        setText(text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        alpha = 0.7f
        setPadding(0, 0, 0, (8 * density).toInt())
    }

    private fun heading(text: String, warn: Boolean = false) = TextView(context).apply {
        this.text = text
        setTypeface(typeface, Typeface.BOLD)
        if (warn) setTextColor(0xFFC62828.toInt())
        setPadding(0, (10 * density).toInt(), 0, (2 * density).toInt())
    }

    private fun row(text: String, page: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundResource(R.drawable.panel_row_bg)
        val pad = (6 * density).toInt()
        setPadding(0, pad, 0, pad)
        addView(TextView(context).apply { this.text = text }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            this.text = page
            alpha = 0.6f
            setPadding((8 * density).toInt(), 0, 0, 0)
        })
        setOnClickListener {
            dialog?.dismiss()
            onClick()
        }
    }
}
