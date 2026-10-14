package de.jobcopilot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jobcopilot.app.domain.LetterLanguage
import de.jobcopilot.app.ui.MainViewModel

@Composable
fun LetterScreen(vm: MainViewModel, jobId: String, onBack: () -> Unit) {
    LaunchedEffect(jobId) { vm.openLetter(jobId) }
    val state by vm.letter.collectAsStateWithLifecycle()
    val scored by vm.detail.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column {
                Text("Anschreiben", style = MaterialTheme.typography.titleLarge)
                scored?.let { Text("${it.job.title} · ${it.job.company}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Language")
                FilterChip(state.language == LetterLanguage.DE, { vm.setLetterLanguage(LetterLanguage.DE) }, label = { Text("Deutsch") })
                FilterChip(state.language == LetterLanguage.EN, { vm.setLetterLanguage(LetterLanguage.EN) }, label = { Text("English") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.generateLetter(useAi = true) }, enabled = !state.generating && scored != null) {
                    Text(if (state.letter == null) "Draft with Claude" else "Redraft with Claude")
                }
                OutlinedButton(onClick = { vm.generateLetter(useAi = false) }, enabled = !state.generating && scored != null) {
                    Text("Offline template")
                }
            }
            if (settings.claudeApiKey.isBlank()) {
                Text(
                    "No Claude API key set: the offline template works without one. Add a key in Settings for tailored drafts.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.generating) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text("Drafting… this can take a little while.")
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            state.letter?.let { letter ->
                Text(
                    "Review every sentence. Remove anything that isn't true for you before sending.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(letter.subject, { v -> vm.editLetter { it.copy(subject = v) } }, label = { Text("Betreff / Subject") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(letter.salutation, { v -> vm.editLetter { it.copy(salutation = v) } }, label = { Text("Anrede / Salutation") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    letter.body, { v -> vm.editLetter { it.copy(body = v) } },
                    label = { Text("Text") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                )
                OutlinedTextField(letter.closing, { v -> vm.editLetter { it.copy(closing = v) } }, label = { Text("Grußformel / Closing") }, modifier = Modifier.fillMaxWidth())
                val words = letter.body.split(Regex("\\s+")).count { it.isNotBlank() }
                Text("$words words · aim for 250–380", style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.exportLetterPdf()?.let { sharePdf(context, it) } }) { Text("Letter PDF") }
                    OutlinedButton(onClick = { vm.exportCvPdf()?.let { sharePdf(context, it) } }) { Text("Tailored CV PDF") }
                }
                TextButton(onClick = vm::saveLetterToTracker) { Text("Save letter with application") }
            }
        }
    }
}
