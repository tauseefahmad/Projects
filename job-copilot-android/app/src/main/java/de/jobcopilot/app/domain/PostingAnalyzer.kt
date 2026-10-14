package de.jobcopilot.app.domain

/**
 * Rule-based extraction of the fields internationals care about: language level,
 * visa sponsorship, remote policy, salary, and required vs. nice-to-have skills.
 *
 * Runs on-device and offline. It is deliberately conservative: when a posting
 * doesn't say something, the result is UNKNOWN rather than a guess.
 */
object PostingAnalyzer {

    fun analyze(job: Job): PostingInsights {
        val text = Html.toPlainText(job.description)
        val full = "${job.title}\n$text"
        val lower = full.lowercase()
        val postingLanguage = detectLanguage(text.ifBlank { job.title })

        val (requiredPart, optionalPart) = splitNiceToHave(full)
        val required = SkillCatalog.find(requiredPart)
        val optional = SkillCatalog.find(optionalPart) - required.toSet()

        val (germanLevel, germanOptional) = germanRequirement(lower, optionalPart.lowercase())
        val inferred = germanLevel == null && !germanOptional && postingLanguage == "de" &&
            !Regex("no german|german (is )?not required|keine deutschkenntnisse").containsMatchIn(lower)

        return PostingInsights(
            requiredSkills = required,
            niceToHaveSkills = optional,
            germanRequired = germanLevel ?: if (inferred) LanguageLevel.B2 else null,
            germanOptional = germanOptional,
            germanInferredFromPostingLanguage = inferred,
            englishRequired = Regex("(fluent|excellent|very good|business[- ]fluent|sehr gute?)\\s+(english|englisch)|englisch(kenntnisse)?\\s+(fließend|verhandlungssicher)")
                .containsMatchIn(lower),
            sponsorship = sponsorship(lower),
            remote = if (job.remoteHint != RemotePolicy.UNKNOWN) job.remoteHint else remote(lower),
            salary = job.salaryHint ?: salary(full),
            postingLanguage = postingLanguage,
        )
    }

    // --- language of the posting -------------------------------------------------------------

    private val germanWords = setOf(
        "und", "der", "die", "das", "wir", "sie", "mit", "für", "ihre", "deine", "bei", "eine", "sowie",
        "kenntnisse", "erfahrung", "aufgaben", "bieten", "unser", "ist", "von", "zu", "im", "auf",
    )
    private val englishWords = setOf(
        "and", "the", "we", "you", "with", "for", "your", "our", "of", "to", "in", "experience",
        "skills", "team", "will", "are", "is", "on", "about", "what",
    )

    fun detectLanguage(text: String): String {
        val words = text.lowercase().split(Regex("[^a-zäöüß]+")).filter { it.isNotBlank() }
        val de = words.count { it in germanWords }
        val en = words.count { it in englishWords }
        return if (de > en) "de" else "en"
    }

    // --- nice-to-have split -------------------------------------------------------------------

    private val niceToHaveHeader = Regex(
        "(nice[- ]to[- ]have|bonus points|it'?s a plus|is a plus|would be a plus|preferred qualifications|" +
            "wünschenswert|von vorteil|idealerweise|ein plus|pluspunkte|optional)",
        RegexOption.IGNORE_CASE,
    )

    /** Text after a "nice to have" header (until the next blank-line section) counts as optional. */
    fun splitNiceToHave(text: String): Pair<String, String> {
        val required = StringBuilder()
        val optional = StringBuilder()
        var inOptional = false
        for (line in text.lines()) {
            val t = line.trim()
            val isBullet = t.isNotEmpty() && (t[0] in "•-*–·" || Regex("^\\d+[.)]\\s").containsMatchIn(t))
            val headerLike = !isBullet && t.isNotEmpty() &&
                (t.length < 80 && t.endsWith(":") || t.length <= 40 && !t.contains('.') && !t.contains(','))
            if (niceToHaveHeader.containsMatchIn(line)) {
                // A sentence like "Kubernetes is a plus" marks just that line optional.
                if (headerLike) inOptional = true else { optional.appendLine(line); continue }
            } else if (headerLike && line.isNotBlank()) {
                inOptional = false
            }
            (if (inOptional) optional else required).appendLine(line)
        }
        return required.toString() to optional.toString()
    }

