package de.jobcopilot.app.data.sources

import de.jobcopilot.app.domain.Dedup
import de.jobcopilot.app.domain.Html
import de.jobcopilot.app.domain.Job
import de.jobcopilot.app.domain.RemotePolicy
import de.jobcopilot.app.domain.SalaryRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64

data class SearchQuery(val what: String, val where: String, val radiusKm: Int = 25)

interface JobSource {
    val name: String
    suspend fun search(query: SearchQuery): List<Job>
}

internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun OkHttpClient.getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement {
    val request = Request.Builder().url(url).apply {
        header("User-Agent", "JobCopilot/0.1 (Android; personal job search)")
        headers.forEach { (k, v) -> header(k, v) }
    }.build()
    newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${request.url.host}")
        return json.parseToJsonElement(resp.body?.string().orEmpty())
    }
}

internal fun JsonElement?.str(key: String): String? =
    (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }

internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

/** Locations that count as "in Germany" for international boards that list worldwide offices. */
internal object GermanLocations {
    private val markers = listOf(
        "germany", "deutschland", "berlin", "münchen", "munich", "hamburg", "köln", "cologne", "frankfurt",
        "stuttgart", "düsseldorf", "dusseldorf", "leipzig", "dresden", "hannover", "nürnberg", "nuremberg",
        "aachen", "bonn", "karlsruhe", "mannheim", "heidelberg", "bremen", "essen", "dortmund", "münster",
        "freiburg", "potsdam", "darmstadt", "wiesbaden", ", de", "(de)", "remote - de", "dach",
    )

    fun matches(location: String): Boolean = location.lowercase().let { l -> markers.any { it in l } }
}

internal fun matchesQuery(job: Job, q: SearchQuery): Boolean {
    val words = Dedup.normalize(q.what).split(" ").filter { it.length > 1 }
    val hay = Dedup.normalize(job.title + " " + Html.toPlainText(job.description).take(3000))
    val whatOk = words.isEmpty() || words.all { it in hay }
    val whereOk = q.where.isBlank() || Dedup.normalize(job.location).contains(Dedup.normalize(q.where)) ||
        Regex("(?i)remote").containsMatchIn(job.location)
    return whatOk && whereOk
}

// ------------------------------------------------------------------------------------------------
// Bundesagentur für Arbeit – documented by bund.dev; not officially supported for third parties.
// ------------------------------------------------------------------------------------------------

class BundesagenturSource(private val http: OkHttpClient) : JobSource {
    override val name = "Bundesagentur"
    private val base = "https://rest.arbeitsagentur.de/jobboerse/jobsuche-service"
    private val headers = mapOf("X-API-Key" to "jobboerse-jobsuche")

    override suspend fun search(query: SearchQuery): List<Job> = withContext(Dispatchers.IO) {
        val url = "$base/pc/v4/jobs".toHttpUrl().newBuilder()
            .addQueryParameter("was", query.what)
            .apply { if (query.where.isNotBlank()) addQueryParameter("wo", query.where) }
            .addQueryParameter("umkreis", query.radiusKm.toString())
            .addQueryParameter("angebotsart", "1") // 1 = Arbeit (no Ausbildung/Selbstständigkeit)
            .addQueryParameter("page", "1")
            .addQueryParameter("size", "50")
            .build().toString()
        parseSearch(http.getJson(url, headers))
    }

    /** Fetches the full description for one posting; the search endpoint only returns metadata. */
    suspend fun details(job: Job): Job = withContext(Dispatchers.IO) {
        val refnr = job.id.removePrefix("ba:")
        val encoded = Base64.getEncoder().encodeToString(refnr.toByteArray())
        runCatching {
            val d = http.getJson("$base/pc/v4/jobdetails/$encoded", headers)
            val text = d.str("stellenangebotsBeschreibung") ?: d.str("stellenbeschreibung")
            if (text.isNullOrBlank()) job else job.copy(description = text)
        }.getOrDefault(job)
    }

