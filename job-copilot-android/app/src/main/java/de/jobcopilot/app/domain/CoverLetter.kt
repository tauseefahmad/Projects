package de.jobcopilot.app.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Serializable
enum class LetterLanguage { DE, EN }

/** The parts of a DIN 5008 letter that change per application. Sender/recipient come from Profile/Job. */
@Serializable
data class CoverLetter(
    val language: LetterLanguage,
    val subject: String,
    val salutation: String,
    val body: String,
    val closing: String,
) {
    fun dateLine(city: String, today: LocalDate = LocalDate.now()): String {
        val date = today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        val place = city.substringAfter(' ', city).trim()
        return if (place.isBlank()) date else "$place, $date"
    }

    companion object {
        /**
         * Parses model output of the form
         * `Betreff: ...` / blank / `Sehr geehrte ...,` / body / `Mit freundlichen Grüßen`.
         */
        fun parse(text: String, language: LetterLanguage, fallbackSubject: String): CoverLetter {
            val lines = text.trim().lines().toMutableList()
            var subject = fallbackSubject
            val subjectIdx = lines.indexOfFirst { it.trim().matches(Regex("(?i)(betreff|subject)\\s*:.*")) }
            if (subjectIdx >= 0) {
                subject = lines[subjectIdx].substringAfter(':').trim().trim('*')
                repeat(subjectIdx + 1) { lines.removeAt(0) }
            }
            while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)

            val defaultSalutation = if (language == LetterLanguage.DE) "Sehr geehrte Damen und Herren," else "Dear Hiring Team,"
            val salutation = if (lines.isNotEmpty() && Regex("(?i)^(sehr geehrte|liebe|hallo|guten tag|dear|hello)").containsMatchIn(lines.first().trim())) {
                lines.removeAt(0).trim()
            } else defaultSalutation

            val closingRegex = Regex("(?i)^(mit freundlichen grüßen|freundliche grüße|beste grüße|viele grüße|kind regards|best regards|sincerely|yours sincerely)[,]?$")
            val closingIdx = lines.indexOfLast { closingRegex.matches(it.trim()) }
            val defaultClosing = if (language == LetterLanguage.DE) "Mit freundlichen Grüßen" else "Kind regards"
            val closing = if (closingIdx >= 0) lines[closingIdx].trim().trimEnd(',') else defaultClosing
            val bodyLines = if (closingIdx >= 0) lines.subList(0, closingIdx) else lines

            return CoverLetter(
                language = language,
                subject = subject,
                salutation = salutation,
                body = bodyLines.joinToString("\n").trim(),
                closing = closing,
            )
        }
    }
}

/** Offline fallback when no API key is set. Plain but correct; the user edits it before sending. */
object TemplateLetterWriter {

    fun write(job: Job, insights: PostingInsights, fit: FitResult, profile: Profile, language: LetterLanguage): CoverLetter {
        val skills = fit.matchedSkills.take(4).ifEmpty { profile.skills.take(3) }
        val skillList = joinNatural(skills, language)
        return when (language) {
            LetterLanguage.DE -> CoverLetter(
                language = language,
                subject = "Bewerbung als ${job.title}",
                salutation = "Sehr geehrte Damen und Herren,",
                body = buildString {
                    append("mit großem Interesse habe ich Ihre Stellenanzeige für die Position ${job.title} bei ${job.company} gelesen. ")
                    append(if (profile.headline.isNotBlank()) "Als ${profile.headline} " else "Mit meinem fachlichen Hintergrund ")
                    append("möchte ich Ihr Team gerne unterstützen.\n\n")
                    if (skillList.isNotBlank()) {
                        append("In meiner bisherigen Tätigkeit habe ich fundierte Erfahrung mit $skillList gesammelt – ")
                        append("Kenntnisse, die für diese Stelle besonders relevant sind. ")
                    }
                    if (profile.summary.isNotBlank()) append(profile.summary.trim()).append(' ')
                    append("\n\n")
                    if (fit.missingSkills.isNotEmpty()) {
                        append("Mit ${fit.missingSkills.take(2).joinToString(" und ")} möchte ich mich zügig vertraut machen; ")
                        append("neue Technologien eigne ich mir erfahrungsgemäß schnell an.\n\n")
                    }
                    val de = profile.germanLevel
                    if (de != LanguageLevel.NONE && de != LanguageLevel.NATIVE) {
                        append("Meine Deutschkenntnisse (Niveau ${de.label}) baue ich kontinuierlich aus. ")
                    }
                    append("Über die Einladung zu einem persönlichen Gespräch freue ich mich sehr.")
                },
                closing = "Mit freundlichen Grüßen",
            )
            LetterLanguage.EN -> CoverLetter(
                language = language,
                subject = "Application for the position of ${job.title}",
                salutation = "Dear Hiring Team,",
                body = buildString {
                    append("I am writing to apply for the ${job.title} position at ${job.company}. ")
                    if (profile.headline.isNotBlank()) append("As ${profile.headline}, ")
                    append("I would be glad to contribute to your team.\n\n")
                    if (skillList.isNotBlank()) append("In my previous roles I have built solid experience with $skillList, which matches the core requirements of this role. ")
                    if (profile.summary.isNotBlank()) append(profile.summary.trim()).append(' ')
                    append("\n\n")
                    if (fit.missingSkills.isNotEmpty()) {
                        append("I am keen to deepen my knowledge of ${fit.missingSkills.take(2).joinToString(" and ")} and pick up new tools quickly.\n\n")
                    }
                    append("I would welcome the opportunity to discuss how I can support ${job.company}.")
                },
                closing = "Kind regards",
            )
        }
    }

