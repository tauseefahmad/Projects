package de.jobcopilot.app.data

import de.jobcopilot.app.data.local.AppJson
import de.jobcopilot.app.data.local.ApplicationDao
import de.jobcopilot.app.data.local.ApplicationEntity
import de.jobcopilot.app.data.local.ApplicationStatus
import de.jobcopilot.app.data.local.job
import de.jobcopilot.app.data.sources.BundesagenturSource
import de.jobcopilot.app.data.sources.JobSearchService
import de.jobcopilot.app.data.sources.JobSource
import de.jobcopilot.app.data.sources.SearchOutcome
import de.jobcopilot.app.data.sources.SearchQuery
import de.jobcopilot.app.domain.CoverLetter
import de.jobcopilot.app.domain.FitResult
import de.jobcopilot.app.domain.FitScorer
import de.jobcopilot.app.domain.Job
import de.jobcopilot.app.domain.PostingAnalyzer
import de.jobcopilot.app.domain.PostingInsights
import de.jobcopilot.app.domain.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

data class ScoredJob(val job: Job, val insights: PostingInsights, val fit: FitResult)

class JobRepository(
    private val sources: () -> List<JobSource>,
    private val bundesagentur: BundesagenturSource,
    private val dao: ApplicationDao,
) {
    private val cache = ConcurrentHashMap<String, Job>()

    suspend fun search(query: SearchQuery): SearchOutcome {
        val outcome = JobSearchService(sources()).search(query)
        outcome.jobs.forEach { cache[it.id] = it }
        return outcome
    }

    fun score(job: Job, profile: Profile): ScoredJob {
        val insights = PostingAnalyzer.analyze(job)
        return ScoredJob(job, insights, FitScorer.score(job, insights, profile))
    }

    /** Looks up a job from the last search or the tracker, loading the BA description on demand. */
    suspend fun job(id: String): Job? {
        val job = cache[id] ?: dao.get(id)?.job ?: return null
        if (job.id.startsWith("ba:") && job.description.length < 200) {
            val detailed = bundesagentur.details(job)
            cache[id] = detailed
            dao.get(id)?.let { dao.upsert(it.copy(jobJson = AppJson.encodeToString(detailed))) }
            return detailed
        }
        return job
    }

    // --- tracker ---------------------------------------------------------------------------------

    fun applications(): Flow<List<ApplicationEntity>> = dao.observeAll()

    suspend fun application(id: String) = dao.get(id)

    suspend fun track(scored: ScoredJob, letter: CoverLetter? = null) {
        val now = System.currentTimeMillis()
        val existing = dao.get(scored.job.id)
        dao.upsert(
            existing?.copy(
                jobJson = AppJson.encodeToString(scored.job),
                fitScore = scored.fit.score,
                letterJson = letter?.let { AppJson.encodeToString(it) } ?: existing.letterJson,
                updatedAt = now,
            ) ?: ApplicationEntity(
                id = scored.job.id,
                jobJson = AppJson.encodeToString(scored.job),
                status = ApplicationStatus.SAVED,
                fitScore = scored.fit.score,
                createdAt = now,
                updatedAt = now,
                letterJson = letter?.let { AppJson.encodeToString(it) },
            ),
        )
    }

    suspend fun move(entity: ApplicationEntity, status: ApplicationStatus) {
        val now = System.currentTimeMillis()
        // Moving to "Applied" schedules a follow-up a week later, the usual etiquette in Germany.
        val followUp = if (status == ApplicationStatus.APPLIED && entity.followUpAt == null) now + 7 * DAY else entity.followUpAt
        dao.upsert(entity.copy(status = status, updatedAt = now, followUpAt = followUp))
    }

    suspend fun update(entity: ApplicationEntity) = dao.upsert(entity.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun deleteAll() {
        dao.deleteAll()
        cache.clear()
    }

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
