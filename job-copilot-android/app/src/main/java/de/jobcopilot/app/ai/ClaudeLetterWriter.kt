package de.jobcopilot.app.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import de.jobcopilot.app.domain.CoverLetter
import de.jobcopilot.app.domain.FitResult
import de.jobcopilot.app.domain.Job
import de.jobcopilot.app.domain.LetterLanguage
import de.jobcopilot.app.domain.LetterPrompt
import de.jobcopilot.app.domain.PostingInsights
import de.jobcopilot.app.domain.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LetterGenerationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Drafts the Anschreiben with Claude. The user always reviews and edits the draft before exporting;
 * nothing is sent to an employer from here.
 *
 * For the MVP the user brings their own API key. A shared/B2B deployment should proxy these calls
 * through an EU-hosted backend instead of shipping keys to devices.
 */
class ClaudeLetterWriter {

    suspend fun write(
        apiKey: String,
        job: Job,
        insights: PostingInsights,
        fit: FitResult,
        profile: Profile,
        language: LetterLanguage,
    ): CoverLetter = withContext(Dispatchers.IO) {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        try {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .system(LetterPrompt.system)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .addUserMessage(LetterPrompt.user(job, insights, fit, profile, language))
                // If a safety classifier declines, let the API retry on a fallback model instead of failing.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()

            val response = client.messages().create(params)
            if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                throw LetterGenerationException("The model declined this request. Try the offline template instead.")
            }
            val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("\n")
            if (text.isBlank()) throw LetterGenerationException("Empty response from the model.")
            val fallbackSubject = if (language == LetterLanguage.DE) "Bewerbung als ${job.title}" else "Application: ${job.title}"
            CoverLetter.parse(text, language, fallbackSubject)
        } catch (e: UnauthorizedException) {
            throw LetterGenerationException("The Claude API key was rejected. Check it in Settings.", e)
        } catch (e: RateLimitException) {
            throw LetterGenerationException("Rate limited by the Claude API. Wait a moment and try again.", e)
        } catch (e: AnthropicServiceException) {
            throw LetterGenerationException("Claude API error (${e.statusCode()}).", e)
        } finally {
            client.close()
        }
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
    }
}
