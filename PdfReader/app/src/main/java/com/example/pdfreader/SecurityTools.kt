package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.IdentityHashMap
import java.util.UUID

/** What a protected file allows without the owner (permissions) password. */
data class Permissions(val print: Boolean = true, val copy: Boolean = true, val edit: Boolean = true)

/** Data in a document that isn't visible on its pages. */
data class HiddenData(
    /** Document information entries (Title, Author, Producer, dates…) that are set. */
    val info: Map<String, String>,
    val hasXmpMetadata: Boolean,
    val embeddedFiles: Int,
    val scripts: Int,
    val layers: Int,
    val hiddenLayers: Int,
) {
    val isEmpty get() = info.isEmpty() && !hasXmpMetadata && embeddedFiles == 0 && scripts == 0
}

/** What to remove with [SecurityTools.removeHiddenData]. */
data class HiddenDataRemoval(val metadata: Boolean = true, val embeddedFiles: Boolean = true, val scripts: Boolean = true)

/**
 * Phase 6 tools that run entirely on the phone: passwords and permissions, removing hidden
 * data, and turning redacted pages into images so nothing under a redaction survives.
 */
object SecurityTools {

    fun load(context: Context, uri: Uri, password: String = ""): PDDocument {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
        return input.use { PDDocument.load(it, password, MemoryUsageSetting.setupTempFileOnly()) }
    }

    // --- Passwords ---------------------------------------------------------------------------

    /**
     * Encrypts with AES-256. [openPassword] is needed to open the file (blank: anyone can open
     * it). [permissions] restrict printing, copying and editing for everyone without
     * [ownerPassword]; without an owner password a random one is used, so nobody can lift them.
     */
    fun protect(doc: PDDocument, openPassword: String, ownerPassword: String, permissions: Permissions) {
        val access = AccessPermission().apply {
            setCanPrint(permissions.print)
            setCanPrintFaithful(permissions.print)
            setCanExtractContent(permissions.copy)
            setCanModify(permissions.edit)
            setCanModifyAnnotations(permissions.edit)
            setCanFillInForm(permissions.edit)
            setCanAssembleDocument(permissions.edit)
            setCanExtractForAccessibility(true)
        }
        val unrestricted = permissions == Permissions()
        val owner = ownerPassword.ifEmpty { if (unrestricted) openPassword.ifEmpty { UUID.randomUUID().toString() } else UUID.randomUUID().toString() }
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        val policy = StandardProtectionPolicy(owner, openPassword, access).apply { encryptionKeyLength = 256 }
        doc.protect(policy)
    }

    /** Why a password can't be removed. */
    class NotAllowedException : IOException("The owner password is needed")

    /**
     * Opens [uri] with [password] and writes it without any encryption. Allowed with the owner
     * password, or with the open password when the file restricts nothing.
     * @throws InvalidPasswordException for a wrong password, [NotAllowedException] when the
     * password opens the file but the owner password is needed to lift its restrictions.
     */
    fun removePassword(context: Context, uri: Uri, password: String, output: OutputStream) {
        load(context, uri, password).use { doc ->
            if (!doc.isEncrypted) {
                doc.save(output)
                return
            }
            val p = doc.currentAccessPermission
            val unrestricted = p.canPrint() && p.canModify() && p.canExtractContent() && p.canModifyAnnotations() &&
                p.canFillInForm() && p.canAssembleDocument()
            if (!p.isOwnerPermission && !unrestricted) throw NotAllowedException()
            doc.isAllSecurityToBeRemoved = true
            doc.save(output)
        }
    }

    // --- Hidden data -------------------------------------------------------------------------

    private val JS = COSName.getPDFName("JS")
    private val JAVASCRIPT = COSName.getPDFName("JavaScript")
    private val EF = COSName.getPDFName("EF")
    private val PIECE_INFO = COSName.getPDFName("PieceInfo")
    private val FILE_ATTACHMENT = "FileAttachment"

    fun inspect(doc: PDDocument): HiddenData {
        val info = doc.documentInformation
        val entries = linkedMapOf<String, String>()
        for (key in info.metadataKeys) {
            val value = runCatching { info.cosObject.getDictionaryObject(key) }.getOrNull() ?: continue
            val text = when (value) {
                is com.tom_roush.pdfbox.cos.COSString -> value.string
                else -> value.toString()
            }
            if (text.isNotBlank()) entries[key] = text
        }
        var scripts = 0
        var files = 0
        walk(doc) { dict ->
            if (dict.containsKey(JS) || dict.getCOSName(COSName.S) == JAVASCRIPT) scripts++
            if (dict.containsKey(EF)) files++
        }
        val oc = doc.documentCatalog.ocProperties
        val groups = oc?.optionalContentGroups.orEmpty()
        return HiddenData(
            info = entries,
            hasXmpMetadata = doc.documentCatalog.metadata != null,
            embeddedFiles = files,
            scripts = scripts,
            layers = groups.size,
            hiddenLayers = groups.count { !oc!!.isGroupEnabled(it) },
        )
    }

