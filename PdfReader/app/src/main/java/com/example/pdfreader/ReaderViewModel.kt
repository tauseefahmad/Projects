package com.example.pdfreader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ReaderState {
    data object Empty : ReaderState
    data object Loading : ReaderState
    data class Ready(val uri: Uri, val name: String, val document: PdfDocument, val structure: PdfStructure) : ReaderState
    data class Error(val message: Int) : ReaderState
}

/** Holds the open document so it survives rotation without re-opening the file. */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Empty)
    val state: StateFlow<ReaderState> = _state

    val recentFiles = RecentFiles(application)
    val bookmarks = BookmarkStore(application)

    /** Clockwise view rotation of the open document (display only; the file is unchanged). */
    var viewRotation = 0

    /** Page the reader is currently on; used to restore position after rotation. */
    var currentPage = 0
        private set

    fun open(uri: Uri, name: String) {
        val current = _state.value
        if (current is ReaderState.Ready && current.uri == uri) return
        savePage()
        closeCurrent()
        viewRotation = 0
        _state.value = ReaderState.Loading
        viewModelScope.launch {
            _state.value = try {
                val document = PdfDocument.open(getApplication(), uri)
                val page = recentFiles.lastPage(uri).coerceIn(0, (document.pageCount - 1).coerceAtLeast(0))
                recentFiles.remember(RecentFile(uri, name, page))
                currentPage = page
                ReaderState.Ready(uri, name, document, PdfStructure(getApplication(), uri))
            } catch (e: PasswordProtectedException) {
                ReaderState.Error(R.string.error_password)
            } catch (e: Exception) {
                recentFiles.remove(uri)
                ReaderState.Error(R.string.error_open)
            }
        }
    }

    fun updatePage(page: Int) {
        currentPage = page
    }

    /** Persists the reading position so the document reopens where it was left. */
    fun savePage() {
        val current = _state.value as? ReaderState.Ready ?: return
        recentFiles.remember(RecentFile(current.uri, current.name, currentPage))
    }

    fun close() {
        savePage()
        closeCurrent()
        _state.value = ReaderState.Empty
    }

    fun errorShown() {
        if (_state.value is ReaderState.Error) _state.value = ReaderState.Empty
    }

    private fun closeCurrent() {
        val ready = _state.value as? ReaderState.Ready ?: return
        ready.document.close()
        ready.structure.close()
    }

    override fun onCleared() = closeCurrent()
}
