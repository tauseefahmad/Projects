package de.jobcopilot.app.domain

import kotlin.math.roundToInt

/**
 * Explainable 0–100 fit score. Weighted soft signals (skills, role, location) plus hard checks
 * (German level, visa) that cap the score, so a posting needing German C1 never looks like
 * a great match for a B1 speaker no matter how well the skills line up.
 */
object FitScorer {

    fun score(job: Job, insights: PostingInsights, profile: Profile): FitResult {
        val reasons = mutableListOf<FitReason>()
        val userSkills = profile.skills.map { SkillCatalog.canonical(it).lowercase() }.toSet()

        // Skills: 50 points, required skills count double vs nice-to-have.
        val matchedReq = insights.requiredSkills.filter { it.lowercase() in userSkills }
        val missingReq = insights.requiredSkills.filterNot { it.lowercase() in userSkills }
        val matchedOpt = insights.niceToHaveSkills.filter { it.lowercase() in userSkills }
        val skillPoints = if (insights.requiredSkills.isEmpty() && insights.niceToHaveSkills.isEmpty()) {
            25.0 // nothing detectable: neutral
        } else {
            val total = insights.requiredSkills.size * 2.0 + insights.niceToHaveSkills.size
            val got = matchedReq.size * 2.0 + matchedOpt.size
            50.0 * got / total
        }
        if (matchedReq.isNotEmpty()) reasons += FitReason(true, "Matches: ${matchedReq.joinToString()}")
        if (matchedOpt.isNotEmpty()) reasons += FitReason(true, "Bonus skills: ${matchedOpt.joinToString()}")
        if (missingReq.isNotEmpty()) reasons += FitReason(false, "Missing: ${missingReq.joinToString()}")

        // Role: 15 points.
        val titleNorm = Dedup.normalize(job.title)
        val roleHit = profile.targetRoles.any { role ->
            val words = Dedup.normalize(role).split(" ").filter { it.length > 2 }
            words.isNotEmpty() && words.all { it in titleNorm }
        }
        val rolePoints = when {
            profile.targetRoles.isEmpty() -> 8.0
            roleHit -> 15.0
            else -> 3.0
        }
        if (roleHit) reasons += FitReason(true, "Title matches your target role")

        // Language: 20 points, plus a hard cap when the gap is large.
        var cap = 100
        val userGerman = profile.germanLevel
        val needed = insights.germanRequired
        val languagePoints = when {
            needed == null -> {
                if (insights.germanOptional) reasons += FitReason(true, "German is a plus, not required")
                else reasons += FitReason(true, "No German requirement found")
                20.0
            }
            userGerman >= needed -> {
                reasons += FitReason(true, "German ${needed.label} required – you have ${userGerman.label}")
                20.0
            }
            else -> {
                val gap = needed.ordinal - userGerman.ordinal
                val hint = if (insights.germanInferredFromPostingLanguage) " (posting is in German)" else ""
                val blocker = gap >= 2
                reasons += FitReason(false, "German ${needed.label} required$hint – you have ${userGerman.label}", hardBlocker = blocker)
                if (blocker) cap = minOf(cap, 35)
                if (gap == 1) 8.0 else 0.0
            }
        }

        // Location / remote: 10 points.
        val cityHit = profile.preferredCities.any { Dedup.normalize(it) in Dedup.normalize(job.location) }
        val locationPoints = when {
            insights.remote == RemotePolicy.REMOTE -> 10.0.also { reasons += FitReason(true, "Remote") }
            cityHit -> 10.0.also { reasons += FitReason(true, "In ${job.location}") }
            profile.preferredCities.isEmpty() -> 6.0
            insights.remote == RemotePolicy.HYBRID -> 5.0
            else -> 2.0.also { reasons += FitReason(false, "Outside your preferred cities") }
        }
        if (!profile.acceptsOnsite && insights.remote == RemotePolicy.ONSITE) {
            reasons += FitReason(false, "On-site only")
            cap = minOf(cap, 50)
        }

        // Visa: 5 points, hard cap if explicitly not offered.
        val visaPoints = if (!profile.needsVisaSponsorship) 5.0 else when (insights.sponsorship) {
            SponsorshipSignal.OFFERED -> 5.0.also { reasons += FitReason(true, "Visa sponsorship / relocation mentioned") }
            SponsorshipSignal.UNKNOWN -> 2.0.also { reasons += FitReason(false, "Visa sponsorship not mentioned – ask") }
            SponsorshipSignal.NOT_OFFERED -> 0.0.also {
                reasons += FitReason(false, "No visa sponsorship", hardBlocker = true)
                cap = minOf(cap, 25)
            }
        }

        // Salary is informational unless clearly below the user's floor.
        val salary = insights.salary
        if (salary != null && profile.minSalary != null && salary.period == "year") {
            val top = salary.max ?: salary.min
            if (top != null && top < profile.minSalary) {
                reasons += FitReason(false, "Salary ${salary.display()} below your minimum")
                cap = minOf(cap, 60)
            }
        }

        val raw = skillPoints + rolePoints + languagePoints + locationPoints + visaPoints
        return FitResult(
            score = minOf(raw.roundToInt(), cap).coerceIn(0, 100),
            reasons = reasons.sortedWith(compareByDescending<FitReason> { it.hardBlocker }.thenBy { it.positive }),
            matchedSkills = matchedReq + matchedOpt,
            missingSkills = missingReq,
        )
    }
}
