package de.jobcopilot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jobcopilot.app.data.JobRepository
import de.jobcopilot.app.data.local.ApplicationEntity
import de.jobcopilot.app.data.local.ApplicationStatus
import de.jobcopilot.app.data.local.job
import de.jobcopilot.app.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

@Composable
fun TrackerScreen(vm: MainViewModel, onOpenJob: (String) -> Unit) {
    val apps by vm.applications.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ApplicationEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        Text("Applications", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        if (apps.isEmpty()) {
            Text("Save a job from the search to start tracking it.", Modifier.padding(horizontal = 16.dp))
        }
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(ApplicationStatus.entries) { status ->
                val column = apps.filter { it.status == status }
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.width(280.dp).fillMaxHeight().padding(bottom = 12.dp),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("${status.label} (${column.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(6.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(column, key = { it.id }) { app ->
                                AppCard(app, onClick = { editing = app }, onMove = { vm.move(app, it) })
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { app ->
        EditDialog(
            app = app,
            onDismiss = { editing = null },
            onSave = { vm.updateApplication(it); editing = null },
            onDelete = { vm.deleteApplication(app.id); editing = null },
            onOpenJob = { editing = null; onOpenJob(app.id) },
        )
    }
}

@Composable
private fun AppCard(app: ApplicationEntity, onClick: () -> Unit, onMove: (ApplicationStatus) -> Unit) {
    val job = remember(app.jobJson) { app.job }
    val statuses = ApplicationStatus.entries
    val idx = statuses.indexOf(app.status)
    val overdue = app.followUpAt != null && app.followUpAt <= System.currentTimeMillis() &&
        app.status in setOf(ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEW)

    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreBadge(app.fitScore, size = 32)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(job.company, style = MaterialTheme.typography.bodySmall)
                }
            }
            app.followUpAt?.let {
                Text(
                    "Follow up: ${dateFmt.format(Date(it))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                )
            }
            if (app.letterJson != null) Text("Letter drafted", style = MaterialTheme.typography.labelSmall)
            Row {
                IconButton(onClick = { onMove(statuses[idx - 1]) }, enabled = idx > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Move back")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onMove(statuses[idx + 1]) }, enabled = idx < statuses.lastIndex) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Move forward")
                }
            }
        }
    }
}

private const val PREP_TEMPLATE = """Tell me about yourself (2 min, tailored to this role):

Why this company / why Germany:

Project that shows the key requirement:

A conflict or failure and what I learned:

Questions to ask them (team, onboarding, German expectations, visa timeline):

Salary expectation (annual gross):
"""

@Composable
private fun EditDialog(
    app: ApplicationEntity,
    onDismiss: () -> Unit,
    onSave: (ApplicationEntity) -> Unit,
    onDelete: () -> Unit,
    onOpenJob: () -> Unit,
) {
    var notes by remember { mutableStateOf(app.notes) }
    var prep by remember { mutableStateOf(app.interviewNotes) }
    var followUp by remember { mutableStateOf(app.followUpAt) }
    val job = remember(app.jobJson) { app.job }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(job.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${job.company} · ${app.status.label}", style = MaterialTheme.typography.bodySmall)
                Text("Follow-up: ${followUp?.let { dateFmt.format(Date(it)) } ?: "none"}")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 7, 14).forEach { d ->
                        AssistChip(onClick = { followUp = System.currentTimeMillis() + d * JobRepository.DAY }, label = { Text("+$d d") })
                    }
                    AssistChip(onClick = { followUp = null }, label = { Text("Clear") })
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (contact person, salary talk…)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    prep, { prep = it },
                    label = { Text("Interview preparation") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
                if (prep.isBlank()) TextButton(onClick = { prep = PREP_TEMPLATE }) { Text("Insert prep template") }
                Row {
                    TextButton(onClick = onOpenJob) { Text("Open job") }
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(app.copy(notes = notes, interviewNotes = prep, followUpAt = followUp)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
