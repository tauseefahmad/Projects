package de.jobcopilot.app

import android.app.Application
import de.jobcopilot.app.ai.ClaudeLetterWriter
import de.jobcopilot.app.data.JobRepository
import de.jobcopilot.app.data.local.AppDatabase
import de.jobcopilot.app.data.local.SecureStore
import de.jobcopilot.app.data.sources.ArbeitnowSource
import de.jobcopilot.app.data.sources.BundesagenturSource
import de.jobcopilot.app.data.sources.GreenhouseSource
import de.jobcopilot.app.data.sources.JobSource
import de.jobcopilot.app.data.sources.LeverSource
import de.jobcopilot.app.pdf.PdfExporter
import de.jobcopilot.app.work.FollowUpWorker
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class JobCopilotApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        FollowUpWorker.schedule(this)
    }
}

/** Manual dependency wiring; small enough that a DI framework isn't worth it yet. */
class AppContainer(app: Application) {
    val store = SecureStore(app)
    val db = AppDatabase.create(app)
    val pdf = PdfExporter(app)
    val letterWriter = ClaudeLetterWriter()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val bundesagentur = BundesagenturSource(http)

    private fun activeSources(): List<JobSource> = buildList {
        val s = store.settings.value
        add(bundesagentur)
        add(GreenhouseSource(http) { s.greenhouseBoards })
        add(LeverSource(http) { s.leverCompanies })
        if (s.useArbeitnow) add(ArbeitnowSource(http))
    }

    val jobs = JobRepository(::activeSources, bundesagentur, db.applications())
}
