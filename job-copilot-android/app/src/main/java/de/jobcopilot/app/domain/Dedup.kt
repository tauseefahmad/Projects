package de.jobcopilot.app.domain

import java.text.Normalizer

/** Merges the same posting returned by several sources (e.g. BA and the company's Greenhouse board). */
object Dedup {
    private val companySuffixes = Regex(
        "\\b(gmbh|ag|se|kg|ug|co|mbh|inc|ltd|llc|haftungsbeschränkt|& co\\.?|gmbh & co\\.? kg)\\b",
    )
    private val genderTags = Regex("\\(?\\s*[mwfdx]\\s*/\\s*[mwfdx]\\s*(/\\s*[mwfdx]\\s*)?\\)?|\\(all genders?\\)|\\(gn\\)")

    fun normalize(text: String): String {
        val lower = text.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(genderTags, " ")
            .replace(companySuffixes, " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun city(location: String): String =
        normalize(location.split(',', '/', '(', '-').firstOrNull().orEmpty())

    fun key(job: Job): String = listOf(normalize(job.company), normalize(job.title), city(job.location)).joinToString("|")

    /** Keeps the posting with the longest description and unions the sources. */
    fun merge(jobs: List<Job>): List<Job> =
        jobs.groupBy { it.dedupKey }.values.map { group ->
            val best = group.maxBy { it.description.length }
            best.copy(
                sources = group.flatMap { it.sources }.distinct(),
                salaryHint = best.salaryHint ?: group.firstNotNullOfOrNull { it.salaryHint },
                remoteHint = if (best.remoteHint != RemotePolicy.UNKNOWN) best.remoteHint
                else group.map { it.remoteHint }.firstOrNull { it != RemotePolicy.UNKNOWN } ?: RemotePolicy.UNKNOWN,
            )
        }
}
