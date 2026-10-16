package com.example.pdfreader

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.pdfreader.core.Change
import com.example.pdfreader.core.Compare
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Phase 8 extras for the open document: version history (open, compare, restore), comparing
 * with another PDF, e-invoice (ZUGFeRD / XRechnung) data and an accessibility check.
 * Create it while the activity is being constructed (it registers for activity results).
 */
class DocumentExtrasController(
    private val activity: AppCompatActivity,
    private val current: () -> ToolsController.CurrentDocument?,
    private val convert: ConvertController,
    private val showPassage: (page: Int, phrase: String) -> Unit,
    private val openResult: (Uri, String) -> Unit,
    private val reloadCurrent: () -> Unit,
) {
    private val context get() = activity

    /** The open dialog (for tests). */
    var dialog: AlertDialog? = null
        private set

    private val density get() = context.resources.displayMetrics.density

    // --- Version history --------------------------------------------------------------------------

    fun showVersions() {
        val doc = current() ?: return
        val versions = VersionStore.list(context, doc.uri)
        if (versions.isEmpty()) {
            dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.versions_title)
                .setMessage(R.string.versions_none)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val labels = versions.map { "${format.format(Date(it.savedAt))} · ${Formatter.formatShortFileSize(context, it.size)}" }.toTypedArray()
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.versions_title)
            .setItems(labels) { _, which -> versionMenu(doc, versions[which], labels[which]) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun versionMenu(doc: ToolsController.CurrentDocument, version: Version, label: String) {
        val options = arrayOf(context.getString(R.string.versions_open), context.getString(R.string.versions_compare), context.getString(R.string.versions_restore))
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openVersion(doc, version)
                    1 -> compare(doc, Uri.fromFile(version.file), context.getString(R.string.versions_earlier))
                    else -> confirmRestore(doc, version)
                }
            }
            .show()
    }

    private fun openVersion(doc: ToolsController.CurrentDocument, version: Version) {
        val name = "${convert.baseName(doc.name)} (${DateUtils.formatDateTime(context, version.savedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NUMERIC_DATE).replace('/', '-').replace(':', '.')}).pdf"
        val copy = File(File(context.cacheDir, "shared").apply { mkdirs() }, name)
        version.file.copyTo(copy, overwrite = true)
        openResult(FileProvider.getUriForFile(context, "${context.packageName}.files", copy), name)
    }

    private fun confirmRestore(doc: ToolsController.CurrentDocument, version: Version) {
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.versions_restore)
            .setMessage(R.string.versions_restore_message)
            .setPositiveButton(R.string.versions_restore_action) { _, _ ->
                activity.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            // The current content becomes a version too, so restoring can be undone.
                            val restored = File(context.cacheDir, "restore-${version.savedAt}.pdf")
                            version.file.copyTo(restored, overwrite = true)
                            VersionStore.keep(context, doc.uri)
                            val out = context.contentResolver.openOutputStream(doc.uri, "wt") ?: throw java.io.IOException("Cannot write")
                            out.use { o -> restored.inputStream().use { it.copyTo(o) } }
                            restored.delete()
                        }.isSuccess
                    }
                    Toast.makeText(context, if (ok) R.string.versions_restored else R.string.copy_failed, Toast.LENGTH_SHORT).show()
                    if (ok) reloadCurrent()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Compare ----------------------------------------------------------------------------------

    private val pickOther = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val doc = current() ?: return@registerForActivityResult
        if (uri != null) compare(doc, uri, displayName(uri))
    }

    fun showCompare() = pickOther.launch(arrayOf("application/pdf"))

    /** Compares [other] (the earlier version) with the open document. */
    fun compare(doc: ToolsController.CurrentDocument, other: Uri, otherName: String) {
        activity.lifecycleScope.launch {
            val changes = try {
                withContext(Dispatchers.IO) {
                    val old = SecurityTools.load(context, other).use { ConvertTools.pageTexts(it) }
                    val new = SecurityTools.load(context, doc.contentUri).use { ConvertTools.pageTexts(it) }
                    Compare.diff(old, new)
                }
            } catch (e: Exception) {
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val list = list()
            list.addView(note(context.resources.getQuantityString(R.plurals.compare_summary, changes.size, changes.size, otherName)))
            for (c in changes.take(300)) {
                val (sign, color, text) = when (c.kind) {
                    Change.Kind.ADDED -> Triple("+", 0xFF2E7D32.toInt(), c.text)
                    Change.Kind.REMOVED -> Triple("−", 0xFFC62828.toInt(), c.text)
                    Change.Kind.CHANGED -> Triple("~", 0xFF1565C0.toInt(), "“${c.before}” → “${c.text}”")
                }
                list.addView(row("$sign $text", context.getString(R.string.page_short, c.newPage + 1), color) {
                    showPassage(c.newPage, if (c.kind == Change.Kind.REMOVED) "" else c.text.split(' ').take(4).joinToString(" "))
                })
            }
            val report = Compare.report(otherName, doc.name, changes)
            dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.compare_title)
                .setView(ScrollView(context).apply { addView(list) })
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.summary_export) { _, _ ->
                    activity.lifecycleScope.launch {
                        val file = withContext(Dispatchers.IO) { convert.temp("txt").also { it.writeText(report) } }
                        convert.saveAs(file, "text/plain", "${convert.baseName(doc.name)} changes.txt", open = false)
                    }
                }
                .show()
        }
    }

    // --- E-invoice --------------------------------------------------------------------------------

    fun showEInvoice() {
        val doc = current() ?: return
        activity.lifecycleScope.launch {
            val invoice = try {
                withContext(Dispatchers.IO) { SecurityTools.load(context, doc.contentUri).use { EInvoiceReader.read(it) } }
            } catch (e: Exception) {
                null
            }
            val list = list()
            if (invoice == null) {
                list.addView(note(context.getString(R.string.einvoice_none)))
            } else {
                for ((label, value) in invoice.fields()) {
                    list.addView(TextView(context).apply {
                        text = "$label: $value"
                        setTextIsSelectable(true)
                        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                    })
                }
            }
            dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.einvoice_title)
                .setView(ScrollView(context).apply { addView(list) })
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    // --- Accessibility ----------------------------------------------------------------------------

    fun showAccessibility() {
        val doc = current() ?: return
        activity.lifecycleScope.launch {
            val results = try {
                withContext(Dispatchers.IO) { SecurityTools.load(context, doc.contentUri).use { AccessibilityCheck.run(it) } }
            } catch (e: Exception) {
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val list = list()
            val passed = results.count { it.passed }
            list.addView(note(context.getString(R.string.accessibility_summary, passed, results.size)))
            for (r in results) {
                list.addView(TextView(context).apply {
                    text = (if (r.passed) "✓ " else "✗ ") + r.check + if (r.detail.isNotBlank()) "\n    ${r.detail}" else ""
                    setTextColor(if (r.passed) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
                    setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                })
            }
            dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.accessibility_title)
                .setView(ScrollView(context).apply { addView(list) })
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    // --- Views ------------------------------------------------------------------------------------

    private fun list() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    private fun note(text: String) = TextView(context).apply {
        this.text = text
        alpha = 0.75f
        setPadding(0, 0, 0, (8 * density).toInt())
    }

    private fun row(text: String, page: String, color: Int, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundResource(R.drawable.panel_row_bg)
        val pad = (6 * density).toInt()
        setPadding(0, pad, 0, pad)
        addView(TextView(context).apply {
            this.text = text
            setTextColor(color)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"
}
