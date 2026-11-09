package app.pdfdoc.ui.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import app.pdfdoc.MainActivity
import app.pdfdoc.R
import app.pdfdoc.data.db.DocumentDao
import app.pdfdoc.ui.navigation.Incoming
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Home screen widget: scan, photos to PDF and the three most recent documents (secret ones stay hidden). */
class PdfDocWidget : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun documentDao(): DocumentDao
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val recent = runCatching {
                    EntryPointAccessors.fromApplication(context, Deps::class.java).documentDao().observeAll().first()
                        .filterNot { it.isSecret }.take(3)
                }.getOrDefault(emptyList())
                ids.forEach { id -> manager.updateAppWidget(id, views(context, recent.map { it.name to it.uri })) }
            } finally {
                pending.finish()
            }
        }
    }

    private fun views(context: Context, docs: List<Pair<String, String>>) = RemoteViews(context.packageName, R.layout.widget).apply {
        setOnClickPendingIntent(R.id.widget_scan, action(context, 1, Incoming.ACTION_SCAN, null))
        setOnClickPendingIntent(R.id.widget_images, action(context, 2, Incoming.ACTION_IMAGES, null))
        setViewVisibility(R.id.widget_empty, if (docs.isEmpty()) View.VISIBLE else View.GONE)
        listOf(R.id.widget_doc1, R.id.widget_doc2, R.id.widget_doc3).forEachIndexed { i, view ->
            val doc = docs.getOrNull(i)
            setViewVisibility(view, if (doc == null) View.GONE else View.VISIBLE)
            if (doc != null) {
                setTextViewText(view, doc.first)
                setOnClickPendingIntent(view, action(context, 10 + i, Intent.ACTION_VIEW, Uri.parse(doc.second)))
            }
        }
    }

    private fun action(context: Context, code: Int, action: String, data: Uri?): PendingIntent =
        PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).setAction(action).setData(data)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        /** Redraws placed widgets, e.g. after the recent list changed. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PdfDocWidget::class.java))
            if (ids.isNotEmpty()) {
                context.sendBroadcast(
                    Intent(context, PdfDocWidget::class.java)
                        .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                )
            }
        }
    }
}
