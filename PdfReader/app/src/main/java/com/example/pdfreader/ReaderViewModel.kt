package com.example.pdfreader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdfreader.core.ZoomState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReaderState {
    data object Empty : ReaderState
    data object Loading : ReaderState
    /**
     * An open document. [uri] identifies the file (recents, bookmarks, sharing); [contentUri] is
     * what is actually read — a decrypted private copy for password-protected files.
     */
    data class Ready(
        val uri: Uri,
        val name: String,
        val document: PdfDocument,
        val structure: PdfStructure,
        val contentUri: Uri = uri,
        /** Editable annotations; the pages in [document] are rendered without them. */
        val annotations: AnnotationSession? = null,
    ) : ReaderState

    /** The file is encrypted; ask for its password. */
    data class NeedsPassword(val uri: Uri, val name: String, val wrongPassword: Boolean) : ReaderState

    /** Downloading from the web; [total] is -1 when the size is unknown. */
    data class Downloading(val read: Long, val total: Long) : ReaderState
    data class Error(val message: Int) : ReaderState
}

/** Holds the open document so it survives rotation without re-opening the file. */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Empty)
    val state: StateFlow<ReaderState> = _state

    val recentFiles = RecentFiles(application)
    val bookmarks = BookmarkStore(application)

    /** Immersive full-screen reading is on. */
    var fullScreen = false

    /** Annotate mode is on (survives rotation; ends when another document opens). */
    var annotating = false

    /** The pending "save a copy" of annotations is the flattened kind. */
    var pendingFlattenCopy = false

    /** Clockwise view rotation of the open document (display only; the file is unchanged). */
    var viewRotation = 0

    /** Page the reader is currently on; used to restore position after rotation. */
    var currentPage = 0
        private set

    /** Zoom the reader is at, saved with the page so a document reopens as it was left. */
    var currentZoom: ZoomState? = null
        private set

    /** Zoom to restore once the reopened document is laid out; consumed by the screen. */
    var zoomToRestore: ZoomState? = null

    private var unlockedFile: java.io.File? = null

    /** Copy of the file without its editable annotations, which is what the screen renders. */
    private var renderFile: java.io.File? = null

    /** Drafts must be written even while the screen is being torn down, so not viewModelScope. */
    private val draftScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)

    fun open(uri: Uri, name: String, password: String? = null, startPage: Int? = null) {
        val current = _state.value
        if (current is ReaderState.Ready && current.uri == uri) return
        savePage()
        closeCurrent()
        viewRotation = 0
        annotating = false
        _state.value = ReaderState.Loading
        viewModelScope.launch {
            _state.value = try {
                var contentUri = uri
                val document = try {
                    PdfDocument.open(getApplication(), uri)
                } catch (e: PasswordProtectedException) {
                    // Try an empty password first: many files only restrict editing/printing.
                    val file = try {
                        withContext(Dispatchers.IO) { PdfUnlocker.unlock(getApplication(), uri, password ?: "") }
                    } catch (wrong: InvalidPasswordException) {
                        _state.value = ReaderState.NeedsPassword(uri, name, wrongPassword = password != null)
                        return@launch
                    }
                    unlockedFile = file
                    contentUri = Uri.fromFile(file)
                    PdfDocument.open(getApplication(), contentUri)
                }
                val loaded = withContext(Dispatchers.IO) { AnnotationSession.load(getApplication(), uri, contentUri) }
                val shown = loaded.renderFile?.let { file ->
                    try {
                        PdfDocument.open(getApplication(), Uri.fromFile(file)).also {
                            document.close()
                            renderFile = file
                        }
                    } catch (e: Exception) {
                        file.delete()
                        null
                    }
                } ?: document
                // If the stripped copy couldn't be used, the file's annotations stay as they are
                // and can't be edited (saving would otherwise drop them).
                val session = if (loaded.renderFile != null && shown === document) null else AnnotationSession(
                    getApplication(), draftScope, uri, loaded.annotations, loaded.draft, loaded.formFields,
                )
                val recent = recentFiles.find(uri)
                val page = (startPage ?: recent?.page ?: 0).coerceIn(0, (shown.pageCount - 1).coerceAtLeast(0))
                zoomToRestore = recent?.zoom
                currentZoom = recent?.zoom
                recentFiles.remember(RecentFile(uri, name, page, recent?.zoom))
                recordInLibrary(uri, name, contentUri, shown.pageCount)
                currentPage = page
                ReaderState.Ready(uri, name, shown, PdfStructure(getApplication(), contentUri), contentUri, session)
            } catch (e: PasswordProtectedException) {
                ReaderState.Error(R.string.error_password)
            } catch (e: Exception) {
                recentFiles.remove(uri)
                ReaderState.Error(R.string.error_open)
            }
        }
    }

    /** Adds the document to the library and indexes its text for searching across files. */
    private fun recordInLibrary(uri: Uri, name: String, contentUri: Uri, pageCount: Int) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val size = LibraryStore.sizeOf(app, uri)
                LibraryStore(app).opened(uri, name, pageCount, size)
                RecentFilesWidget.update(app)
                // Text of password-protected files stays out of the index.
                if (contentUri != uri || pageCount > 1000) return@launch
                val index = LibraryIndex.get(app)
                val stamp = "$size/$pageCount"
                if (!index.isIndexed(uri, stamp)) {
                    val texts = SecurityTools.load(app, contentUri).use { ConvertTools.pageTexts(it) }
                    index.index(uri, name, texts, stamp)
                }
            } catch (e: Exception) {
                android.util.Log.w("PdfReader", "Library indexing failed for $uri", e)
            }
        }
    }

    fun updatePage(page: Int) {
        currentPage = page
    }

    fun updateZoom(zoom: ZoomState) {
        currentZoom = zoom
    }

    /** Persists the reading position so the document reopens where it was left. */
    fun savePage() {
        val current = _state.value as? ReaderState.Ready ?: return
        recentFiles.remember(RecentFile(current.uri, current.name, currentPage, currentZoom))
    }

    fun close() {
        savePage()
        closeCurrent()
        _state.value = ReaderState.Empty
    }

    fun errorShown() {
        if (_state.value is ReaderState.Error) _state.value = ReaderState.Empty
    }

    /** Reopens the current document, e.g. after its pages were reorganized and saved. */
    fun reload() {
        val ready = _state.value as? ReaderState.Ready ?: return
        savePage()
        closeCurrent()
        _state.value = ReaderState.Empty
        open(ready.uri, ready.name)
    }

    /** Downloads a PDF from [url] and opens it. */
    fun download(url: String) {
        savePage()
        closeCurrent()
        _state.value = ReaderState.Downloading(0, -1)
        viewModelScope.launch {
            try {
                val (uri, name) = withContext(Dispatchers.IO) {
                    PdfDownloader.download(getApplication(), url) { read, total -> _state.value = ReaderState.Downloading(read, total) }
                }
                open(uri, name)
            } catch (e: DownloadException) {
                _state.value = ReaderState.Error(e.messageRes)
            } catch (e: Exception) {
                _state.value = ReaderState.Error(R.string.download_failed)
            }
        }
    }

    private fun closeCurrent() {
        val ready = _state.value as? ReaderState.Ready
        ready?.document?.close()
        ready?.structure?.close()
        ready?.annotations?.writeDraftNow()
        renderFile?.delete()
        renderFile = null
        // Never leave a decrypted copy of a protected file behind.
        unlockedFile?.delete()
        unlockedFile = null
    }

    override fun onCleared() = closeCurrent()
}
