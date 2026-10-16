package com.example.pdfreader

import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.pdfreader.core.PositionedWord
import com.example.pdfreader.core.TableExtraction
import com.example.pdfreader.core.TextFormat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Phase 5 screens: compress, export to pictures/text/Word/CSV, recognize text (OCR), make a PDF
 * from pictures or the camera, and run one operation on many files. Create it while the
 * activity is being constructed (it registers for activity results).
 */
class ConvertController(
    private val activity: AppCompatActivity,
    private val current: () -> ToolsController.CurrentDocument?,
    /** Text with character positions of a page of the open document. */
    private val pageText: suspend (Int) -> PageText,
    private val currentPage: () -> Int,
    private val openResult: (Uri, String) -> Unit,
    private val reloadCurrent: () -> Unit,
) {
    private val context get() = activity

    // --- Saving results ---------------------------------------------------------------------------

    private class Pending(val file: File, val open: Boolean, val onDone: (() -> Unit)? = null)

    private var pending: Pending? = null

    private val savers = listOf(
        "application/pdf", "application/zip", "image/png", "image/jpeg", "text/plain", "text/markdown", "text/html", "text/csv",
        TextFormat.WORD.mimeType, "application/json",
    ).associateWith { mime ->
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri -> finishSave(uri) }
    }

    private fun finishSave(uri: Uri?) {
        val p = pending ?: return
        pending = null
        if (uri == null) {
            p.file.delete()
            return
        }
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { copy(p.file, uri) }
                Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
                p.onDone?.invoke()
                if (p.open) openResult(uri, displayName(uri))
            } catch (e: Exception) {
                Toast.makeText(context, R.string.copy_failed, Toast.LENGTH_LONG).show()
            } finally {
                p.file.delete()
            }
        }
    }

    /** Asks where to save [file] (as [mime] named [name]), then optionally opens it. */
    internal fun saveAs(file: File, mime: String, name: String, open: Boolean, onDone: (() -> Unit)? = null) {
        pending = Pending(file, open, onDone)
        savers.getValue(mime).launch(name)
    }

    /** Replaces the open file with [file] when allowed, else saves a copy. */
    internal fun replaceOrCopy(doc: ToolsController.CurrentDocument, file: File) {
        activity.lifecycleScope.launch {
            if (doc.canOverwrite) {
                try {
                    withContext(Dispatchers.IO) {
                        VersionStore.keep(context, doc.uri)
                        copy(file, doc.uri)
                    }
                    file.delete()
                    Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
                    reloadCurrent()
                    return@launch
                } catch (e: Exception) {
                    // Save a copy instead.
                }
            }
            saveAs(file, "application/pdf", DocumentExport.safeFileName(doc.name), open = true)
        }
    }

    private fun copy(file: File, uri: Uri) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("Cannot write $uri")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** A private copy of the open document to work on (PdfRenderer and PdfBox need a file). */
    private fun localCopy(uri: Uri): File {
        val file = temp("pdf")
        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot read $uri")
        input.use { i -> file.outputStream().use { i.copyTo(it) } }
        return file
    }

    internal fun temp(extension: String) = File(context.cacheDir, "convert-${UUID.randomUUID()}.$extension")

    internal fun baseName(name: String) = name.substringBeforeLast('.').ifBlank { "Document" }

    // --- Progress -------------------------------------------------------------------------------

    private class Progress(val dialog: AlertDialog, val bar: LinearProgressIndicator, val label: TextView) {
        @Volatile var cancelled = false
    }

    private fun showProgress(title: Int): Progress {
        val bar = LinearProgressIndicator(context).apply {
            id = R.id.convert_progress
            isIndeterminate = true
        }
        val label = TextView(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(bar)
            addView(label)
        }
        lateinit var progress: Progress
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> progress.cancelled = true }
            .show()
        progress = Progress(dialog, bar, label)
        return progress
    }

    private fun Progress.update(done: Int, total: Int) = activity.runOnUiThread {
        bar.isIndeterminate = false
        bar.max = total.coerceAtLeast(1)
        bar.progress = done
        label.text = context.getString(R.string.progress_pages, done, total)
    }

    // --- Compress -------------------------------------------------------------------------------

    fun showCompress() {
        val doc = current() ?: return
        val group = RadioGroup(context)
        val presets = listOf(
            CompressionPreset.HIGH_QUALITY to R.string.compress_high,
            CompressionPreset.BALANCED to R.string.compress_balanced,
            CompressionPreset.SMALLEST to R.string.compress_smallest,
        ).map { (preset, label) ->
            RadioButton(context).apply {
                id = View.generateViewId()
                setText(label)
                tag = preset
                group.addView(this)
                if (preset == CompressionPreset.BALANCED) isChecked = true
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.compress_title)
            .setView(padded(group))
            .setPositiveButton(R.string.compress_action) { _, _ ->
                compress(doc, presets.first { it.isChecked }.tag as CompressionPreset)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun compress(doc: ToolsController.CurrentDocument, preset: CompressionPreset) {
        val progress = showProgress(R.string.compress_title)
        activity.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val source = localCopy(doc.contentUri)
                    val out = temp("pdf")
                    try {
                        SecurityTools.load(context, Uri.fromFile(source)).use { pdf ->
                            ConvertTools.compress(pdf, preset)
                            if (pdf.isEncrypted) pdf.isAllSecurityToBeRemoved = true
                            out.outputStream().use { pdf.save(it) }
                        }
                        Triple(source.length(), out.length(), out)
                    } finally {
                        source.delete()
                    }
                }
            } catch (e: Exception) {
                progress.dialog.dismiss()
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            progress.dialog.dismiss()
            if (progress.cancelled) {
                result.third.delete()
                return@launch
            }
            val (before, after, file) = result
            if (after >= before) {
                file.delete()
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.compress_title)
                    .setMessage(context.getString(R.string.compress_no_gain, Formatter.formatShortFileSize(context, before)))
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@launch
            }
            val percent = ((before - after) * 100 / before.coerceAtLeast(1)).toInt()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.compress_title)
                .setMessage(context.getString(R.string.compress_result, Formatter.formatShortFileSize(context, before), Formatter.formatShortFileSize(context, after), percent))
                .setPositiveButton(if (doc.canOverwrite) R.string.compress_replace else R.string.save_copy) { _, _ -> replaceOrCopy(doc, file) }
                .setNegativeButton(R.string.cancel) { _, _ -> file.delete() }
                .setCancelable(false)
                .show()
        }
    }

    // --- Export -----------------------------------------------------------------------------------

    fun showExport() {
        val doc = current() ?: return
        val labels = listOf(
            R.string.export_png, R.string.export_jpg, R.string.export_text, R.string.export_markdown,
            R.string.export_html, R.string.export_word, R.string.export_table,
        ).map { context.getString(it) }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.export_title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> chooseDpi { exportImages(doc, ImageFormat.PNG, it) }
                    1 -> chooseDpi { exportImages(doc, ImageFormat.JPG, it) }
                    2 -> exportText(doc, TextFormat.TEXT)
                    3 -> exportText(doc, TextFormat.MARKDOWN)
                    4 -> exportText(doc, TextFormat.HTML)
                    5 -> exportText(doc, TextFormat.WORD)
                    else -> exportTable(doc)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseDpi(onChosen: (Int) -> Unit) {
        val values = intArrayOf(72, 150, 300)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.export_resolution)
            .setItems(values.map { context.getString(R.string.export_dpi, it) }.toTypedArray()) { _, which -> onChosen(values[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun exportImages(doc: ToolsController.CurrentDocument, format: ImageFormat, dpi: Int) {
        val progress = showProgress(R.string.export_title)
        activity.lifecycleScope.launch {
            val base = baseName(doc.name)
            val result = try {
                withContext(Dispatchers.IO) {
                    val source = localCopy(doc.contentUri)
                    val dir = File(context.cacheDir, "export-${UUID.randomUUID()}").apply { mkdirs() }
                    try {
                        val images = ConvertTools.exportImages(source, (0 until doc.pageCount).toList(), dpi, format, base, dir)
                        if (images.size == 1) {
                            images[0].copyTo(temp(format.extension)).also { dir.deleteRecursively() }
                        } else {
                            temp("zip").also { zip -> zip.outputStream().use { ConvertTools.zip(images, it) }; dir.deleteRecursively() }
                        }
                    } finally {
                        source.delete()
                    }
                }
            } catch (e: Exception) {
                progress.dialog.dismiss()
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            progress.dialog.dismiss()
            if (result.extension == "zip") saveAs(result, "application/zip", "$base.zip", open = false)
            else saveAs(result, format.mimeType, "$base.${format.extension}", open = false)
        }
    }

    fun exportText(doc: ToolsController.CurrentDocument, format: TextFormat) {
        activity.lifecycleScope.launch {
            val base = baseName(doc.name)
            val file = try {
                withContext(Dispatchers.IO) {
                    temp(format.extension).also { f ->
                        SecurityTools.load(context, doc.contentUri).use { pdf -> f.outputStream().use { ConvertTools.exportText(pdf, base, format, it) } }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            saveAs(file, format.mimeType, "$base.${format.extension}", open = false)
        }
    }

    /** Exports the table on the current page as CSV. */
    fun exportTable(doc: ToolsController.CurrentDocument) {
        activity.lifecycleScope.launch {
            val page = currentPage()
            val text = try {
                pageText(page)
            } catch (e: Exception) {
                PageText.EMPTY
            }
            val rows = TableExtraction.rows(words(text))
            if (rows.isEmpty()) {
                Toast.makeText(context, R.string.export_no_table, Toast.LENGTH_LONG).show()
                return@launch
            }
            val file = withContext(Dispatchers.IO) { temp("csv").also { it.writeText(TableExtraction.csv(rows)) } }
            saveAs(file, "text/csv", "${baseName(doc.name)}-page-${page + 1}.csv", open = false)
        }
    }

    /** Words of a page with their boxes, from its characters. */
    private fun words(text: PageText): List<PositionedWord> {
        val result = mutableListOf<PositionedWord>()
        var start = -1
        fun flush(end: Int) {
            if (start < 0) return
            val boxes = (start until end).mapNotNull { text.boxOf(it) }
            if (boxes.isNotEmpty()) {
                result += PositionedWord(
                    text.text.substring(start, end),
                    boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom },
                )
            }
            start = -1
        }
        for (i in text.text.indices) {
            if (text.text[i].isWhitespace()) flush(i) else if (start < 0) start = i
        }
        flush(text.text.length)
        return result
    }

    // --- OCR --------------------------------------------------------------------------------------

    fun showOcr() {
        val doc = current() ?: return
        val skip = CheckBox(context).apply {
            setText(R.string.ocr_skip_text_pages)
            isChecked = true
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { setText(R.string.ocr_message) })
            addView(skip)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ocr_title)
            .setView(padded(content))
            .setPositiveButton(R.string.ocr_action) { _, _ -> ocr(doc, skip.isChecked) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun ocr(doc: ToolsController.CurrentDocument, skipPagesWithText: Boolean) {
        val progress = showProgress(R.string.ocr_title)
        activity.lifecycleScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    val source = localCopy(doc.contentUri)
                    val out = temp("pdf")
                    try {
                        val result = out.outputStream().use { o ->
                            Ocr.recognize(context, source, null, skipPagesWithText, o, { d, t -> progress.update(d, t) }, { progress.cancelled })
                        }
                        result to out
                    } finally {
                        source.delete()
                    }
                }
            } catch (e: java.util.concurrent.CancellationException) {
                progress.dialog.dismiss()
                return@launch
            } catch (e: Exception) {
                progress.dialog.dismiss()
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            progress.dialog.dismiss()
            val (result, file) = outcome
            if (result.pagesRecognized == 0) {
                file.delete()
                Toast.makeText(context, R.string.ocr_nothing_to_do, Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(context, context.resources.getQuantityString(R.plurals.ocr_done, result.pagesRecognized, result.pagesRecognized, result.words), Toast.LENGTH_LONG).show()
            replaceOrCopy(doc, file)
        }
    }

    // --- Pictures and camera to PDF -------------------------------------------------------------

    private val pickImages = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) imagesToPdf(uris)
    }

    fun pickImagesForPdf() = pickImages.launch(arrayOf("image/*"))

    fun imagesToPdf(uris: List<Uri>) {
        val progress = showProgress(R.string.images_to_pdf)
        activity.lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { temp("pdf").also { f -> f.outputStream().use { ConvertTools.imagesToPdf(context, uris, it) } } }
            } catch (e: Exception) {
                progress.dialog.dismiss()
                Toast.makeText(context, R.string.image_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            progress.dialog.dismiss()
            saveAs(file, "application/pdf", context.getString(R.string.images_pdf_name), open = true)
        }
    }

    private val scanner = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf ?: return@registerForActivityResult
        finishScan(pdf.uri)
    }

    /** Opens the camera document scanner (edge detection, perspective and color correction). */
    fun scan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scanner.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { Toast.makeText(context, R.string.scan_unavailable, Toast.LENGTH_LONG).show() }
    }

    /** After scanning: make the PDF searchable and small in one go, then save and open it. */
    fun finishScan(pdf: Uri) {
        val ocr = CheckBox(context).apply { setText(R.string.scan_make_searchable); isChecked = true }
        val compress = CheckBox(context).apply { setText(R.string.scan_compress); isChecked = true }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ocr)
            addView(compress)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.scan_title)
            .setView(padded(content))
            .setCancelable(false)
            .setPositiveButton(R.string.save) { _, _ -> processScan(pdf, ocr.isChecked, compress.isChecked) }
            .show()
    }

    fun processScan(pdf: Uri, recognize: Boolean, compress: Boolean) {
        val progress = showProgress(R.string.scan_title)
        activity.lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { process(localCopy(pdf), recognize, compress, removeHidden = false) { d, t -> progress.update(d, t) } }
            } catch (e: Exception) {
                progress.dialog.dismiss()
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            progress.dialog.dismiss()
            saveAs(file, "application/pdf", context.getString(R.string.scan_name), open = true)
        }
    }

    /** Runs OCR and/or compression on [source] (deleted afterwards); returns the result file. */
    private fun process(source: File, recognize: Boolean, compress: Boolean, removeHidden: Boolean, progress: (Int, Int) -> Unit = { _, _ -> }): File {
        var current = source
        try {
            if (recognize) {
                val out = temp("pdf")
                out.outputStream().use { Ocr.recognize(context, current, null, true, it, progress) }
                current.delete()
                current = out
            }
            if (compress || removeHidden) {
                val out = temp("pdf")
                SecurityTools.load(context, Uri.fromFile(current)).use { pdf ->
                    if (compress) ConvertTools.compress(pdf, CompressionPreset.BALANCED)
                    if (removeHidden) SecurityTools.removeHiddenData(pdf, HiddenDataRemoval())
                    if (pdf.isEncrypted) pdf.isAllSecurityToBeRemoved = true
                    out.outputStream().use { pdf.save(it) }
                }
                current.delete()
                current = out
            }
            return current
        } catch (e: Exception) {
            current.delete()
            throw e
        }
    }

    // --- Batch ------------------------------------------------------------------------------------

    private var batchFiles: List<Uri> = emptyList()
    private var batchOperation = 0

    private val pickBatch = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) chooseBatchOperation(uris)
    }

    private val batchFolder = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) runBatch(batchFiles, batchOperation, tree)
    }

    fun showBatch() = pickBatch.launch(arrayOf("application/pdf"))

    private fun chooseBatchOperation(uris: List<Uri>) {
        val labels = arrayOf(
            context.getString(R.string.batch_compress),
            context.getString(R.string.batch_ocr),
            context.getString(R.string.batch_ocr_compress),
            context.getString(R.string.batch_hidden),
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(context.resources.getQuantityString(R.plurals.batch_title, uris.size, uris.size))
            .setItems(labels) { _, which ->
                batchFiles = uris
                batchOperation = which
                Toast.makeText(context, R.string.batch_choose_folder, Toast.LENGTH_SHORT).show()
                batchFolder.launch(null)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Applies operation [op] to every file and writes the results into [tree]. */
    fun runBatch(uris: List<Uri>, op: Int, tree: Uri) {
        val progress = showProgress(R.string.batch_running)
        val suffix = context.getString(
            when (op) {
                0 -> R.string.batch_suffix_compressed
                1, 2 -> R.string.batch_suffix_searchable
                else -> R.string.batch_suffix_clean
            },
        )
        activity.lifecycleScope.launch {
            var failed = 0
            var done = 0
            withContext(Dispatchers.IO) {
                val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                uris.forEachIndexed { i, uri ->
                    if (progress.cancelled) return@withContext
                    progress.update(i, uris.size)
                    try {
                        val result = process(localCopy(uri), recognize = op == 1 || op == 2, compress = op == 0 || op == 2, removeHidden = op == 3)
                        try {
                            val name = "${baseName(displayName(uri))} ($suffix).pdf"
                            val target = DocumentsContract.createDocument(context.contentResolver, folder, "application/pdf", name)
                                ?: throw java.io.IOException("Cannot create $name")
                            copy(result, target)
                            done++
                        } finally {
                            result.delete()
                        }
                    } catch (e: Exception) {
                        failed++
                    }
                }
                progress.update(uris.size, uris.size)
            }
            progress.dialog.dismiss()
            val message = if (failed == 0) context.resources.getQuantityString(R.plurals.batch_done, done, done)
            else context.getString(R.string.batch_done_failed, done, failed)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private fun displayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"

    private fun padded(view: View) = android.widget.FrameLayout(context).apply {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(view)
    }
}
