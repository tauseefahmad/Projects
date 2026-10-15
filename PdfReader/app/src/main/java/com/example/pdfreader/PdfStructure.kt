package com.example.pdfreader

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.example.pdfreader.core.OutlineNode
import com.example.pdfreader.core.PageLink
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/**
 * The document's structure (outline, and later text, links and metadata) read with PdfBox.
 * PdfRenderer only draws pages; this parses the file itself. It is loaded on first use, in the
 * background, and is not thread-safe, so all access goes through [mutex].
 */
class PdfStructure(private val context: Context, private val uri: Uri) : Closeable {
    // Closed in the background; safe to use with use { }.

    private val mutex = Mutex()
    private var document: PDDocument? = null
    private var closed = false
    private var outline: List<OutlineNode>? = null

    // Text of every page searched so far (small), and full text+positions for a few recent pages.
    private val textCache = HashMap<Int, String>()
    private val positionCache = LruCache<Int, PageText>(POSITION_CACHE_PAGES)
    private val linkCache = HashMap<Int, List<PageLink>>()

    private suspend fun <T> withDocument(block: (PDDocument) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "Document is closed" }
            val doc = document ?: load().also { document = it }
            block(doc)
        }
    }

    private fun load(): PDDocument {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
        // Buffer through a temp file instead of RAM so large documents don't run out of memory.
        return input.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
    }

    /** The PDF's built-in table of contents, or an empty list if it has none. */
    suspend fun outline(): List<OutlineNode> {
        outline?.let { return it }
        return withDocument { doc ->
            val root = doc.documentCatalog.documentOutline
            if (root == null) emptyList() else readChildren(doc, root, depth = 0)
        }.also { outline = it }
    }

    /** The page's plain text, for search. Cached for the lifetime of the document. */
    suspend fun pageText(page: Int): String {
        synchronized(textCache) { textCache[page] }?.let { return it }
        return pageTextWithPositions(page).text
    }

    /** The page's text if it has already been extracted (e.g. by a search), without waiting. */
    fun cachedPageText(page: Int): String? = synchronized(textCache) { textCache[page] }

    /** The page's text with character positions, for highlights and selection. */
    suspend fun pageTextWithPositions(page: Int): PageText {
        positionCache.get(page)?.let { return it }
        return withDocument { doc ->
            if (page !in 0 until doc.numberOfPages) return@withDocument PageText.EMPTY
            val stripper = PositionStripper()
            stripper.startPage = page + 1
            stripper.endPage = page + 1
            stripper.getText(doc)
            stripper.result()
        }.also {
            positionCache.put(page, it)
            synchronized(textCache) { textCache[page] = it.text }
        }
    }

    /** Metadata and facts about the file for the properties dialog. */
    data class Properties(
        val title: String?,
        val author: String?,
        val subject: String?,
        val keywords: String?,
        val creator: String?,
        val producer: String?,
        val created: java.util.Calendar?,
        val modified: java.util.Calendar?,
        val version: String,
        val encrypted: Boolean,
        val pageCount: Int,
        val firstPageWidth: Float,
        val firstPageHeight: Float,
    )

    suspend fun properties(): Properties = withDocument { doc ->
        val info = doc.documentInformation
        val box = if (doc.numberOfPages > 0) doc.getPage(0).cropBox else null
        Properties(
            title = info?.title?.takeIf { it.isNotBlank() },
            author = info?.author?.takeIf { it.isNotBlank() },
            subject = info?.subject?.takeIf { it.isNotBlank() },
            keywords = info?.keywords?.takeIf { it.isNotBlank() },
            creator = info?.creator?.takeIf { it.isNotBlank() },
            producer = info?.producer?.takeIf { it.isNotBlank() },
            created = runCatching { info?.creationDate }.getOrNull(),
            modified = runCatching { info?.modificationDate }.getOrNull(),
            version = doc.version.toString(),
            encrypted = doc.isEncrypted,
            pageCount = doc.numberOfPages,
            firstPageWidth = box?.width ?: 0f,
            firstPageHeight = box?.height ?: 0f,
        )
    }

    /** Link areas on a page: internal (to a page) and external (URLs). */
    suspend fun links(page: Int): List<PageLink> {
        synchronized(linkCache) { linkCache[page] }?.let { return it }
        return withDocument { doc ->
            if (page !in 0 until doc.numberOfPages) return@withDocument emptyList()
            val pdPage = doc.getPage(page)
            val crop = pdPage.cropBox
            pdPage.annotations.filterIsInstance<PDAnnotationLink>().mapNotNull { link ->
                val rect = link.rectangle ?: return@mapNotNull null
                // PDF space has its origin bottom-left; flip to top-left relative to the crop box.
                val left = rect.lowerLeftX - crop.lowerLeftX
                val right = rect.upperRightX - crop.lowerLeftX
                val top = crop.upperRightY - rect.upperRightY
                val bottom = crop.upperRightY - rect.lowerLeftY
                val action = link.action
                when {
                    action is PDActionURI -> action.uri?.let { PageLink(left, top, right, bottom, uri = it) }
                    else -> {
                        val destination = (action as? PDActionGoTo)?.destination ?: runCatching { link.destination }.getOrNull()
                        resolvePage(doc, destination)?.let { PageLink(left, top, right, bottom, targetPage = it) }
                    }
                }
            }
        }.also { synchronized(linkCache) { linkCache[page] = it } }
    }

    private fun resolvePage(doc: PDDocument, destination: PDDestination?): Int? {
        val pageDestination = when (destination) {
            is PDPageDestination -> destination
            is PDNamedDestination -> runCatching { doc.documentCatalog.findNamedDestinationPage(destination) }.getOrNull()
            else -> null
        } ?: return null
        pageDestination.page?.let { page -> return doc.pages.indexOf(page).takeIf { it >= 0 } }
        // Some files (remote go-to style) store a page number instead of a page reference.
        return pageDestination.pageNumber.takeIf { it in 0 until doc.numberOfPages }
    }

    private fun readChildren(doc: PDDocument, parent: PDOutlineNode, depth: Int): List<OutlineNode> {
        if (depth > MAX_OUTLINE_DEPTH) return emptyList()
        val nodes = mutableListOf<OutlineNode>()
        for (item in parent.children()) {
            if (nodes.size >= MAX_OUTLINE_ITEMS) break
            val page = try {
                item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) }?.takeIf { it >= 0 }
            } catch (e: IOException) {
                null
            }
            val title = item.title?.trim().orEmpty().ifEmpty { "—" }
            nodes += OutlineNode(title, page, readChildren(doc, item, depth + 1))
        }
        return nodes
    }

    override fun close() {
        // Called from the main thread: close in the background once any running parse finishes.
        Thread {
            runBlocking {
                mutex.withLock {
                    closed = true
                    runCatching { document?.close() }
                    document = null
                }
            }
        }.start()
    }

    private companion object {
        // Guards against malformed outlines with cycles or absurd sizes.
        const val MAX_OUTLINE_DEPTH = 16
        const val MAX_OUTLINE_ITEMS = 5000
        const val POSITION_CACHE_PAGES = 12
    }
}