    private fun joinNatural(items: List<String>, language: LetterLanguage): String {
        val and = if (language == LetterLanguage.DE) "und" else "and"
        return when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " $and " + items.last()
        }
    }
}

/** Builds the prompt for the LLM writer. Kept here so it can be unit-tested without Android. */
object LetterPrompt {

    val system = """
        You write job application letters (Anschreiben) for international professionals applying in Germany.
        German recruiters read many generic letters; yours must be specific to the posting and the applicant.

        Rules:
        - Use only facts from the applicant profile. Never invent employers, degrees, numbers, or skills.
          If the profile is thin, write fewer, stronger sentences rather than padding.
        - Connect 2–3 concrete requirements from the posting to evidence in the profile.
        - If a required skill is missing, do not claim it; you may briefly show willingness to learn it.
        - German letters: formal "Sie", modern business German (no "hiermit bewerbe ich mich"), DIN 5008 conventions,
          lowercase first word after the comma of the salutation. English letters: concise British/international business English.
        - 250–380 words for the body. No bullet lists, no markdown, no placeholders like [Company].
        - Output exactly this plain-text shape and nothing else:
          Betreff: <subject line>   (use "Subject:" for English)
          <blank line>
          <salutation>
          <body paragraphs separated by blank lines>
          <closing formula>
    """.trimIndent()

    fun user(job: Job, insights: PostingInsights, fit: FitResult, profile: Profile, language: LetterLanguage): String = buildString {
        appendLine("Write the letter in ${if (language == LetterLanguage.DE) "German" else "English"}.")
        appendLine()
        appendLine("<posting>")
        appendLine("Title: ${job.title}")
        appendLine("Company: ${job.company}")
        appendLine("Location: ${job.location}")
        appendLine(Html.toPlainText(job.description).take(12_000))
        appendLine("</posting>")
        appendLine()
        appendLine("<applicant_profile>")
        appendLine("Name: ${profile.fullName}")
        if (profile.headline.isNotBlank()) appendLine("Headline: ${profile.headline}")
        if (profile.summary.isNotBlank()) appendLine("Summary: ${profile.summary}")
        if (profile.experience.isNotBlank()) appendLine("Experience:\n${profile.experience}")
        if (profile.education.isNotBlank()) appendLine("Education:\n${profile.education}")
        appendLine("Skills: ${profile.skills.joinToString()}")
        appendLine("Languages: ${profile.languages.joinToString { "${it.language} ${it.level.label}" }}")
        appendLine("</applicant_profile>")
        appendLine()
        appendLine("<fit_analysis>")
        appendLine("Matched skills: ${fit.matchedSkills.joinToString().ifBlank { "none detected" }}")
        appendLine("Missing required skills: ${fit.missingSkills.joinToString().ifBlank { "none" }}")
        insights.germanRequired?.let { appendLine("German required: ${it.label}") }
        appendLine("</fit_analysis>")
    }
}
