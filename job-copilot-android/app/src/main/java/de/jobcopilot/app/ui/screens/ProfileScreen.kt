package de.jobcopilot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jobcopilot.app.domain.LanguageLevel
import de.jobcopilot.app.domain.LanguageSkill
import de.jobcopilot.app.ui.MainViewModel

private fun List<String>.csv() = joinToString(", ")
private fun String.fromCsv() = split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(vm: MainViewModel) {
    val saved by vm.profile.collectAsStateWithLifecycle()
    // Local editing copy; keyed on the saved profile so "delete all data" resets the form.
    var p by remember(saved) { mutableStateOf(saved) }
    var skills by remember(saved) { mutableStateOf(saved.skills.csv()) }
    var roles by remember(saved) { mutableStateOf(saved.targetRoles.csv()) }
    var cities by remember(saved) { mutableStateOf(saved.preferredCities.csv()) }
    var otherLanguages by remember(saved) {
        mutableStateOf(saved.languages.filterNot { it.language.lowercase() in setOf("german", "deutsch") }
            .joinToString(", ") { "${it.language} ${it.level.label}" })
    }
    var minSalary by remember(saved) { mutableStateOf(saved.minSalary?.toString().orEmpty()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Your profile", style = MaterialTheme.typography.headlineSmall)
        Text("Stored encrypted on this device only. Used for fit scores, letters, and the CV.", style = MaterialTheme.typography.bodySmall)

        Section("Contact (letterhead)")
        Field("Full name", p.fullName) { p = p.copy(fullName = it) }
        Field("Street and number", p.street) { p = p.copy(street = it) }
        Field("Postcode and city (e.g. 52062 Aachen)", p.postalCity) { p = p.copy(postalCity = it) }
        Field("Email", p.email, KeyboardType.Email) { p = p.copy(email = it) }
        Field("Phone", p.phone, KeyboardType.Phone) { p = p.copy(phone = it) }

        Section("What you're looking for")
        Field("Target roles, comma-separated", roles) { roles = it }
        Field("Preferred cities, comma-separated", cities) { cities = it }
        Field("Minimum annual salary (€, optional)", minSalary, KeyboardType.Number) { minSalary = it.filter(Char::isDigit) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("I need visa sponsorship", Modifier.weight(1f))
            Switch(p.needsVisaSponsorship, { p = p.copy(needsVisaSponsorship = it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("On-site only jobs are OK", Modifier.weight(1f))
            Switch(p.acceptsOnsite, { p = p.copy(acceptsOnsite = it) })
        }

        Section("Languages")
        Text("German level")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LanguageLevel.entries.forEach { level ->
                FilterChip(
                    selected = p.germanLevel == level,
                    onClick = {
                        p = p.copy(languages = p.languages.filterNot { it.language.lowercase() in setOf("german", "deutsch") } +
                            LanguageSkill("German", level))
                    },
                    label = { Text(level.label) },
                )
            }
        }
        Field("Other languages (e.g. English C1, Urdu native)", otherLanguages) { otherLanguages = it }

        Section("Experience")
        Field("Headline (e.g. Backend Developer with 4 years of Java)", p.headline) { p = p.copy(headline = it) }
        Field("Skills, comma-separated", skills) { skills = it }
        Field("Short summary", p.summary, minHeight = 100) { p = p.copy(summary = it) }
        Field("Work experience (one block per job, blank line between)", p.experience, minHeight = 160) { p = p.copy(experience = it) }
        Field("Education", p.education, minHeight = 100) { p = p.copy(education = it) }

        Button(
            onClick = {
                val german = p.languages.filter { it.language.lowercase() in setOf("german", "deutsch") }
                val others = otherLanguages.fromCsv().mapNotNull { entry ->
                    val parts = entry.split(' ').filter { it.isNotBlank() }
                    if (parts.isEmpty()) return@mapNotNull null
                    val level = LanguageLevel.parse(parts.last())
                    LanguageSkill(if (level != null) parts.dropLast(1).joinToString(" ") else entry, level ?: LanguageLevel.B2)
                }
                vm.saveProfile(
                    p.copy(
                        skills = skills.fromCsv(),
                        targetRoles = roles.fromCsv(),
                        preferredCities = cities.fromCsv(),
                        languages = others + german,
                        minSalary = minSalary.toIntOrNull(),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save profile") }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    minHeight: Int = 0,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = minHeight == 0,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp),
    )
}
