package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.pdfreader.core.Annotation
import com.example.pdfreader.core.AnnotationHistory
import com.example.pdfreader.core.AnnotationJson
import com.example.pdfreader.core.EditDraft
import com.example.pdfreader.core.FieldKind
import com.example.pdfreader.core.FormField
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * The annotations of the open document while it is being read and edited.
 *
 * Annotations this app understands are taken out of the pages the screen renders (see
 * [load]) and drawn live by [AnnotationPainter] instead, so they can be moved, edited and
 * undone instantly. Every change is kept as a local draft until it is saved into the PDF, so
 * nothing is lost if the app is closed first.
 */
class AnnotationSession(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Identifies the document for drafts (its original uri). */
    documentUri: Uri,
    /** The annotations stored in the file. */
    private val saved: List<Annotation>,
    /** Unsaved edits left from an earlier session, waiting for the user to restore them. */
    var pendingDraft: EditDraft?,
    /** The interactive form's widgets, with the values stored in the file. */
    val formFields: List<FormField> = emptyList(),
) {
    val history = AnnotationHistory(saved)
    val items: List<Annotation> get() = history.items

    /** Called after every change (add, edit, undo…), on the main thread. */
    val listeners = mutableListOf<() -> Unit>()

    private val draftFile = draftFile(context, documentUri)
    private var draftJob: Job? = null

    /** Form values changed since the file was opened or saved: field name → value. */
    private val formValues = linkedMapOf<String, String>()
    val changedFormValues: Map<String, String> get() = formValues

    /** Unsaved changes, compared with the file. */
    val hasChanges: Boolean get() = history.items != saved || formValues.isNotEmpty()

    /** The current value of a field (as typed, or as stored in the file). */
    fun valueOf(field: FormField): String {
        val value = formValues[field.name] ?: return field.value
        return when (field.kind) {
            // Radio buttons share one value: the selected button's on-state.
            FieldKind.RADIO -> if (value == field.onValue) value else ""
            FieldKind.CHECKBOX -> if (value.isEmpty() || value == "Off") "" else field.onValue
            else -> value
        }
    }

    /** Whether [field]'s value differs from the file. */
    fun isChanged(field: FormField) = field.name in formValues

    fun setFieldValue(field: FormField, value: String) {
        val stored = formFields.filter { it.name == field.name }
        val original = when (field.kind) {
            FieldKind.RADIO -> stored.firstOrNull { it.value.isNotEmpty() }?.onValue.orEmpty()
            else -> field.value
        }
        val normalized = if (field.kind == FieldKind.CHECKBOX || field.kind == FieldKind.RADIO) value.takeUnless { it == "Off" }.orEmpty() else value
        if (normalized == original) formValues.remove(field.name) else formValues[field.name] = normalized
        listeners.forEach { it() }
        scheduleDraft()
    }

    fun add(a: Annotation) = change { history.add(a) }
    fun addAll(list: List<Annotation>) = change { history.addAll(list) }
    fun replace(a: Annotation) = change { history.replace(a) }
    fun remove(id: String) = change { history.remove(id) }
    fun removeAll(ids: Set<String>) = change { history.removeAll(ids) }
    fun undo() = change { history.undo() }
    fun redo() = change { history.redo() }

    fun restoreDraft() {
        val draft = pendingDraft ?: return
        pendingDraft = null
        val known = formFields.map { it.name }.toSet()
        formValues.putAll(draft.form.filterKeys { it in known })
        change { history.setAll(draft.annotations) }
    }

    fun discardDraft() {
        pendingDraft = null
        draftJob?.cancel()
        draftFile.delete()
    }

    /** Goes back to what the file contains. */
    fun discardChanges() {
        formValues.clear()
        change { history.setAll(saved) }
        discardDraft()
    }

    private fun change(block: () -> Unit) {
        block()
        listeners.forEach { it() }
        scheduleDraft()
    }

    /** Writes the draft shortly after the last change (or deletes it when back to the file). */
    private fun scheduleDraft() {
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(DRAFT_DELAY_MS)
            writeDraftNow()
        }
    }

    /** Stores (or clears) the draft right away, e.g. when the app goes to the background. */
    fun writeDraftNow() {
        draftJob?.cancel()
        val snapshot = EditDraft(history.items, LinkedHashMap(formValues))
        val unchanged = !hasChanges
        scope.launch(Dispatchers.IO) {
            if (unchanged) {
                draftFile.delete()
            } else {
                draftFile.parentFile?.mkdirs()
                val temp = File(draftFile.path + ".tmp")
                temp.writeText(AnnotationJson.encodeDraft(snapshot))
                temp.renameTo(draftFile)
            }
        }
    }

    /**
     * Writes the document at [source] with the current annotations and form values to
     * [output]. With [flatten] both become part of the page content and can no longer be edited.
     */
    fun writeTo(source: Uri, output: OutputStream, flatten: Boolean) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(source) ?: throw java.io.IOException("Cannot read $source")
        input.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }.use { doc ->
            FormIO.create(doc, history.items.filterIsInstance<Annotation.Field>())
            FormIO.write(doc, formValues, flatten)
            AnnotationIO.write(doc, history.items)
            if (flatten) AnnotationIO.flatten(doc)
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            doc.save(output)
        }
    }

    /** After a successful save: the draft is no longer needed. */
    fun onSaved() {
        history.markSaved()
        formValues.clear()
        discardDraft()
    }

    /** The annotations stored in a file, and a copy of it without them for the screen to render. */
    class Loaded(
        val annotations: List<Annotation>,
        val renderFile: File?,
        val draft: EditDraft?,
        val formFields: List<FormField> = emptyList(),
    )

    companion object {
        private const val DRAFT_DELAY_MS = 400L

        /**
         * Reads the annotations of [contentUri]. When it has any, also writes a copy without
         * them for [PdfDocument] to render (they are drawn on top instead). Never throws: a
         * file PdfBox can't read simply has no editable annotations.
         */
        fun load(context: Context, documentUri: Uri, contentUri: Uri): Loaded {
            val draft = draftFile(context, documentUri).takeIf { it.exists() }
                ?.let { runCatching { AnnotationJson.decodeDraft(it.readText()) }.getOrNull() }
            return try {
                PDFBoxResourceLoader.init(context.applicationContext)
                val input = context.contentResolver.openInputStream(contentUri) ?: return Loaded(emptyList(), null, draft)
                input.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }.use { doc ->
                    val fields = runCatching { FormIO.read(doc) }.getOrDefault(emptyList())
                    val hasManaged = doc.pages.any { page -> page.annotations.any(AnnotationIO::isManaged) }
                    if (!hasManaged) return Loaded(emptyList(), null, draft?.takeIf { it.size > 0 }, fields)
                    val annotations = AnnotationIO.read(doc, imageDir(context))
                    AnnotationIO.stripManaged(doc)
                    if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                    val dir = File(context.cacheDir, "render").apply { mkdirs() }
                    val file = File(dir, "${UUID.randomUUID()}.pdf")
                    file.outputStream().use { doc.save(it) }
                    Loaded(annotations, file, draft?.takeIf { it.annotations != annotations || it.form.isNotEmpty() }, fields)
                }
            } catch (e: Exception) {
                Loaded(emptyList(), null, draft)
            } catch (e: OutOfMemoryError) {
                Loaded(emptyList(), null, draft)
            }
        }

        fun imageDir(context: Context) = File(context.filesDir, "annotation-images")

        private fun draftFile(context: Context, uri: Uri): File {
            val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            val name = digest.take(16).joinToString("") { "%02x".format(it) }
            return File(File(context.filesDir, "annotation-drafts"), "$name.json")
        }

        /**
         * Copies a picked picture into app storage (at most 2048 px, PNG) so it survives
         * until saved, and returns the file with its pixel size.
         */
        fun importImage(context: Context, uri: Uri): Triple<File, Int, Int>? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val file = File(imageDir(context).apply { mkdirs() }, "${UUID.randomUUID()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val size = Triple(file, bitmap.width, bitmap.height)
            bitmap.recycle()
            return size
        }
    }
}
