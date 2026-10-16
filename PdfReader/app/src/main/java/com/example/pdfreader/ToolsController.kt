package com.example.pdfreader

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.pdfreader.core.BlankSize
import com.example.pdfreader.core.PageRangeException
import com.example.pdfreader.core.PageRanges
import com.example.pdfreader.core.StampMath
import com.example.pdfreader.core.StampPosition
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * Document tools (roadmap phase 3): merge, split, crop, resize, page numbers/headers/footers
 * and watermark. Must be created while the activity is being constructed (it registers for
 * activity results).
 */
class ToolsController(
    private val activity: AppCompatActivity,
    /** The open document: (uri identifying it, uri to read, display name, may overwrite). */
    private val current: () -> CurrentDocument?,
    private val openResult: (Uri, String) -> Unit,
    private val reloadCurrent: () -> Unit,
) {
    data class CurrentDocument(
        val uri: Uri, val contentUri: Uri, val name: String, val canOverwrite: Boolean, val pageCount: Int,
        /** The file itself is encrypted (what is shown is a decrypted private copy). */
        val isEncrypted: Boolean = false,
        /** The original file may be replaced (for encrypted files [canOverwrite] is false). */
        val canWriteOriginal: Boolean = canOverwrite,
    )

    private val context get() = activity

    // --- Result launchers -------------------------------------------------------------------

    private var mergeFiles = mutableListOf<Pair<Uri, String>>()
    private var refreshMergeList: (() -> Unit)? = null
    private val addMergeFiles = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { mergeFiles += it to displayName(it) }
        refreshMergeList?.invoke()
    }

    /** A finished result waiting for the user to choose where to save it. */
    private var pendingFile: File? = null
    private var pendingOpensAfterSave = true
    private val saveAs = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val file = pendingFile ?: return@registerForActivityResult
        pendingFile = null
        if (uri == null) {
            file.delete()
            return@registerForActivityResult
        }
        activity.lifecycleScope.launch {
            try {
                copyToUri(file, uri)
                if (pendingOpensAfterSave) openResult(uri, displayName(uri))
                Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.copy_failed, Toast.LENGTH_LONG).show()
            } finally {
                file.delete()
            }
        }
    }

    private var pendingParts: List<File> = emptyList()
    private val saveZip = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val parts = pendingParts
        pendingParts = emptyList()
        if (uri == null) return@registerForActivityResult
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { DocumentTools.zip(parts, it) }
                }
                Toast.makeText(context, context.getString(R.string.split_done, parts.size), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.copy_failed, Toast.LENGTH_LONG).show()
            } finally {
                parts.forEach { it.delete() }
            }
        }
    }
    private val chooseFolder = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val parts = pendingParts
        pendingParts = emptyList()
        if (tree == null) return@registerForActivityResult
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    for (part in parts) {
                        val target = DocumentsContract.createDocument(context.contentResolver, folder, "application/pdf", part.name)
                            ?: throw java.io.IOException("Cannot create ${part.name}")
                        copyToUriBlocking(part, target)
                    }
                }
                Toast.makeText(context, context.getString(R.string.split_done, parts.size), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.copy_failed, Toast.LENGTH_LONG).show()
            } finally {
                parts.forEach { it.delete() }
            }
        }
    }

    // --- Merge ------------------------------------------------------------------------------

    fun showMerge() {
        val doc = current() ?: return
        mergeFiles = mutableListOf(doc.contentUri to doc.name)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun render() {
            list.removeAllViews()
            mergeFiles.forEachIndexed { i, (_, name) ->
                list.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        text = context.getString(R.string.merge_item, i + 1, name)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(iconButton(R.drawable.ic_chevron_up, context.getString(R.string.move_up, name), enabled = i > 0) {
                        mergeFiles.add(i - 1, mergeFiles.removeAt(i))
                        render()
                    })
                    addView(iconButton(R.drawable.ic_chevron_down, context.getString(R.string.move_down, name), enabled = i < mergeFiles.lastIndex) {
                        mergeFiles.add(i + 1, mergeFiles.removeAt(i))
                        render()
                    })
                    addView(iconButton(R.drawable.ic_close, context.getString(R.string.remove_file, name), enabled = mergeFiles.size > 1) {
                        mergeFiles.removeAt(i)
                        render()
                    })
                })
            }
        }
        refreshMergeList = ::render
        render()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.merge_title)
            .setView(padded(ScrollView(context).apply { addView(list) }))
            .setPositiveButton(R.string.merge_action, null)
            .setNeutralButton(R.string.merge_add, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        // Keep the dialog open while adding files.
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { addMergeFiles.launch(arrayOf("application/pdf")) }
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (mergeFiles.size < 2) {
                Toast.makeText(context, R.string.merge_need_two, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            val files = mergeFiles.toList()
            produce(context.getString(R.string.merged_name)) { output -> DocumentTools.merge(context, files, output) }
        }
        dialog.setOnDismissListener { refreshMergeList = null }
    }

    // --- Split ------------------------------------------------------------------------------

    fun showSplit() {
        val doc = current() ?: return
        val group = RadioGroup(context)
        val ranges = radio(group, R.string.split_by_ranges, checked = true)
        val rangesInput = input(context.getString(R.string.split_ranges_hint), InputType.TYPE_CLASS_TEXT).apply { setText("1-${(doc.pageCount + 1) / 2}, ${(doc.pageCount + 1) / 2 + 1}-${doc.pageCount}") }
        group.addView(rangesInput)
        val everyN = radio(group, R.string.split_every_n)
        val nInput = input(context.getString(R.string.split_n_hint), InputType.TYPE_CLASS_NUMBER).apply { setText("1") }
        group.addView(nInput)
        val byBookmarks = radio(group, R.string.split_by_bookmarks)
        val singles = radio(group, R.string.split_singles)
        val outputGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val asZip = radio(outputGroup, R.string.split_output_zip, checked = true)
        radio(outputGroup, R.string.split_output_folder)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(group)
            addView(TextView(context).apply { setText(R.string.split_output) ; setPadding(0, 24, 0, 0) })
            addView(outputGroup)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.split_title)
            .setView(padded(ScrollView(context).apply { addView(content) }))
            .setPositiveButton(R.string.split_action) { _, _ ->
                val zip = asZip.isChecked
                activity.lifecycleScope.launch {
                    try {
                        val parts = when {
                            ranges.isChecked -> PageRanges.parse(rangesInput.text.toString(), doc.pageCount)
                            everyN.isChecked -> PageRanges.everyN(doc.pageCount, (nInput.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1))
                            byBookmarks.isChecked -> {
                                val starts = withContext(Dispatchers.IO) {
                                    PdfStructure(context, doc.contentUri).use { s -> s.outline().mapNotNull { it.page } }
                                }
                                PageRanges.fromStarts(doc.pageCount, starts)
                            }
                            singles.isChecked -> PageRanges.everyN(doc.pageCount, 1)
                            else -> return@launch
                        }
                        val dir = File(context.cacheDir, "split-${UUID.randomUUID()}")
                        val files = withContext(Dispatchers.IO) { DocumentTools.split(context, doc.contentUri, parts, doc.name, dir) }
                        pendingParts = files
                        if (zip) saveZip.launch(doc.name.removeSuffix(".pdf") + ".zip") else chooseFolder.launch(null)
                    } catch (e: PageRangeException) {
                        Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, R.string.copy_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Crop and resize -------------------------------------------------------------------

    fun showCrop() {
        val doc = current() ?: return
        val fields = listOf(R.string.crop_top, R.string.crop_bottom, R.string.crop_left, R.string.crop_right).map { label ->
            input(context.getString(label), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply { setText("10") }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { setText(R.string.crop_message) })
            fields.forEach(::addView)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.crop_title)
            .setView(padded(content))
            .setPositiveButton(R.string.apply) { _, _ ->
                val (top, bottom, left, right) = fields.map { StampMath.mmToPoints(it.text.toString().toFloatOrNull() ?: 0f) }
                modify(doc) { output -> DocumentTools.crop(context, doc.contentUri, left, top, right, bottom, output) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun showResize() {
        val doc = current() ?: return
        val sizes = listOf(BlankSize.A4, BlankSize.LETTER)
        val labels = arrayOf(context.getString(R.string.size_a4), context.getString(R.string.size_letter))
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.resize_title)
            .setItems(labels) { _, which ->
                val size = sizes[which]
                modify(doc) { output -> DocumentTools.resize(context, doc.contentUri, size.width, size.height, output) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Page numbers, headers and footers --------------------------------------------------

    fun showStamp() {
        val doc = current() ?: return
        val header = input(context.getString(R.string.stamp_header), InputType.TYPE_CLASS_TEXT)
        val footer = input(context.getString(R.string.stamp_footer), InputType.TYPE_CLASS_TEXT)
        val format = input(context.getString(R.string.stamp_format), InputType.TYPE_CLASS_TEXT).apply { setText(R.string.stamp_format_default) }
        val start = input(context.getString(R.string.stamp_start), InputType.TYPE_CLASS_NUMBER).apply { setText("1") }
        val positions = StampPosition.entries
        val positionNames = context.resources.getStringArray(R.array.stamp_positions)
        val position = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, positionNames)
            setSelection(positions.indexOf(StampPosition.BOTTOM_CENTER))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(footer)
            addView(format)
            addView(TextView(context).apply { setText(R.string.stamp_format_help) })
            addView(start)
            addView(TextView(context).apply { setText(R.string.stamp_position); setPadding(0, 16, 0, 0) })
            addView(position)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.stamp_title)
            .setView(padded(ScrollView(context).apply { addView(content) }))
            .setPositiveButton(R.string.apply) { _, _ ->
                val spec = StampSpec(
                    header = header.text.toString(),
                    footer = footer.text.toString(),
                    pageNumber = format.text.toString(),
                    pageNumberPosition = positions[position.selectedItemPosition],
                    startNumber = start.text.toString().toIntOrNull() ?: 1,
                    date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date()),
                    fileName = doc.name,
                )
                modify(doc) { output -> DocumentTools.stamp(context, doc.contentUri, spec, output) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Watermark --------------------------------------------------------------------------

    fun showWatermark() {
        val doc = current() ?: return
        val text = input(context.getString(R.string.watermark_text), InputType.TYPE_CLASS_TEXT).apply { setText(R.string.watermark_default) }
        val opacity = Slider(context).apply { valueFrom = 5f; valueTo = 100f; stepSize = 5f; value = 25f }
        val angle = Slider(context).apply { valueFrom = -90f; valueTo = 90f; stepSize = 15f; value = 45f }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text)
            addView(TextView(context).apply { setText(R.string.watermark_opacity); setPadding(0, 16, 0, 0) })
            addView(opacity)
            addView(TextView(context).apply { setText(R.string.watermark_angle) })
            addView(angle)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.watermark_title)
            .setView(padded(content))
            .setPositiveButton(R.string.apply) { _, _ ->
                val spec = WatermarkSpec(text.text.toString().ifBlank { context.getString(R.string.watermark_default) }, opacity.value / 100f, angle.value)
                modify(doc) { output -> DocumentTools.watermark(context, doc.contentUri, spec, output) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Security (phase 6) -----------------------------------------------------------------

    /** Adds an open password and/or restricts printing, copying and editing (AES-256). */
    fun showProtect() {
        val doc = current() ?: return
        val password = passwordInput(R.id.protect_password_input, R.string.protect_open_password)
        val confirm = passwordInput(R.id.protect_confirm_input, R.string.protect_confirm_password)
        val restrict = android.widget.CheckBox(context).apply { id = R.id.protect_restrict_check; setText(R.string.protect_restrict) }
        val owner = passwordInput(R.id.protect_owner_input, R.string.protect_owner_password)
        val allowPrint = android.widget.CheckBox(context).apply { setText(R.string.protect_allow_print) }
        val allowCopy = android.widget.CheckBox(context).apply { setText(R.string.protect_allow_copy) }
        val allowEdit = android.widget.CheckBox(context).apply { setText(R.string.protect_allow_edit) }
        val restrictions = listOf(owner, allowPrint, allowCopy, allowEdit)
        fun update() = restrictions.forEach { it.visibility = if (restrict.isChecked) View.VISIBLE else View.GONE }
        restrict.setOnCheckedChangeListener { _, _ -> update() }
        update()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { setText(R.string.protect_message) })
            listOf(password, confirm, restrict).forEach(::addView)
            restrictions.forEach(::addView)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.protect_title)
            .setView(ScrollView(context).apply { addView(padded(content)) })
            .setPositiveButton(R.string.protect_action, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val open = password.text.toString()
            if (open != confirm.text.toString()) {
                confirm.error = context.getString(R.string.protect_mismatch)
                return@setOnClickListener
            }
            if (open.isEmpty() && !restrict.isChecked) {
                password.error = context.getString(R.string.protect_need_password)
                return@setOnClickListener
            }
            val permissions = if (restrict.isChecked) Permissions(allowPrint.isChecked, allowCopy.isChecked, allowEdit.isChecked) else Permissions()
            val ownerPassword = if (restrict.isChecked) owner.text.toString() else ""
            dialog.dismiss()
            modify(doc, overwrite = doc.canWriteOriginal) { output ->
                SecurityTools.load(context, doc.contentUri).use { pdf ->
                    SecurityTools.protect(pdf, open, ownerPassword, permissions)
                    pdf.save(output)
                }
            }
        }
    }

    /** Removes the password and restrictions, given the password. */
    fun showRemovePassword() {
        val doc = current() ?: return
        val password = passwordInput(R.id.unprotect_password_input, R.string.unprotect_password)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.unprotect_title)
            .setMessage(R.string.unprotect_message)
            .setView(padded(password))
            .setPositiveButton(R.string.unprotect_action, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = password.text.toString()
            activity.lifecycleScope.launch {
                val file = tempFile()
                val error = withContext(Dispatchers.IO) {
                    try {
                        file.outputStream().use { SecurityTools.removePassword(context, doc.uri, text, it) }
                        null
                    } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                        R.string.password_wrong
                    } catch (e: SecurityTools.NotAllowedException) {
                        R.string.unprotect_need_owner
                    } catch (e: Exception) {
                        R.string.tool_failed
                    }
                }
                if (error != null) {
                    file.delete()
                    password.error = context.getString(error)
                    return@launch
                }
                dialog.dismiss()
                modify(doc, overwrite = doc.canWriteOriginal) { output -> file.inputStream().use { it.copyTo(output) }; file.delete() }
            }
        }
    }

    /** Shows metadata, attachments, scripts and layers, and removes what the user picks. */
    fun showHiddenData() {
        val doc = current() ?: return
        activity.lifecycleScope.launch {
            val data = try {
                withContext(Dispatchers.IO) { SecurityTools.load(context, doc.contentUri).use { SecurityTools.inspect(it) } }
            } catch (e: Exception) {
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fun line(text: String) = content.addView(TextView(context).apply { this.text = text; setPadding(0, 4, 0, 4) })
            fun check(label: String, present: Boolean) = android.widget.CheckBox(context).apply {
                text = label
                isChecked = present
                isEnabled = present
                content.addView(this)
            }
            if (data.info.isEmpty() && !data.hasXmpMetadata) line(context.getString(R.string.hidden_no_metadata))
            for ((key, value) in data.info) line("$key: $value")
            val metadata = check(context.getString(R.string.hidden_metadata), data.info.isNotEmpty() || data.hasXmpMetadata)
            val files = check(context.resources.getQuantityString(R.plurals.hidden_files, data.embeddedFiles, data.embeddedFiles), data.embeddedFiles > 0)
            val scripts = check(context.resources.getQuantityString(R.plurals.hidden_scripts, data.scripts, data.scripts), data.scripts > 0)
            if (data.layers > 0) line(context.getString(R.string.hidden_layers, data.layers, data.hiddenLayers))
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.hidden_title)
                .setView(ScrollView(context).apply { addView(padded(content)) })
                .setPositiveButton(R.string.hidden_remove) { _, _ ->
                    val what = HiddenDataRemoval(metadata.isChecked, files.isChecked, scripts.isChecked)
                    if (what == HiddenDataRemoval(false, false, false)) return@setPositiveButton
                    modify(doc) { output ->
                        SecurityTools.load(context, doc.contentUri).use { pdf ->
                            SecurityTools.removeHiddenData(pdf, what)
                            if (pdf.isEncrypted) pdf.isAllSecurityToBeRemoved = true
                            pdf.save(output)
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun passwordInput(id: Int, hint: Int) = EditText(context).apply {
        this.id = id
        setHint(hint)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    // --- Saving results ---------------------------------------------------------------------

    /** Runs [write] into a temp file, then saves it in place (and reloads) or as a copy. */
    private fun modify(doc: CurrentDocument, overwrite: Boolean = doc.canOverwrite, write: (OutputStream) -> Unit) {
        activity.lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { tempFile().also { f -> f.outputStream().use(write) } }
            } catch (e: Exception) {
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (overwrite) {
                try {
                    withContext(Dispatchers.IO) { VersionStore.keep(context, doc.uri) }
                    copyToUri(file, doc.uri)
                    file.delete()
                    Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
                    reloadCurrent()
                    return@launch
                } catch (e: Exception) {
                    // Fall through to saving a copy.
                }
            }
            pendingFile = file
            pendingOpensAfterSave = true
            saveAs.launch(DocumentExport.safeFileName(doc.name))
        }
    }

    /** Runs [write] into a temp file, then asks where to save it and opens it. */
    private fun produce(defaultName: String, write: (OutputStream) -> Unit) {
        activity.lifecycleScope.launch {
            try {
                pendingFile = withContext(Dispatchers.IO) { tempFile().also { f -> f.outputStream().use(write) } }
                pendingOpensAfterSave = true
                saveAs.launch(DocumentExport.safeFileName(defaultName))
            } catch (e: Exception) {
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun tempFile() = File(context.cacheDir, "tool-${UUID.randomUUID()}.pdf")

    private suspend fun copyToUri(file: File, uri: Uri) = withContext(Dispatchers.IO) { copyToUriBlocking(file, uri) }

    private fun copyToUriBlocking(file: File, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("Cannot write $uri")
        output.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    // --- Small view helpers -----------------------------------------------------------------

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

    private fun input(hint: String, type: Int) = EditText(context).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
    }

    private fun radio(group: RadioGroup, label: Int, checked: Boolean = false) = RadioButton(context).apply {
        id = View.generateViewId()
        setText(label)
        group.addView(this)
        if (checked) group.check(id)
    }

    private fun iconButton(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        contentDescription = description
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.3f
        background = null
        val size = (40 * context.resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
        setOnClickListener { onClick() }
    }
}
