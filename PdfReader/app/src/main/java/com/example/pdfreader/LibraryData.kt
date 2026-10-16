package com.example.pdfreader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import com.example.pdfreader.core.EInvoice
import com.example.pdfreader.core.EInvoices
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// --- Version history ---------------------------------------------------------------------------

/** A saved earlier version of a document. */
data class Version(val file: File, val savedAt: Long, val size: Long)

/**
 * Keeps the previous content of a file each time the app overwrites it, so earlier versions can
 * be opened, compared or restored. Up to [MAX] versions per document, stored privately.
 */
object VersionStore {
    private const val MAX = 10

    private fun dir(context: Context, uri: Uri): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
        return File(File(context.filesDir, "versions"), digest.take(12).joinToString("") { "%02x".format(it) })
    }

    /** Copies what [uri] holds now into the history. Never throws. */
    fun keep(context: Context, uri: Uri) {
        runCatching {
            val dir = dir(context, uri).apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}.pdf")
            val input = context.contentResolver.openInputStream(uri) ?: return
            input.use { i -> target.outputStream().use { i.copyTo(it) } }
            if (target.length() == 0L) target.delete()
            list(context, uri).drop(MAX).forEach { it.file.delete() }
        }
    }

    /** Newest first. */
    fun list(context: Context, uri: Uri): List<Version> = dir(context, uri).listFiles { f -> f.name.endsWith(".pdf") }.orEmpty()
        .mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { Version(f, it, f.length()) } }
        .sortedByDescending { it.savedAt }

    fun clear(context: Context, uri: Uri) = dir(context, uri).deleteRecursively()
}

// --- Library -------------------------------------------------------------------------------------

/** A document in the library. */
data class LibraryEntry(
    val uri: Uri,
    val name: String,
    val lastOpened: Long,
    val size: Long = -1,
    val pages: Int = 0,
    val favorite: Boolean = false,
    val tags: Set<String> = emptySet(),
)