    fun removeHiddenData(doc: PDDocument, what: HiddenDataRemoval) {
        val catalog = doc.documentCatalog
        if (what.metadata) {
            doc.documentInformation = PDDocumentInformation()
            catalog.metadata = null
        }
        if (what.scripts) {
            catalog.names?.setJavascript(null)
            catalog.cosObject.removeItem(COSName.AA)
            val open = catalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION) as? COSDictionary
            if (open != null && (open.containsKey(JS) || open.getCOSName(COSName.S) == JAVASCRIPT)) catalog.cosObject.removeItem(COSName.OPEN_ACTION)
        }
        if (what.embeddedFiles) {
            catalog.names?.embeddedFiles = null
            for (page in doc.pages) {
                val annotations = page.annotations
                if (annotations.any { it.subtype == FILE_ATTACHMENT }) page.annotations = annotations.filterNot { it.subtype == FILE_ATTACHMENT }
            }
        }
        // Whatever is left anywhere (page, field and link actions, attachments elsewhere).
        walk(doc) { dict ->
            if (what.scripts) {
                dict.removeItem(COSName.AA)
                if (dict.containsKey(JS) || dict.getCOSName(COSName.S) == JAVASCRIPT) {
                    // An action that runs nothing.
                    dict.removeItem(JS)
                    dict.setItem(COSName.S, COSName.getPDFName("Named"))
                    dict.setItem(COSName.N, COSName.getPDFName("NoOp"))
                }
            }
            if (what.embeddedFiles) {
                dict.removeItem(EF)
                dict.removeItem(COSName.getPDFName("AF"))
            }
            if (what.metadata) {
                dict.removeItem(PIECE_INFO)
                if (dict !== catalog.cosObject) dict.removeItem(COSName.METADATA)
            }
        }
    }

    /** Visits every dictionary reachable from the document's trailer once. */
    private fun walk(doc: PDDocument, visit: (COSDictionary) -> Unit) {
        val seen = IdentityHashMap<COSBase, Boolean>()
        val stack = ArrayDeque<COSBase>()
        stack.addLast(doc.document.trailer)
        while (stack.isNotEmpty()) {
            var base = stack.removeLast()
            if (base is COSObject) base = base.`object` ?: continue
            if (seen.put(base, true) != null) continue
            when (base) {
                is COSDictionary -> {
                    visit(base)
                    for (value in base.values) stack.addLast(value)
                }
                is COSArray -> for (value in base) stack.addLast(value)
                else -> Unit
            }
        }
    }

    // --- Redaction ---------------------------------------------------------------------------

    /** Resolution of redacted pages, which become images. */
    private const val REDACTED_DPI = 150f

    /**
     * Replaces [pages] of the PDF in [source] with pictures of themselves (which already show
     * the black redaction boxes), so no text, image or drawing data under a box remains.
     * Form fields on those pages are removed too. Writes the result to [output].
     */
    fun rasterizePages(context: Context, source: File, pages: Set<Int>, output: OutputStream) {
        val images = mutableMapOf<Int, Triple<File, Float, Float>>()
        try {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (index in pages.sorted()) {
                        if (index !in 0 until renderer.pageCount) continue
                        renderer.openPage(index).use { page ->
                            val scale = REDACTED_DPI / 72f
                            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val file = File(context.cacheDir, "redacted-${UUID.randomUUID()}.jpg")
                            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                            bitmap.recycle()
                            images[index] = Triple(file, page.width.toFloat(), page.height.toFloat())
                        }
                    }
                }
            }
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                removeFields(doc, images.keys)
                for ((index, entry) in images) {
                    val (file, width, height) = entry
                    val page = doc.getPage(index)
                    val dict = page.cosObject
                    for (key in listOf(COSName.ANNOTS, COSName.getPDFName("Thumb"), PIECE_INFO, COSName.METADATA, COSName.getPDFName("StructParents"), COSName.GROUP, COSName.BLEED_BOX, COSName.TRIM_BOX, COSName.ART_BOX)) {
                        dict.removeItem(key)
                    }
                    val box = PDRectangle(width, height)
                    page.mediaBox = box
                    page.cropBox = box
                    page.rotation = 0
                    page.resources = PDResources()
                    val image = JPEGFactory.createFromStream(doc, file.inputStream())
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { s ->
                        s.drawImage(image, 0f, 0f, width, height)
                    }
                }
                doc.save(output)
            }
        } finally {
            images.values.forEach { it.first.delete() }
        }
    }

    /** Removes form fields (and their values) whose widgets are on [pages]. */
    private fun removeFields(doc: PDDocument, pages: Set<Int>) {
        val form = doc.documentCatalog.acroForm ?: return
        val onPages = IdentityHashMap<COSDictionary, Boolean>()
        for (i in pages) runCatching { doc.getPage(i).annotations }.getOrNull()?.forEach { onPages[it.cosObject] = true }
        fun keep(field: com.tom_roush.pdfbox.pdmodel.interactive.form.PDField): Boolean = when (field) {
            is com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField -> {
                field.children = field.children.filter(::keep)
                field.children.isNotEmpty()
            }
            is com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField -> {
                val widgets = field.widgets
                val remaining = widgets.filterNot { onPages.containsKey(it.cosObject) }
                if (remaining.size != widgets.size && remaining.isNotEmpty()) field.widgets = remaining
                remaining.isNotEmpty() || widgets.isEmpty()
            }
            else -> true
        }
        form.fields = form.fields.filter(::keep)
    }
}
