package de.jobcopilot.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerAndScorerTest {

    private fun job(title: String, description: String, company: String = "Acme GmbH", location: String = "Berlin") =
        Job(id = title, title = title, company = company, location = location, url = "https://example.com", description = description)

    private val englishPosting = job(
        "Backend Engineer (m/w/d)",
        """
        <p>We are an English-speaking team in Berlin. Hybrid, 2 days per week in the office.</p>
        <h3>What you bring:</h3>
        <ul><li>3+ years with Kotlin or Java</li><li>Spring Boot and PostgreSQL</li><li>Docker</li></ul>
        <h3>Nice to have:</h3>
        <ul><li>Kubernetes</li><li>German is a plus</li></ul>
        <p>Salary: €65.000 – €80.000 per year. We offer visa sponsorship and relocation support.</p>
        """.trimIndent(),
    )

    private val germanPosting = job(
        "Softwareentwickler Java (m/w/d)",
        """
        Ihre Aufgaben:
        • Entwicklung von Microservices mit Java und Spring Boot
        • Betrieb in Kubernetes
        Ihr Profil:
        • Erfahrung mit Java, SQL und Docker
        • Verhandlungssichere Deutschkenntnisse in Wort und Schrift
        Wir bieten: 30 Tage Urlaub und mobiles Arbeiten.
        """.trimIndent(),
        company = "Muster AG",
        location = "Aachen",
    )

    @Test
    fun extractsRequiredAndOptionalSkills() {
        val i = PostingAnalyzer.analyze(englishPosting)
        assertTrue(i.requiredSkills.containsAll(listOf("Kotlin", "Java", "Spring Boot", "PostgreSQL", "Docker")))
        assertTrue("Kubernetes" in i.niceToHaveSkills)
        assertFalse("Kubernetes" in i.requiredSkills)
        assertFalse("JavaScript" in i.requiredSkills)
    }

    @Test
    fun flagsForEnglishPosting() {
        val i = PostingAnalyzer.analyze(englishPosting)
        assertNull(i.germanRequired)
        assertEquals(SponsorshipSignal.OFFERED, i.sponsorship)
        assertEquals(RemotePolicy.HYBRID, i.remote)
        assertEquals(SalaryRange(65_000, 80_000), i.salary)
        assertEquals("en", i.postingLanguage)
    }

    @Test
    fun detectsGermanC1() {
        val i = PostingAnalyzer.analyze(germanPosting)
        assertEquals(LanguageLevel.C1, i.germanRequired)
        assertEquals("de", i.postingLanguage)
        assertTrue("Kubernetes" in i.requiredSkills)
    }

    @Test
    fun explicitLevelAndNoSponsorship() {
        val i = PostingAnalyzer.analyze(job("Data Analyst", "German (B2) required. We cannot offer visa sponsorship. Python and SQL."))
        assertEquals(LanguageLevel.B2, i.germanRequired)
        assertEquals(SponsorshipSignal.NOT_OFFERED, i.sponsorship)
    }

    @Test
    fun monthlySalaryAndK() {
        assertEquals(SalaryRange(3_500, 4_200, period = "month"), PostingAnalyzer.salary("Gehalt 3.500 € - 4.200 € brutto monatlich"))
        assertEquals(SalaryRange(70_000, 90_000), PostingAnalyzer.salary("Compensation: €70k-90k"))
    }

    @Test
    fun goDoesNotMatchGood() {
        assertFalse("Go" in SkillCatalog.find("good communication skills"))
        assertTrue("Go" in SkillCatalog.find("Experience with Golang"))
        assertEquals("Kubernetes", SkillCatalog.canonical("k8s"))
        assertEquals("PostgreSQL", SkillCatalog.canonical("postgres"))
    }

    @Test
    fun scoreExplainsMissingSkillsAndGermanGap() {
        val profile = Profile(
            fullName = "Ayesha Khan",
            skills = listOf("Java", "Spring Boot", "SQL", "docker"),
            targetRoles = listOf("Java Developer", "Softwareentwickler"),
            preferredCities = listOf("Aachen"),
            languages = listOf(LanguageSkill("English", LanguageLevel.C1), LanguageSkill("German", LanguageLevel.B1)),
        )
        val fit = FitScorer.score(germanPosting, PostingAnalyzer.analyze(germanPosting), profile)
        assertTrue(fit.score <= 35) // C1 vs B1 is a hard blocker
        assertTrue(fit.reasons.first().hardBlocker)
        assertTrue(fit.reasons.any { !it.positive && "Kubernetes" in it.text })
        assertTrue(fit.reasons.any { "German C1 required" in it.text })
    }

    @Test
    fun goodMatchScoresHigh() {
        val profile = Profile(
            skills = listOf("Kotlin", "Java", "Spring Boot", "PostgreSQL", "Docker"),
            targetRoles = listOf("Backend Engineer"),
            preferredCities = listOf("Berlin"),
            needsVisaSponsorship = true,
        )
        val fit = FitScorer.score(englishPosting, PostingAnalyzer.analyze(englishPosting), profile)
        assertTrue("score was ${fit.score}", fit.score >= 85)
        assertTrue(fit.missingSkills.isEmpty())
    }

    @Test
    fun dedupMergesSamePostingAcrossSources() {
        val a = job("Backend Engineer (m/w/d)", "short", company = "Acme GmbH", location = "Berlin, Germany").copy(id = "1", sources = listOf("Bundesagentur"))
        val b = job("Backend Engineer", "a much longer description", company = "ACME", location = "Berlin").copy(id = "2", sources = listOf("Greenhouse"))
        val merged = Dedup.merge(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals("2", merged[0].id)
        assertEquals(listOf("Bundesagentur", "Greenhouse"), merged[0].sources)
    }

    @Test
    fun parsesModelLetterOutput() {
        val text = """
            Betreff: Bewerbung als Backend Engineer – Ref. 123

            Sehr geehrte Frau Schmidt,
            seit drei Jahren entwickle ich Microservices.

            Ich freue mich auf ein Gespräch.

            Mit freundlichen Grüßen
        """.trimIndent()
        val l = CoverLetter.parse(text, LetterLanguage.DE, "fallback")
        assertEquals("Bewerbung als Backend Engineer – Ref. 123", l.subject)
        assertEquals("Sehr geehrte Frau Schmidt,", l.salutation)
        assertTrue(l.body.startsWith("seit drei Jahren"))
        assertTrue(l.body.endsWith("Gespräch."))
        assertEquals("Mit freundlichen Grüßen", l.closing)
    }

    @Test
    fun templateLetterIsUsableOffline() {
        val profile = Profile(fullName = "Ayesha Khan", skills = listOf("Java"), headline = "Java-Entwicklerin")
        val i = PostingAnalyzer.analyze(germanPosting)
        val fit = FitScorer.score(germanPosting, i, profile)
        val l = TemplateLetterWriter.write(germanPosting, i, fit, profile, LetterLanguage.DE)
        assertTrue(l.subject.contains("Softwareentwickler"))
        assertTrue(l.body.contains("Muster AG"))
        assertFalse(l.body.contains("["))
    }
}
