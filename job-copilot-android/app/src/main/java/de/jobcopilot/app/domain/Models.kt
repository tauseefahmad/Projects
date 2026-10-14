package de.jobcopilot.app.domain

import kotlinx.serialization.Serializable

/** CEFR levels, ordered so that comparisons work (`B2 < C1`). */
@Serializable
enum class LanguageLevel(val label: String) {
    NONE("none"), A1("A1"), A2("A2"), B1("B1"), B2("B2"), C1("C1"), C2("C2"), NATIVE("native");

    companion object {
        fun parse(text: String): LanguageLevel? =
            entries.firstOrNull { it.label.equals(text.trim(), ignoreCase = true) }
    }
}

@Serializable
enum class RemotePolicy { REMOTE, HYBRID, ONSITE, UNKNOWN }

@Serializable
enum class SponsorshipSignal { OFFERED, NOT_OFFERED, UNKNOWN }

@Serializable
data class SalaryRange(val min: Int?, val max: Int?, val currency: String = "EUR", val period: String = "year") {
    fun display(): String {
        val fmt = { v: Int -> if (v >= 1000) "${v / 1000}k" else v.toString() }
        val range = when {
            min != null && max != null && min != max -> "${fmt(min)}–${fmt(max)}"
            min != null -> fmt(min)
            max != null -> "up to ${fmt(max)}"
            else -> "?"
        }
        return "$range $currency/$period"
    }
}

/** A posting normalised from any source. */
@Serializable
data class Job(
    val id: String,
    val title: String,
    val company: String,
    val location: String,
    val url: String,
    val description: String = "",
    val sources: List<String> = emptyList(),
    val postedAt: String? = null,
    val remoteHint: RemotePolicy = RemotePolicy.UNKNOWN,
    val salaryHint: SalaryRange? = null,
) {
    /** Key used to merge the same posting found on several boards. */
    val dedupKey: String get() = Dedup.key(this)
}

@Serializable
data class LanguageSkill(val language: String, val level: LanguageLevel)

@Serializable
data class Profile(
    val fullName: String = "",
    val street: String = "",
    val postalCity: String = "",
    val email: String = "",
    val phone: String = "",
    val headline: String = "",
    val summary: String = "",
    val experience: String = "",
    val education: String = "",
    val skills: List<String> = emptyList(),
    val targetRoles: List<String> = emptyList(),
    val preferredCities: List<String> = emptyList(),
    val languages: List<LanguageSkill> = listOf(
        LanguageSkill("English", LanguageLevel.C1),
        LanguageSkill("German", LanguageLevel.B1),
    ),
    val needsVisaSponsorship: Boolean = false,
    val acceptsOnsite: Boolean = true,
    val minSalary: Int? = null,
) {
    val germanLevel: LanguageLevel
        get() = languages.firstOrNull { it.language.lowercase() in setOf("german", "deutsch") }?.level
            ?: LanguageLevel.NONE

    val isEmpty: Boolean get() = fullName.isBlank() && skills.isEmpty()
}

/** What the analyzer extracted from a posting's text. */
@Serializable
data class PostingInsights(
    val requiredSkills: List<String>,
    val niceToHaveSkills: List<String>,
    val germanRequired: LanguageLevel?,
    val germanOptional: Boolean,
    val germanInferredFromPostingLanguage: Boolean,
    val englishRequired: Boolean,
    val sponsorship: SponsorshipSignal,
    val remote: RemotePolicy,
    val salary: SalaryRange?,
    val postingLanguage: String,
)

@Serializable
data class FitReason(val positive: Boolean, val text: String, val hardBlocker: Boolean = false)

@Serializable
data class FitResult(
    val score: Int,
    val reasons: List<FitReason>,
    val matchedSkills: List<String>,
    val missingSkills: List<String>,
)