    companion object {
        fun parseSearch(root: JsonElement): List<Job> {
            val offers = (root as? JsonObject)?.get("stellenangebote") as? JsonArray ?: return emptyList()
            return offers.mapNotNull { o ->
                val refnr = o.str("refnr") ?: return@mapNotNull null
                val ort = o.obj("arbeitsort")
                val location = listOfNotNull(ort.str("ort"), ort.str("region")).distinct().joinToString(", ")
                Job(
                    id = "ba:$refnr",
                    title = o.str("titel") ?: o.str("beruf") ?: "Stelle",
                    company = o.str("arbeitgeber") ?: "",
                    location = location.ifBlank { "Deutschland" },
                    url = o.str("externeUrl") ?: "https://www.arbeitsagentur.de/jobsuche/jobdetail/$refnr",
                    description = o.str("beruf").orEmpty(),
                    sources = listOf("Bundesagentur"),
                    postedAt = o.str("aktuelleVeroeffentlichungsdatum"),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Greenhouse public job board API – one board token per company.
// ------------------------------------------------------------------------------------------------

class GreenhouseSource(private val http: OkHttpClient, private val boards: () -> List<String>) : JobSource {
    override val name = "Greenhouse"

    override suspend fun search(query: SearchQuery): List<Job> = withContext(Dispatchers.IO) {
        boards().flatMap { board ->
            runCatching {
                parseBoard(board, http.getJson("https://boards-api.greenhouse.io/v1/boards/$board/jobs?content=true"))
            }.getOrDefault(emptyList())
        }.filter { GermanLocations.matches(it.location) && matchesQuery(it, query) }
    }

    companion object {
        fun parseBoard(board: String, root: JsonElement): List<Job> {
            val jobs = (root as? JsonObject)?.get("jobs") as? JsonArray ?: return emptyList()
            return jobs.mapNotNull { j ->
                val id = (j as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Job(
                    id = "gh:$board:$id",
                    title = j.str("title").orEmpty(),
                    company = j.str("company_name") ?: board.replaceFirstChar { it.uppercase() },
                    location = j.obj("location").str("name").orEmpty(),
                    url = j.str("absolute_url").orEmpty(),
                    description = j.str("content").orEmpty(), // HTML-escaped; Html.toPlainText handles it
                    sources = listOf("Greenhouse"),
                    postedAt = j.str("updated_at"),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Lever postings API – one site name per company; EU-hosted companies live on api.eu.lever.co.
// ------------------------------------------------------------------------------------------------

class LeverSource(private val http: OkHttpClient, private val companies: () -> List<String>) : JobSource {
    override val name = "Lever"

    override suspend fun search(query: SearchQuery): List<Job> = withContext(Dispatchers.IO) {
        companies().flatMap { company ->
            val global = runCatching { http.getJson("https://api.lever.co/v0/postings/$company?mode=json") }.getOrNull()
            val root = if (global is JsonArray && global.isNotEmpty()) global
            else runCatching { http.getJson("https://api.eu.lever.co/v0/postings/$company?mode=json") }.getOrNull()
            root?.let { parse(company, it) }.orEmpty()
        }.filter { GermanLocations.matches(it.location) && matchesQuery(it, query) }
    }

    companion object {
        fun parse(company: String, root: JsonElement): List<Job> {
            val arr = root as? JsonArray ?: return emptyList()
            return arr.mapNotNull { p ->
                val id = p.str("id") ?: return@mapNotNull null
                val cats = p.obj("categories")
                val allLocations = (cats?.get("allLocations") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                val location = allLocations?.firstOrNull { GermanLocations.matches(it) } ?: cats.str("location").orEmpty()
                val lists = (p as JsonObject)["lists"]?.let { it as? JsonArray }?.joinToString("\n") { l ->
                    "<h3>${l.str("text").orEmpty()}</h3>${l.str("content").orEmpty()}"
                }.orEmpty()
                val salary = p.obj("salaryRange")?.let { s ->
                    val min = s["min"]?.jsonPrimitive?.intOrNull
                    val max = s["max"]?.jsonPrimitive?.intOrNull
                    val interval = s.str("interval").orEmpty()
                    if (min == null && max == null) null
                    else SalaryRange(min, max, s.str("currency") ?: "EUR", if ("month" in interval) "month" else "year")
                }
                Job(
                    id = "lever:$company:$id",
                    title = p.str("text").orEmpty(),
                    company = company.replace('-', ' ').replaceFirstChar { it.uppercase() },
                    location = location,
                    url = p.str("hostedUrl").orEmpty(),
                    description = listOf(p.str("description").orEmpty(), lists, p.str("additional").orEmpty()).joinToString("\n"),
                    sources = listOf("Lever"),
                    remoteHint = when (p.str("workplaceType")) {
                        "remote" -> RemotePolicy.REMOTE
                        "hybrid" -> RemotePolicy.HYBRID
                        "on-site", "onsite" -> RemotePolicy.ONSITE
                        else -> RemotePolicy.UNKNOWN
                    },
                    salaryHint = salary,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Arbeitnow – free aggregator focused on Germany, no key required.
// ------------------------------------------------------------------------------------------------

class ArbeitnowSource(private val http: OkHttpClient) : JobSource {
    override val name = "Arbeitnow"

    override suspend fun search(query: SearchQuery): List<Job> = withContext(Dispatchers.IO) {
        (1..3).flatMap { page ->
            runCatching { parse(http.getJson("https://www.arbeitnow.com/api/job-board-api?page=$page")) }.getOrDefault(emptyList())
        }.filter { matchesQuery(it, query) }
    }

    companion object {
        fun parse(root: JsonElement): List<Job> {
            val data = (root as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
            return data.mapNotNull { j ->
                val slug = j.str("slug") ?: return@mapNotNull null
                val remote = (j as JsonObject)["remote"]?.jsonPrimitive?.contentOrNull == "true"
                Job(
                    id = "an:$slug",
                    title = j.str("title").orEmpty(),
                    company = j.str("company_name").orEmpty(),
                    location = j.str("location").orEmpty(),
                    url = j.str("url").orEmpty(),
                    description = j.str("description").orEmpty(),
                    sources = listOf("Arbeitnow"),
                    remoteHint = if (remote) RemotePolicy.REMOTE else RemotePolicy.UNKNOWN,
                    postedAt = j.str("created_at"),
                )
            }
        }
    }
}

/** Result of one combined search, including which sources failed so the UI can say so. */
data class SearchOutcome(val jobs: List<Job>, val failedSources: List<String>)

class JobSearchService(private val sources: List<JobSource>) {
    suspend fun search(query: SearchQuery): SearchOutcome = coroutineScope {
        val results = sources.map { src -> src.name to async(Dispatchers.IO) { runCatching { src.search(query) } } }
            .map { (name, deferred) -> name to deferred.await() }
        SearchOutcome(
            jobs = Dedup.merge(results.flatMap { it.second.getOrDefault(emptyList()) }),
            failedSources = results.filter { it.second.isFailure }.map { it.first },
        )
    }
}
