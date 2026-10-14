package de.jobcopilot.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.jobcopilot.app.JobCopilotApp
import de.jobcopilot.app.ai.LetterGenerationException
import de.jobcopilot.app.data.ScoredJob
import de.jobcopilot.app.data.local.ApplicationEntity
import de.jobcopilot.app.data.local.ApplicationStatus
import de.jobcopilot.app.data.local.Settings
import de.jobcopilot.app.data.local.letter
import de.jobcopilot.app.data.sources.SearchQuery
import de.jobcopilot.app.domain.CoverLetter
import de.jobcopilot.app.domain.LetterLanguage
import de.jobcopilot.app.domain.Profile
import de.jobcopilot.app.domain.TemplateLetterWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class SearchState(
    val what: String = "",
    val where: String = "",
    val loading: Boolean = false,
    val results: List<ScoredJob> = emptyList(),
    val failedSources: List<String> = emptyList(),
    val error: String? = null,
    val searched: Boolean = false,
)

data class LetterState(
    val jobId: String? = null,
    val language: LetterLanguage = LetterLanguage.DE,
    val generating: Boolean = false,
    val letter: CoverLetter? = null,
    val fromTemplate: Boolean = false,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as JobCopilotApp).container

    val profile: StateFlow<Profile> = c.store.profile
    val settings: StateFlow<Settings> = c.store.settings
    val applications: StateFlow<List<ApplicationEntity>> =
        c.jobs.applications().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _search = MutableStateFlow(SearchState())
    val search = _search.asStateFlow()

    private val _detail = MutableStateFlow<ScoredJob?>(null)
    val detail = _detail.asStateFlow()

    private val _letter = MutableStateFlow(LetterState())
    val letter = _letter.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()
    fun toastShown() { _toast.value = null }

    // --- search ----------------------------------------------------------------------------------

    fun onQueryChange(what: String? = null, where: String? = null) = _search.update {
        it.copy(what = what ?: it.what, where = where ?: it.where)
    }

    fun runSearch() {
        val s = _search.value
        if (s.what.isBlank()) return
        _search.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { c.jobs.search(SearchQuery(s.what.trim(), s.where.trim())) }
                .onSuccess { outcome ->
                    val p = profile.value
                    val scored = outcome.jobs.map { c.jobs.score(it, p) }.sortedByDescending { it.fit.score }
                    _search.update {
                        it.copy(loading = false, results = scored, failedSources = outcome.failedSources, searched = true)
                    }
                }
                .onFailure { e -> _search.update { it.copy(loading = false, error = e.message ?: "Search failed", searched = true) } }
        }
    }

    /** Re-score after the profile changes so reasons stay accurate. */
    private fun rescore() {
        val p = profile.value
        _search.update { s -> s.copy(results = s.results.map { c.jobs.score(it.job, p) }.sortedByDescending { it.fit.score }) }
        _detail.value?.let { _detail.value = c.jobs.score(it.job, p) }
    }

    // --- detail ----------------------------------------------------------------------------------

    fun openJob(id: String) {
        if (_detail.value?.job?.id == id) return
        _detail.value = null
        viewModelScope.launch {
            val job = c.jobs.job(id) ?: return@launch
            _detail.value = c.jobs.score(job, profile.value)
        }
    }

    fun track(scored: ScoredJob) = viewModelScope.launch {
        c.jobs.track(scored)
        _toast.value = "Saved to tracker"
    }

    // --- letter ----------------------------------------------------------------------------------

    fun openLetter(jobId: String) {
        if (_letter.value.jobId == jobId && _letter.value.letter != null) return
        val defaultLang = _detail.value?.let { if (it.insights.postingLanguage == "de") LetterLanguage.DE else LetterLanguage.EN }
            ?: LetterLanguage.DE
        _letter.value = LetterState(jobId = jobId, language = defaultLang)
        openJob(jobId)
        viewModelScope.launch {
            c.jobs.application(jobId)?.letter?.let { saved ->
                _letter.update { it.copy(letter = saved, language = saved.language) }
            }
        }
    }

    fun setLetterLanguage(language: LetterLanguage) = _letter.update { it.copy(language = language) }

    fun generateLetter(useAi: Boolean) {
        val scored = _detail.value ?: return
        val lang = _letter.value.language
        val p = profile.value
        val key = settings.value.claudeApiKey
        if (useAi && key.isBlank()) {
            _letter.update { it.copy(error = "Add your Claude API key in Settings, or use the offline template.") }
            return
        }
        _letter.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            val result = if (useAi) {
                runCatching { c.letterWriter.write(key, scored.job, scored.insights, scored.fit, p, lang) }
            } else {
                Result.success(TemplateLetterWriter.write(scored.job, scored.insights, scored.fit, p, lang))
            }
            result
                .onSuccess { l -> _letter.update { it.copy(generating = false, letter = l, fromTemplate = !useAi) } }
                .onFailure { e ->
                    val msg = (e as? LetterGenerationException)?.message ?: "Could not generate the letter: ${e.message}"
                    _letter.update { it.copy(generating = false, error = msg) }
                }
        }
    }

    fun editLetter(transform: (CoverLetter) -> CoverLetter) =
        _letter.update { s -> s.copy(letter = s.letter?.let(transform)) }

    fun saveLetterToTracker() {
        val scored = _detail.value ?: return
        val l = _letter.value.letter ?: return
        viewModelScope.launch {
            c.jobs.track(scored, l)
            _toast.value = "Letter saved with the application"
        }
    }

    fun exportLetterPdf(): File? {
        val scored = _detail.value ?: return null
        val l = _letter.value.letter ?: return null
        return c.pdf.exportLetter(l, profile.value, scored.job)
    }

    fun exportCvPdf(): File? {
        val scored = _detail.value ?: return null
        return c.pdf.exportCv(profile.value, scored.job, scored.fit, _letter.value.language)
    }

    // --- tracker ---------------------------------------------------------------------------------

    fun move(entity: ApplicationEntity, status: ApplicationStatus) = viewModelScope.launch { c.jobs.move(entity, status) }
    fun updateApplication(entity: ApplicationEntity) = viewModelScope.launch { c.jobs.update(entity) }
    fun deleteApplication(id: String) = viewModelScope.launch { c.jobs.delete(id) }

    // --- profile & settings ----------------------------------------------------------------------

    fun saveProfile(p: Profile) {
        c.store.saveProfile(p)
        rescore()
        _toast.value = "Profile saved"
    }

    fun saveSettings(s: Settings) {
        c.store.saveSettings(s)
        _toast.value = "Settings saved"
    }

    /** GDPR-style full deletion: profile, API key, tracker, exported PDFs. */
    fun deleteAllData() = viewModelScope.launch {
        c.jobs.deleteAll()
        c.store.wipe()
        c.pdf.clearExports()
        _search.value = SearchState()
        _detail.value = null
        _letter.value = LetterState()
        _toast.value = "All data deleted from this device"
    }
}
