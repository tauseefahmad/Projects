package de.jobcopilot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jobcopilot.app.ai.ClaudeLetterWriter
import de.jobcopilot.app.ui.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    var key by remember(saved) { mutableStateOf(saved.claudeApiKey) }
    var boards by remember(saved) { mutableStateOf(saved.greenhouseBoards.joinToString(", ")) }
    var lever by remember(saved) { mutableStateOf(saved.leverCompanies.joinToString(", ")) }
    var arbeitnow by remember(saved) { mutableStateOf(saved.useArbeitnow) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("Letter drafting", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            key, { key = it },
            label = { Text("Claude API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Uses ${ClaudeLetterWriter.MODEL}. When you draft a letter, the posting and your profile are sent to the Claude API. " +
                "Without a key, the offline template is used.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text("Job sources", fontWeight = FontWeight.SemiBold)
        Text("Bundesagentur für Arbeit is always searched.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(boards, { boards = it }, label = { Text("Greenhouse board tokens") }, modifier = Modifier.fillMaxWidth())
        Text("The part after boards.greenhouse.io/ in a company's careers URL.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(lever, { lever = it }, label = { Text("Lever company names") }, modifier = Modifier.fillMaxWidth())
        Text("The part after jobs.lever.co/ (or jobs.eu.lever.co/).", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include Arbeitnow", Modifier.weight(1f))
            Switch(arbeitnow, { arbeitnow = it })
        }

        Button(
            onClick = {
                val split = { s: String -> s.split(',', ' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() } }
                vm.saveSettings(saved.copy(claudeApiKey = key, greenhouseBoards = split(boards), leverCompanies = split(lever), useArbeitnow = arbeitnow))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save settings") }

        HorizontalDivider()
        Text("Privacy", fontWeight = FontWeight.SemiBold)
        Text(
            "Your profile, applications and letters stay on this device (encrypted preferences and a local database, excluded from cloud backup). " +
                "Job Copilot never submits applications and never automates LinkedIn, StepStone or Indeed.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { confirmDelete = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Delete all my data") }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete everything?") },
            text = { Text("This removes your profile, API key, tracked applications, letters and exported PDFs from this device. It cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteAllData(); confirmDelete = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
