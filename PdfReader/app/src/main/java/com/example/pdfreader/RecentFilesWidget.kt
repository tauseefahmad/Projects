package com.example.pdfreader

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** Home-screen widget: the three most recent documents and an Open button. */
class RecentFilesWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    companion object {
        private val fileViews = intArrayOf(R.id.widgetFile1, R.id.widgetFile2, R.id.widgetFile3)

        /** Refreshes every placed widget, e.g. after a document was opened. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, RecentFilesWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_recent)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SHORTCUT_OPEN)
            views.setOnClickPendingIntent(R.id.widgetOpen, PendingIntent.getActivity(context, 0, open, flags))
            val recent = RecentFiles(context).load().take(fileViews.size)
            views.setViewVisibility(R.id.widgetEmpty, if (recent.isEmpty()) View.VISIBLE else View.GONE)
            fileViews.forEachIndexed { i, viewId ->
                val file = recent.getOrNull(i)
                if (file == null) {
                    views.setViewVisibility(viewId, View.GONE)
                } else {
                    views.setViewVisibility(viewId, View.VISIBLE)
                    views.setTextViewText(viewId, file.name)
                    val intent = Intent(Intent.ACTION_VIEW, file.uri, context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    views.setOnClickPendingIntent(viewId, PendingIntent.getActivity(context, i + 1, intent, flags))
                }
            }
            return views
        }
    }
}
