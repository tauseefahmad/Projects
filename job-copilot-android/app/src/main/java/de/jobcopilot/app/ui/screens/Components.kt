package de.jobcopilot.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import de.jobcopilot.app.domain.LanguageLevel
import de.jobcopilot.app.domain.PostingInsights
import de.jobcopilot.app.domain.RemotePolicy
import de.jobcopilot.app.domain.SponsorshipSignal
import de.jobcopilot.app.ui.theme.ScoreGood
import de.jobcopilot.app.ui.theme.ScoreLow
import de.jobcopilot.app.ui.theme.ScoreOk
import de.jobcopilot.app.ui.theme.scoreColor
import java.io.File

@Composable
fun ScoreBadge(score: Int, size: Int = 44) {
    Box(
        Modifier.size(size.dp).background(scoreColor(score), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$score", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size / 2.6).sp)
    }
}

@Composable
fun Flag(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium,
    )
}

/** The four flags internationals ask about first: language, visa, remote, salary. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InsightFlags(i: PostingInsights, userGerman: LanguageLevel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val g = i.germanRequired
        when {
            g == null && i.germanOptional -> Flag("German: plus", ScoreGood)
            g == null -> Flag("No German req.", ScoreGood)
            else -> Flag(
                "German ${g.label}${if (i.germanInferredFromPostingLanguage) "?" else ""}",
                if (userGerman >= g) ScoreGood else if (g.ordinal - userGerman.ordinal == 1) ScoreOk else ScoreLow,
            )
        }
        when (i.sponsorship) {
            SponsorshipSignal.OFFERED -> Flag("Visa/relocation", ScoreGood)
            SponsorshipSignal.NOT_OFFERED -> Flag("No sponsorship", ScoreLow)
            SponsorshipSignal.UNKNOWN -> {}
        }
        when (i.remote) {
            RemotePolicy.REMOTE -> Flag("Remote", ScoreGood)
            RemotePolicy.HYBRID -> Flag("Hybrid", ScoreOk)
            RemotePolicy.ONSITE -> Flag("On-site", Color.Gray)
            RemotePolicy.UNKNOWN -> {}
        }
        i.salary?.let { Flag(it.display(), MaterialTheme.colorScheme.primary) }
    }
}

fun sharePdf(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, file.name))
}

fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
}
