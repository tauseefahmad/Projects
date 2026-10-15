package com.example.pdfreader

import android.app.Application
import android.net.Uri
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdfreader.core.PageListEditor
import com.example.pdfreader.core.PageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** State of the page organizer: the editable page list, its source files and the selection. */
class OrganizerViewModel(application: Application) : AndroidViewModel(application) {

    /** Source 0 is the document being organized; inserted files and images follow. */
    val sources = mutableListOf<Uri>()
    val renderers = mutableListOf<PdfDocument>()

    var editor: PageListEditor? = null
        private set

    val selection = mutableSetOf<Int>()

    /** Bumped on every change so the screen re-renders. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** One-off messages (string resources) for the screen to show. */
    val messages = MutableStateFlow<Int?>(null)

    val isLoaded: Boolean get() = editor != null

    fun load(uri: Uri) {
        if (isLoaded || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val document = PdfDocument.open(getApplication(), uri)
                sources += uri
                renderers += document
                editor = PageListEditor(List(document.pageCount) { PageRef(0, it) })
            } catch (e: Exception) {
                messages.value = R.string.error_open
            }
            _busy.value = false
            changed()
        }
    }

    fun changed() {
        _version.value++
    }

    fun pageSize(ref: PageRef): Size =
        if (ref.isBlank) Size(ref.blankWidth.toInt(), ref.blankHeight.toInt()) else renderers[ref.source].pageSizes[ref.page]

    /** Adds another PDF (or an image converted to one) as a source and returns its index. */
    suspend fun addSource(uri: Uri): Int {
        val document = PdfDocument.open(getApplication(), uri)
        sources += uri
        renderers += document
        return sources.lastIndex
    }

    fun edit(block: PageListEditor.() -> Unit) {
        val editor = editor ?: return
        editor.block()
        selection.retainAll(editor.pages.indices.toSet())
        changed()
    }

    /** Builds the PDF for [pages] into a temporary file, then copies it to [destination]. */
    suspend fun writeTo(destination: Uri, pages: List<PageRef>) = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val temp = File(app.cacheDir, "organizer-${UUID.randomUUID()}.pdf")
        try {
            temp.outputStream().use { PageAssembler.assemble(app, sources, pages, it) }
            // Truncate on write ("wt") so a shorter result doesn't leave old bytes behind.
            val output = app.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("Cannot write $destination")
            output.use { out -> temp.inputStream().use { it.copyTo(out) } }
        } finally {
            temp.delete()
        }
    }

    fun setBusy(value: Boolean) {
        _busy.value = value
    }

    override fun onCleared() {
        renderers.forEach { it.close() }
    }
}
