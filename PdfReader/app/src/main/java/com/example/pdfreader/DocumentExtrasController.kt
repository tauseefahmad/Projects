package com.example.pdfreader

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
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
    /** Whether the open document has unsaved annotations or form entries. */
    private val hasUnsavedChanges: () -> Boolean = { false },
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

    // --- Digital signatures -------------------------------------------------------------------------

    private val importCertificate = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askImportPassword(uri)
    }

    /** Signs the document with a certificate (PAdES), after choosing or creating one. */
    fun showSignDigitally() {
        if (current() == null) return
        if (hasUnsavedChanges()) {
            Toast.makeText(context, R.string.dsig_save_first, Toast.LENGTH_LONG).show()
            return
        }
        val stored = DigitalSignatures.stored(context)
        val labels = stored.map { it.nameWithoutExtension } + listOf(context.getString(R.string.dsig_create), context.getString(R.string.dsig_import))
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dsig_choose)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < stored.size -> askSignDetails(stored[which])
                    which == stored.size -> createCertificate()
                    else -> importCertificate.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*"))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun field(hint: Int, password: Boolean = false, id: Int = View.NO_ID) = android.widget.EditText(context).apply {
        this.id = id
        setHint(hint)
        isSingleLine = true
        inputType = if (password) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_CLASS_TEXT
    }

    private fun form(vararg views: View) = list().apply { views.forEach(::addView) }

    private fun createCertificate() {
        val name = field(R.string.dsig_name, id = R.id.dsig_name_input).apply { setText(ProfileStore(context).profile.fullName) }
        val email = field(R.string.dsig_email).apply { setText(ProfileStore(context).profile.email) }
        val password = field(R.string.dsig_new_password, password = true, id = R.id.dsig_new_password_input)
        val d = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dsig_create)
            .setView(ScrollView(context).apply { addView(form(note(context.getString(R.string.dsig_create_note)), name, email, password)) })
            .setPositiveButton(R.string.dsig_create_action, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog = d
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (name.text.isBlank()) { name.error = context.getString(R.string.field_required); return@setOnClickListener }
            if (password.text.length < 4) { password.error = context.getString(R.string.dsig_password_short); return@setOnClickListener }
            d.dismiss()
            val pw = password.text.toString().toCharArray()
            activity.lifecycleScope.launch {
                val file = try {
                    withContext(Dispatchers.Default) { DigitalSignatures.createPersonal(context, name.text.toString().trim(), email.text.toString().trim(), pw) }
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                    return@launch
                }
                askSignDetails(file, pw)
            }
        }
    }

    private fun askImportPassword(uri: Uri) {
        val password = field(R.string.dsig_password, password = true)
        val d = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dsig_import)
            .setView(form(password))
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog = d
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pw = password.text.toString().toCharArray()
            activity.lifecycleScope.launch {
                val file = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        DigitalSignatures.import(context, bytes, pw)
                    }.getOrNull()
                }
                if (file == null) {
                    password.error = context.getString(R.string.dsig_bad_certificate)
                } else {
                    d.dismiss()
                    askSignDetails(file, pw)
                }
            }
        }
    }

    /** Asks for the certificate password (unless known), reason and place, then signs. */
    fun askSignDetails(certificate: File, knownPassword: CharArray? = null) {
        val doc = current() ?: return
        val password = field(R.string.dsig_password, password = true, id = R.id.dsig_password_input)
        val reason = field(R.string.dsig_reason, id = R.id.dsig_reason_input)
        val location = field(R.string.dsig_location)
        val views = listOfNotNull(note(context.getString(R.string.dsig_note)), password.takeIf { knownPassword == null }, reason, location)
        val d = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.dsig_sign_as, certificate.nameWithoutExtension))
            .setView(ScrollView(context).apply { addView(form(*views.toTypedArray())) })
            .setPositiveButton(R.string.dsig_sign, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog = d
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pw = knownPassword ?: password.text.toString().toCharArray()
            activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val identity = runCatching { DigitalSignatures.load(certificate, pw) }.getOrNull() ?: return@withContext null
                    runCatching {
                        val source = convert.temp("pdf")
                        context.contentResolver.openInputStream(doc.contentUri)!!.use { i -> source.outputStream().use { i.copyTo(it) } }
                        val out = convert.temp("pdf")
                        try {
                            out.outputStream().use { DigitalSignatures.sign(context, source, identity, reason.text.toString().trim(), location.text.toString().trim(), it) }
                        } finally {
                            source.delete()
                        }
                        out
                    }
                }
                when {
                    result == null -> password.error = context.getString(R.string.password_wrong)
                    result.isFailure -> {
                        d.dismiss()
                        Toast.makeText(context, R.string.dsig_failed, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        d.dismiss()
                        convert.replaceOrCopy(doc, result.getOrThrow())
                    }
                }
            }
        }
    }

    /** Lists the digital signatures in the document and whether each is intact. */
    fun showSignatures() {
        val doc = current() ?: return
        activity.lifecycleScope.launch {
            val checks = try {
                withContext(Dispatchers.IO) {
                    val copy = convert.temp("pdf")
                    try {
                        context.contentResolver.openInputStream(doc.uri)!!.use { i -> copy.outputStream().use { i.copyTo(it) } }
                        DigitalSignatures.verify(context, copy)
                    } finally {
                        copy.delete()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            val list = list()
            if (checks.isEmpty()) list.addView(note(context.getString(R.string.dsig_none)))
            for (c in checks) {
                val status = when {
                    !c.intact -> context.getString(R.string.dsig_broken)
                    !c.coversWholeFile -> context.getString(R.string.dsig_changed_after)
                    c.selfSigned -> context.getString(R.string.dsig_valid_self)
                    else -> context.getString(R.string.dsig_valid)
                }
                list.addView(TextView(context).apply {
                    text = listOfNotNull(
                        (if (c.intact && c.coversWholeFile) "✓ " else "✗ ") + c.signer,
                        c.signedAt?.let { format.format(it.time) },
                        c.reason.takeIf { it.isNotBlank() },
                        status,
                    ).joinToString("\n")
                    setTextColor(if (c.intact && c.coversWholeFile) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
                    setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                })
            }
            dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dsig_check_title)
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
