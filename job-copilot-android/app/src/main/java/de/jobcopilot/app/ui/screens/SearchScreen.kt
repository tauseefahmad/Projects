package de.jobcopilot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jobcopilot.app.data.ScoredJob
import de.jobcopilot.app.domain.Profile
import de.jobcopilot.app.ui.MainViewModel

@Composable
fun SearchScreen(vm: MainViewModel, onOpen: (String) -> Unit, onEditProfile: () -> Unit) {
    val state by vm.search.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Find jobs in Germany", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        if (profile.isEmpty) {
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Add your skills and German level so fit scores mean something.", Modifier.weight(1f))
                    TextButton(onClick = onEditProfile) { Text("Profile") }
                }
            }
        }
        OutlinedTextField(
            value = state.what,
            onValueChange = { vm.onQueryChange(what = it) },
            label = { Text("Role or keywords (e.g. Data Engineer)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.where,
                onValueChange = { vm.onQueryChange(where = it) },
                label = { Text("City (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.runSearch() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = vm::runSearch, enabled = !state.loading && state.what.isNotBlank(), modifier = Modifier.padding(top = 6.dp)) {
                Text("Search")
            }
        }

        when {
            state.loading -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Searching Bundesagentur, Greenhouse, Lever, Arbeitnow…", Modifier.padding(top = 12.dp))
            }
            state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
            state.searched && state.results.isEmpty() -> Text("No jobs found. Try broader keywords or no city.", Modifier.padding(top = 16.dp))
            else -> {
                if (state.results.isNotEmpty()) {
                    val note = buildString {
                        append("${state.results.size} jobs, deduplicated, best fit first")
                        if (state.failedSources.isNotEmpty()) append(" · unavailable: ${state.failedSources.joinToString()}")
                    }
                    Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.results, key = { it.job.id }) { JobRow(it, profile) { onOpen(it.job.id) } }
                }
            }
        }
    }
}

@Composable
private fun JobRow(item: ScoredJob, profile: Profile, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            ScoreBadge(item.fit.score)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.job.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${item.job.company} · ${item.job.location}", style = MaterialTheme.typography.bodySmall)
                InsightFlags(item.insights, profile.germanLevel)
                item.fit.reasons.firstOrNull { !it.positive }?.let {
                    Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(item.job.sources.joinToString(" + "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
