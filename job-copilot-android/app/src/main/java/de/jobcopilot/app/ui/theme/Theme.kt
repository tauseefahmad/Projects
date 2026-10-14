package de.jobcopilot.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF1F4E79),
    secondary = Color(0xFF8A6D00),
    tertiary = Color(0xFF2E7D32),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9CC3EE),
    secondary = Color(0xFFF2C94C),
    tertiary = Color(0xFF81C784),
)

val ScoreGood = Color(0xFF2E7D32)
val ScoreOk = Color(0xFFB7791F)
val ScoreLow = Color(0xFFC62828)

fun scoreColor(score: Int) = when {
    score >= 70 -> ScoreGood
    score >= 45 -> ScoreOk
    else -> ScoreLow
}

@Composable
fun JobCopilotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
