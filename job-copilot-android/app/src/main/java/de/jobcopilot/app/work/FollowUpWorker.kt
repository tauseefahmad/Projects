package de.jobcopilot.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.jobcopilot.app.JobCopilotApp
import de.jobcopilot.app.MainActivity
import de.jobcopilot.app.data.local.job
import java.util.concurrent.TimeUnit

/** Daily check: notifies about applications whose follow-up date has passed. */
class FollowUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = (applicationContext as JobCopilotApp).container.db.applications()
        val due = dao.dueFollowUps(System.currentTimeMillis())
        if (due.isEmpty()) return Result.success()

        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return Result.success()

        val text = if (due.size == 1) {
            val job = due.first().job
            "Follow up on ${job.title} at ${job.company}"
        } else "${due.size} applications are due for a follow-up"

        val intent = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Application follow-up")
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(1, notification)
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "followups"

        fun schedule(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Follow-up reminders", NotificationManager.IMPORTANCE_DEFAULT))
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "followups",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FollowUpWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
