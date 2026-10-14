# Job Copilot (Android)

An Android app that finds relevant jobs in Germany, explains how well each one fits, and drafts a tailored
Anschreiben and CV that you review before applying yourself. Built for international professionals,
graduates and career changers.

## What the MVP does

| Feature | Where |
|---|---|
| One search across **Bundesagentur für Arbeit**, **Greenhouse**, **Lever** and **Arbeitnow**, deduplicated | `data/sources/JobSources.kt`, `domain/Dedup.kt` |
| **Fit score** 0–100 with reasons ("Missing: Kubernetes", "German C1 required – you have B1") | `domain/FitScorer.kt` |
| Flags for **German level**, **visa sponsorship**, **remote policy**, **salary** | `domain/PostingAnalyzer.kt` |
| **Anschreiben** in German or English drafted with Claude, or an offline template | `ai/ClaudeLetterWriter.kt`, `domain/CoverLetter.kt` |
| PDF export: letter in **DIN 5008 Form B** layout, plus a CV tailored to the posting | `pdf/PdfExporter.kt` |
| **Kanban tracker** (Saved → Applied → Interview → Offer / Rejected), follow-up reminders, interview prep notes | `ui/screens/TrackerScreen.kt`, `work/FollowUpWorker.kt` |
| Encrypted on-device storage, no cloud backup, **"Delete all my data"** | `data/local/Storage.kt` |

How the scoring works: skills 50 pts (required count double vs. nice-to-have), role match 15, German 20,
location/remote 10, visa 5. Hard checks cap the score. A German gap of two or more levels caps it at 35,
and "no visa sponsorship" (when you need it) caps it at 25. A great skills match therefore can't hide a dealbreaker.

## Deliberate limits

- **The app never submits applications.** You open the posting and apply yourself. There's no automation for
  LinkedIn, StepStone or Indeed, which forbid bots.
- The **browser-extension form prefill** from the product plan is not part of an Android app; it belongs in a
  separate Chrome/Firefox extension.
- The BA job search API is documented by [bund.dev](https://jobsuche.api.bund.dev/) but **not officially supported
  for third parties**. It can change without notice, so a failing source is reported in the results and never breaks
  the search.
- Greenhouse and Lever are per-company feeds. Edit the company list in **Settings** (defaults are a few companies with
  German offices). Personio, Softgarden and Adzuna (needs an API key) are the next connectors to add.
- Letters use **your own Claude API key** (stored encrypted on the device). For a shared or B2B rollout, move the
  call behind an EU-hosted backend so keys never ship to devices.

## Build

Requirements: Android Studio (Ladybug or newer) or JDK 17 + Android SDK 35.

```bash
cd job-copilot-android
./gradlew testDebugUnitTest   # analyzer, scorer, dedup, source parsers
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/android.yml` at the repo root) runs the tests and uploads the debug APK as a build artifact.

## Stack

Kotlin, Jetpack Compose (Material 3), Navigation, Room, WorkManager, EncryptedSharedPreferences, OkHttp,
kotlinx.serialization, the Anthropic Java SDK (model `claude-opus-5-5`), and `android.graphics.pdf` for PDFs.
minSdk 26, targetSdk 35.

## Suggested next steps

1. Use it for your own applications, then share the APK with 20–30 internationals and collect feedback on the fit reasons.
2. Add Personio/Softgarden XML feeds and Adzuna.
3. Move ingestion to a backend (Python connectors, LLM field extraction, pgvector matching) and let the app call it.
   This is also where university and bootcamp licences would plug in.