    // --- German requirement -------------------------------------------------------------------

    private val levelPattern = "(a1|a2|b1|b2|c1|c2)"
    private val explicitLevel = listOf(
        Regex("(german|deutsch)[^.\\n]{0,40}?\\b$levelPattern\\b"),
        Regex("\\b$levelPattern\\b[^.\\n]{0,25}?(german|deutsch)"),
    )
    private val fluentGerman = Regex(
        "(fluent|native|business[- ]fluent|excellent|proficient|full professional)[^.\\n]{0,25}german|" +
            "german[^.\\n]{0,15}(fluent|native speaker|mother tongue)|" +
            "(fließend|verhandlungssicher|sehr gut|exzellent|perfekt)[^.\\n]{0,25}deutsch|" +
            "deutsch[^.\\n]{0,25}(fließend|verhandlungssicher|in wort und schrift|muttersprach)",
    )
    private val goodGerman = Regex(
        "(good|solid|strong|working|conversational)[^.\\n]{0,15}german|gute[^.\\n]{0,20}deutsch|deutschkenntnisse",
    )
    private val basicGerman = Regex("(basic|some)[^.\\n]{0,15}german|grundkenntnisse[^.\\n]{0,15}deutsch")
    private val noGerman = Regex(
        "(no|without)\\s+german( required| needed| skills)?|german (is )?not (required|necessary|needed)|" +
            "keine deutschkenntnisse|english[- ]speaking (team|environment|company)|our (working|company) language is english",
    )

    private fun germanRequirement(lower: String, optionalLower: String): Pair<LanguageLevel?, Boolean> {
        if (noGerman.containsMatchIn(lower)) return null to false
        val optional = Regex("german|deutsch").containsMatchIn(optionalLower) ||
            Regex("german[^.\\n]{0,30}(is a plus|nice to have|a bonus|advantage|helpful)|deutsch[^.\\n]{0,30}(von vorteil|wünschenswert)")
                .containsMatchIn(lower)
        for (pattern in explicitLevel) {
            pattern.find(lower)?.let { m ->
                val level = m.groupValues.firstNotNullOfOrNull { LanguageLevel.parse(it) }
                if (level != null) return (if (optional) null else level) to optional
            }
        }
        if (optional) return null to true
        return when {
            fluentGerman.containsMatchIn(lower) -> LanguageLevel.C1 to false
            basicGerman.containsMatchIn(lower) -> LanguageLevel.A2 to false
            goodGerman.containsMatchIn(lower) -> LanguageLevel.B2 to false
            else -> null to false
        }
    }

    // --- visa ---------------------------------------------------------------------------------

    private val noSponsorship = Regex(
        "(no|not able to|unable to|cannot|can't|do not|don't|does not|doesn't) (offer |provide )?(visa )?sponsor|" +
            "without (visa )?sponsorship|must (already )?(have|hold) (a valid |the )?(right|permit|authori[sz]ation) to work|" +
            "eu work permit (is )?required|(gültige )?arbeitserlaubnis (ist )?(erforderlich|voraussetzung)|keine visa",
    )
    private val sponsorshipOffered = Regex(
        "visa sponsorship|sponsor (your |a )?visa|we sponsor|relocation (support|package|assistance)|" +
            "blue card|blaue karte|visa support|unterstützung (beim|bei der) (umzug|visum)|relocation",
    )

    private fun sponsorship(lower: String): SponsorshipSignal = when {
        noSponsorship.containsMatchIn(lower) -> SponsorshipSignal.NOT_OFFERED
        sponsorshipOffered.containsMatchIn(lower) -> SponsorshipSignal.OFFERED
        else -> SponsorshipSignal.UNKNOWN
    }

