package de.jobcopilot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jobcopilot.app.domain.Html
import de.jobcopilot.app.ui.MainViewModel
import de.jobcopilot.app.ui.theme.ScoreGood
import de.jobcopilot.app.ui.theme.ScoreLow
import de.jobcopilot.app.ui.theme.ScoreOk

@Composable
fun JobDetailScreen(vm: MainViewModel, id: String, onBack: () -> Unit, onWriteLetter: () -> Unit) {
    LaunchedEffect(id) { vm.openJob(id) }
    val scored by vm.detail.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Job", style = MaterialTheme.typography.titleLarge)
        }
        val s = scored
        if (s == null || s.job.id != id) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreBadge(s.fit.score, size = 56)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(s.job.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${s.job.company} · ${s.job.location}", style = MaterialTheme.typography.bodyMedium)
                    Text("Found on ${s.job.sources.joinToString(", ")}", style = MaterialTheme.typography.labelSmall)
                }
            }
            InsightFlags(s.insights, profile.germanLevel)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Why this score", fontWeight = FontWeight.SemiBold)
                    s.fit.reasons.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (icon, tint) = when {
                                r.hardBlocker -> Icons.Default.Block to ScoreLow
                                r.positive -> Icons.Default.CheckCircle to ScoreGood
                                else -> Icons.Default.RemoveCircleOutline to ScoreOk
                            }
                            Icon(icon, null, tint = tint)
                            Spacer(Modifier.width(8.dp))
                            Text(r.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (s.insights.germanInferredFromPostingLanguage) {
                        Text(
                            "The posting is written in German without a stated level; B2 is assumed. Check with the employer.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onWriteLetter) { Text("Write Anschreiben") }
                OutlinedButton(onClick = { vm.track(s) }) { Text("Save") }
                OutlinedButton(onClick = { openUrl(context, s.job.url) }) { Text("Open") }
            }
            Text(
                "Apply on the employer's site yourself. Job Copilot never submits applications for you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            HorizontalDivider()
            val text = Html.toPlainText(s.job.description)
            Text(text.ifBlank { "No description available from this source. Open the posting to read it." }, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(16.dp))
        }
    }
}
