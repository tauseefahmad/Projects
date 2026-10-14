package de.jobcopilot.app.data.sources

import de.jobcopilot.app.domain.RemotePolicy
import de.jobcopilot.app.domain.SalaryRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers are tested against trimmed real-shaped payloads so API drift shows up as a test failure. */
class SourceParsingTest {

    @Test
    fun bundesagentur() {
        val payload = """
            {"stellenangebote":[{"beruf":"Softwareentwickler/in","titel":"Java Entwickler (m/w/d)","refnr":"10001-1000000001-S",
             "arbeitsort":{"plz":"52062","ort":"Aachen","region":"Nordrhein-Westfalen","land":"Deutschland"},
             "arbeitgeber":"Muster AG","aktuelleVeroeffentlichungsdatum":"2026-10-01"}],"maxErgebnisse":"1"}
        """
        val jobs = BundesagenturSource.parseSearch(json.parseToJsonElement(payload))
        assertEquals(1, jobs.size)
        assertEquals("ba:10001-1000000001-S", jobs[0].id)
        assertEquals("Aachen, Nordrhein-Westfalen", jobs[0].location)
        assertEquals("https://www.arbeitsagentur.de/jobsuche/jobdetail/10001-1000000001-S", jobs[0].url)
    }

    @Test
    fun greenhouse() {
        val payload = """
            {"jobs":[{"id":4012345,"title":"Senior Backend Engineer","absolute_url":"https://boards.greenhouse.io/acme/jobs/4012345",
              "location":{"name":"Berlin, Germany"},"updated_at":"2026-09-30T10:00:00Z",
              "content":"&lt;p&gt;We use &lt;strong&gt;Kotlin&lt;/strong&gt;&lt;/p&gt;","company_name":"Acme"}]}
        """
        val jobs = GreenhouseSource.parseBoard("acme", json.parseToJsonElement(payload))
        assertEquals("gh:acme:4012345", jobs[0].id)
        assertEquals("Acme", jobs[0].company)
        assertTrue(GermanLocations.matches(jobs[0].location))
    }

    @Test
    fun lever() {
        val payload = """
            [{"id":"abc-123","text":"Data Engineer","hostedUrl":"https://jobs.lever.co/acme/abc-123",
              "categories":{"location":"Munich","allLocations":["London","Munich"]},
              "description":"<p>Build pipelines.</p>","lists":[{"text":"Requirements","content":"<li>Python</li><li>Airflow</li>"}],
              "additional":"","workplaceType":"hybrid",
              "salaryRange":{"min":70000,"max":85000,"currency":"EUR","interval":"per-year-salary"}}]
        """
        val jobs = LeverSource.parse("acme", json.parseToJsonElement(payload))
        assertEquals("Munich", jobs[0].location)
        assertEquals(RemotePolicy.HYBRID, jobs[0].remoteHint)
        assertEquals(SalaryRange(70_000, 85_000), jobs[0].salaryHint)
        assertTrue(jobs[0].description.contains("Airflow"))
    }

    @Test
    fun arbeitnow() {
        val payload = """
            {"data":[{"slug":"backend-dev-acme-123","company_name":"Acme","title":"Backend Developer","description":"<p>Go</p>",
              "remote":true,"url":"https://www.arbeitnow.com/jobs/acme/backend-dev-acme-123","tags":[],"job_types":[],
              "location":"Hamburg","created_at":1727000000}]}
        """
        val jobs = ArbeitnowSource.parse(json.parseToJsonElement(payload))
        assertEquals(RemotePolicy.REMOTE, jobs[0].remoteHint)
        assertEquals("Hamburg", jobs[0].location)
    }

    @Test
    fun queryFilter() {
        val payload = """{"data":[{"slug":"x","company_name":"A","title":"Senior Kotlin Developer","description":"","remote":false,"url":"u","location":"Köln"}]}"""
        val job = ArbeitnowSource.parse(json.parseToJsonElement(payload)).single()
        assertTrue(matchesQuery(job, SearchQuery("kotlin developer", "köln")))
        assertTrue(!matchesQuery(job, SearchQuery("python", "")))
    }
}