    // --- remote -------------------------------------------------------------------------------

    private fun remote(lower: String): RemotePolicy = when {
        Regex("hybrid|\\d\\s*(days?|tage?)\\s*(per week |pro woche |/woche )?(in the office|im büro|remote|homeoffice)").containsMatchIn(lower) -> RemotePolicy.HYBRID
        Regex("fully remote|100\\s?% remote|remote[- ]first|remote within germany|full[- ]remote|vollständig remote|100\\s?% (homeoffice|mobil)").containsMatchIn(lower) -> RemotePolicy.REMOTE
        Regex("homeoffice|home office|mobiles arbeiten|remote").containsMatchIn(lower) -> RemotePolicy.HYBRID
        Regex("on[- ]site|onsite|vor ort|in our office|präsenz").containsMatchIn(lower) -> RemotePolicy.ONSITE
        else -> RemotePolicy.UNKNOWN
    }

    // --- salary -------------------------------------------------------------------------------

    private val amount = "(\\d{1,3}(?:[.,\\s]\\d{3})+|\\d{2,3}\\s?k|\\d{4,6})"
    private val salaryRange = Regex(
        "(?:€|eur)\\s?$amount\\s*(?:-|–|bis|to)\\s*(?:€|eur)?\\s?$amount|" +
            "$amount\\s*(?:€|eur)?\\s*(?:-|–|bis|to)\\s*$amount\\s*(?:€|eur)",
        RegexOption.IGNORE_CASE,
    )
    private val salarySingle = Regex(
        "(?:ab|from|starting at|mindestens|min\\.?)\\s*(?:€|eur)?\\s?$amount\\s*(?:€|eur)",
        RegexOption.IGNORE_CASE,
    )

    private fun parseAmount(raw: String): Int? {
        val s = raw.lowercase().replace(" ", "")
        if (s.endsWith("k")) return s.dropLast(1).toIntOrNull()?.times(1000)
        val v = s.replace(".", "").replace(",", "").toIntOrNull() ?: return null
        return if (v < 1000) v * 1000 else v
    }

    fun salary(text: String): SalaryRange? {
        salaryRange.find(text)?.let { m ->
            val nums = m.groupValues.drop(1).filter { it.isNotBlank() }.mapNotNull(::parseAmount)
            if (nums.size >= 2) return annualise(nums[0], nums[1])
        }
        salarySingle.find(text)?.let { m ->
            val v = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let(::parseAmount)
            if (v != null) return annualise(v, null)
        }
        return null
    }

    /** Amounts under 10k are almost always monthly (Tarif / Werkstudent postings). */
    private fun annualise(min: Int, max: Int?): SalaryRange? {
        if (min < 1000) return null
        return if (min < 10_000) SalaryRange(min, max, period = "month") else SalaryRange(min, max)
    }
}

/** Minimal HTML → text, good enough for job descriptions. */
object Html {
    fun toPlainText(html: String): String {
        if (!html.contains('<') && !html.contains("&lt;")) return html
        val unescaped = if (html.contains("&lt;")) decodeEntities(html) else html
        return decodeEntities(
            unescaped
                .replace(Regex("(?i)<br\\s*/?>|</p>|</li>|</h[1-6]>|</div>"), "\n")
                .replace(Regex("(?i)<li[^>]*>"), "\n• ")
                .replace(Regex("<[^>]+>"), ""),
        ).replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n").trim()
    }

    private fun decodeEntities(s: String) = s
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&auml;", "ä").replace("&ouml;", "ö")
        .replace("&uuml;", "ü").replace("&Auml;", "Ä").replace("&Ouml;", "Ö").replace("&Uuml;", "Ü")
        .replace("&szlig;", "ß").replace("&euro;", "€").replace("&ndash;", "–").replace("&amp;", "&")
}