/** Every document opened in the app, with favorites and tags. */
class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun all(): List<LibraryEntry> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.getString(it) }.toSet() }.orEmpty()
                LibraryEntry(Uri.parse(o.getString("uri")), o.getString("name"), o.optLong("opened"), o.optLong("size", -1), o.optInt("pages"), o.optBoolean("favorite"), tags)
            }
        }.getOrDefault(emptyList())
    }

    fun find(uri: Uri) = all().firstOrNull { it.uri == uri }

    /** Records that [uri] was opened (adds it, or updates it and moves it to the top). */
    fun opened(uri: Uri, name: String, pages: Int, size: Long) {
        val old = find(uri)
        val entry = (old ?: LibraryEntry(uri, name, 0)).copy(name = name, lastOpened = System.currentTimeMillis(), pages = pages, size = if (size >= 0) size else old?.size ?: -1)
        save(listOf(entry) + all().filter { it.uri != uri })
    }

    fun update(entry: LibraryEntry) = save(all().map { if (it.uri == entry.uri) entry else it })

    fun remove(uri: Uri) = save(all().filter { it.uri != uri })

    fun allTags(): List<String> = all().flatMap { it.tags }.distinct().sorted()

    private fun save(entries: List<LibraryEntry>) {
        val array = JSONArray()
        for (e in entries.take(MAX)) {
            array.put(
                JSONObject().put("uri", e.uri.toString()).put("name", e.name).put("opened", e.lastOpened).put("size", e.size)
                    .put("pages", e.pages).put("favorite", e.favorite).put("tags", JSONArray(e.tags.toList())),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val KEY = "entries"
        private const val MAX = 500

        fun sizeOf(context: Context, uri: Uri): Long = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L
    }
}

/** A full-text match in the library. */
data class LibraryHit(val uri: Uri, val name: String, val page: Int, val snippet: String)

/** Full-text index of the text (including OCR'd text) of every document opened (SQLite FTS4). */
class LibraryIndex private constructor(context: Context) : SQLiteOpenHelper(context, "library-index.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE pages USING fts4(uri, name, page, body, tokenize=unicode61)")
        db.execSQL("CREATE TABLE docs (uri TEXT PRIMARY KEY, stamp TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Whether [uri] is indexed with this [stamp] (size and page count, to notice changes). */
    fun isIndexed(uri: Uri, stamp: String): Boolean = readableDatabase.rawQuery("SELECT stamp FROM docs WHERE uri = ?", arrayOf(uri.toString())).use { c ->
        c.moveToFirst() && c.getString(0) == stamp
    }

    fun index(uri: Uri, name: String, pages: List<String>, stamp: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("pages", "uri = ?", arrayOf(uri.toString()))
            pages.forEachIndexed { i, text ->
                if (text.isBlank()) return@forEachIndexed
                db.insert("pages", null, ContentValues().apply {
                    put("uri", uri.toString())
                    put("name", name)
                    put("page", i.toString())
                    put("body", text)
                })
            }
            db.insertWithOnConflict("docs", null, ContentValues().apply {
                put("uri", uri.toString())
                put("stamp", stamp)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun remove(uri: Uri) {
        writableDatabase.delete("pages", "uri = ?", arrayOf(uri.toString()))
        writableDatabase.delete("docs", "uri = ?", arrayOf(uri.toString()))
    }

    /** Pages containing all words of [query] (prefix matches), best first. */
    fun search(query: String, limit: Int = 100): List<LibraryHit> {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.toList()
        if (words.isEmpty()) return emptyList()
        val match = words.joinToString(" ") { "body:$it*" }
        return readableDatabase.rawQuery(
            "SELECT uri, name, page, snippet(pages, '[', ']', '…', 3, 12) FROM pages WHERE pages MATCH ? LIMIT ?",
            arrayOf(match, limit.toString()),
        ).use { c ->
            val hits = mutableListOf<LibraryHit>()
            while (c.moveToNext()) hits += LibraryHit(Uri.parse(c.getString(0)), c.getString(1), c.getString(2).toInt(), c.getString(3))
            hits
        }
    }

    companion object {
        @Volatile private var instance: LibraryIndex? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: LibraryIndex(context.applicationContext).also { instance = it } }
    }
}

// --- E-invoices ---------------------------------------------------------------------------------

object EInvoiceReader {

    /** The ZUGFeRD / Factur-X / XRechnung data attached to [doc], if any. */
    fun read(doc: PDDocument): EInvoice? {
        val files = doc.documentCatalog.names?.embeddedFiles ?: return null
        val specs = mutableMapOf<String, PDComplexFileSpecification>()
        fun collect(node: com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode<PDComplexFileSpecification>) {
            node.names?.let { specs.putAll(it) }
            node.kids?.forEach { collect(it) }
        }
        collect(files)
        val spec = specs.entries.firstOrNull { it.key.lowercase() in EInvoices.attachmentNames }?.value
            ?: specs.values.firstOrNull { (it.filename ?: it.file ?: "").lowercase() in EInvoices.attachmentNames }
            ?: return null
        val file = spec.embeddedFileUnicode ?: spec.embeddedFile ?: return null
        val xml = file.toByteArray().toString(Charsets.UTF_8)
        return EInvoices.read(xml)
    }
}

// --- Accessibility -------------------------------------------------------------------------------

/** One accessibility check and whether the document passes it. */
data class AccessibilityResult(val check: String, val passed: Boolean, val detail: String = "")

/** Checks that matter for screen readers: tags, language, title, alt text, real text, Unicode fonts. */
object AccessibilityCheck {

    fun run(doc: PDDocument): List<AccessibilityResult> {
        val catalog = doc.documentCatalog
        val results = mutableListOf<AccessibilityResult>()
        val tagged = catalog.markInfo?.isMarked == true && catalog.structureTreeRoot != null
        results += AccessibilityResult("Tagged PDF (reading order and structure)", tagged)
        val lang = catalog.language
        results += AccessibilityResult("Document language", !lang.isNullOrBlank(), lang.orEmpty())
        val title = doc.documentInformation?.title
        val shows = catalog.viewerPreferences?.displayDocTitle() == true
        results += AccessibilityResult("Title, shown instead of the file name", !title.isNullOrBlank() && shows, title.orEmpty())
        if (tagged) {
            var figures = 0
            var withoutAlt = 0
            fun visit(node: PDStructureNode, depth: Int) {
                if (depth > 200) return
                for (kid in node.kids) {
                    if (kid is PDStructureElement) {
                        if (kid.standardStructureType == "Figure") {
                            figures++
                            if (kid.alternateDescription.isNullOrBlank() && kid.actualText.isNullOrBlank()) withoutAlt++
                        }
                        visit(kid, depth + 1)
                    }
                }
            }
            catalog.structureTreeRoot?.let { visit(it, 0) }
            results += AccessibilityResult("Pictures have alternative text", withoutAlt == 0, if (figures == 0) "No tagged pictures" else "$withoutAlt of $figures without")
        } else {
            results += AccessibilityResult("Pictures have alternative text", false, "Can't tell without tags")
        }
        val text = ConvertTools.pageTexts(doc)
        val noText = text.count { it.isBlank() }
        results += AccessibilityResult("Pages have real text (not only scans)", noText == 0, if (noText == 0) "" else "$noText of ${text.size} pages without text: run OCR")
        var fonts = 0
        var unmapped = 0
        for (page in doc.pages) {
            val res = page.resources ?: continue
            for (name in res.fontNames) {
                val font = runCatching { res.getFont(name) }.getOrNull() ?: continue
                fonts++
                if (font.cosObject.getDictionaryObject(COSName.TO_UNICODE) == null && font is PDType0Font) unmapped++
            }
        }
        results += AccessibilityResult("Text can be read aloud (Unicode mapping)", unmapped == 0, if (unmapped == 0) "" else "$unmapped fonts without Unicode mapping")
        return results
    }

}
