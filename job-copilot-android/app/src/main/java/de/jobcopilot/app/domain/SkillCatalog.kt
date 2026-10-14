package de.jobcopilot.app.domain

/**
 * Skills recognised in postings. Each entry is a canonical name plus aliases.
 * Matching is word-boundary based so "Go" doesn't match "good" and "Java" doesn't match "JavaScript".
 */
object SkillCatalog {
    private val entries: Map<String, List<String>> = mapOf(
        // Languages
        "Java" to listOf("java"),
        "Kotlin" to listOf("kotlin"),
        "Python" to listOf("python"),
        "JavaScript" to listOf("javascript", "js"),
        "TypeScript" to listOf("typescript"),
        "Go" to listOf("golang", "go lang", "go(?= developer| engineer|,| and)"),
        "Rust" to listOf("rust"),
        "C#" to listOf("c#", "c sharp"),
        "C++" to listOf("c\\+\\+"),
        "C" to listOf("c(?= programming| language)", "embedded c"),
        "PHP" to listOf("php"),
        "Ruby" to listOf("ruby"),
        "Scala" to listOf("scala"),
        "Swift" to listOf("swift"),
        "R" to listOf("r(?= programming| studio)", "rstudio"),
        "MATLAB" to listOf("matlab"),
        "SQL" to listOf("sql"),
        // Frameworks
        "Spring Boot" to listOf("spring boot", "spring"),
        "React" to listOf("react", "react\\.js", "reactjs"),
        "Angular" to listOf("angular"),
        "Vue" to listOf("vue", "vue\\.js"),
        "Node.js" to listOf("node\\.js", "nodejs"),
        "Django" to listOf("django"),
        "FastAPI" to listOf("fastapi"),
        "Flask" to listOf("flask"),
        ".NET" to listOf("\\.net", "dotnet", "asp\\.net"),
        "Android" to listOf("android"),
        "iOS" to listOf("ios"),
        "Jetpack Compose" to listOf("jetpack compose", "compose ui"),
        "Flutter" to listOf("flutter"),
        // Data / ML
        "Machine Learning" to listOf("machine learning", "maschinelles lernen", "ml"),
        "Deep Learning" to listOf("deep learning"),
        "PyTorch" to listOf("pytorch"),
        "TensorFlow" to listOf("tensorflow"),
        "LLMs" to listOf("llm", "llms", "large language models?", "generative ai", "genai"),
        "NLP" to listOf("nlp", "natural language processing"),
        "Computer Vision" to listOf("computer vision"),
        "pandas" to listOf("pandas"),
        "Spark" to listOf("spark", "pyspark"),
        "Airflow" to listOf("airflow"),
        "dbt" to listOf("dbt"),
        "Power BI" to listOf("power bi", "powerbi"),
        "Tableau" to listOf("tableau"),
        "Excel" to listOf("ms excel", "microsoft excel", "excel(?! in| at| with)"),
        "Statistics" to listOf("statistics", "statistik"),
        // Data stores
        "PostgreSQL" to listOf("postgresql", "postgres"),
        "MySQL" to listOf("mysql"),
        "MongoDB" to listOf("mongodb", "mongo"),
        "Redis" to listOf("redis"),
        "Elasticsearch" to listOf("elasticsearch", "opensearch"),
        "Kafka" to listOf("kafka"),
        "Snowflake" to listOf("snowflake"),
        "BigQuery" to listOf("bigquery"),
        // Cloud / infra
        "AWS" to listOf("aws", "amazon web services"),
        "Azure" to listOf("azure"),
        "GCP" to listOf("gcp", "google cloud"),
        "Docker" to listOf("docker", "container"),
        "Kubernetes" to listOf("kubernetes", "k8s"),
        "Terraform" to listOf("terraform"),
        "CI/CD" to listOf("ci/cd", "ci / cd", "continuous integration", "github actions", "gitlab ci", "jenkins"),
        "Linux" to listOf("linux"),
        "Git" to listOf("git"),
        "Microservices" to listOf("microservices?", "micro-services?"),
        "REST APIs" to listOf("restful", "rest(?:ful)?[- ]apis?", "rest(?=,| and| or| services)"),
        "GraphQL" to listOf("graphql"),
        // Engineering / industry (common in German postings)
        "SAP" to listOf("sap", "s/4hana", "abap"),
        "CAD" to listOf("cad", "catia", "solidworks", "autocad", "siemens nx"),
        "PLC" to listOf("plc", "sps", "simatic", "tia portal"),
        "Embedded" to listOf("embedded"),
        "AUTOSAR" to listOf("autosar"),
        "FEM" to listOf("fem", "finite elemente?", "finite element", "ansys", "abaqus"),
        "Six Sigma" to listOf("six sigma", "lean management", "lean manufacturing"),
        "Agile" to listOf("agile", "agil", "scrum", "kanban"),
        "Jira" to listOf("jira"),
        // Business
        "Project Management" to listOf("project management", "projektmanagement", "pmp", "prince2"),
        "Product Management" to listOf("product management", "produktmanagement"),
        "Salesforce" to listOf("salesforce"),
        "SEO" to listOf("seo"),
        "Figma" to listOf("figma"),
        "UX" to listOf("ux", "user experience", "ui/ux"),
        "Accounting" to listOf("accounting", "buchhaltung", "hgb", "ifrs"),
        "Controlling" to listOf("controlling"),
        "Sales" to listOf("sales", "vertrieb"),
        "Marketing" to listOf("marketing"),
        "Recruiting" to listOf("recruiting", "talent acquisition"),
        "Customer Support" to listOf("customer support", "kundenservice", "customer success"),
    )

    private val patterns: List<Pair<String, Regex>> = entries.map { (name, aliases) ->
        name to Regex(
            aliases.joinToString("|") { "(?<![\\p{L}\\p{N}+#.])(?:$it)(?![\\p{L}\\p{N}+#])" },
            RegexOption.IGNORE_CASE,
        )
    }

    val allSkills: List<String> get() = entries.keys.sorted()

    /** Canonical skill names mentioned in [text], in catalog order. */
    fun find(text: String): List<String> = patterns.filter { (_, re) -> re.containsMatchIn(text) }.map { it.first }

    /** Maps whatever the user typed ("k8s", "postgres") to the canonical name. */
    fun canonical(userSkill: String): String =
        patterns.firstOrNull { (_, re) -> re.matches(userSkill.trim()) }?.first ?: userSkill.trim()
}
