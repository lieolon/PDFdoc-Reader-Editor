package app.pdfdoc.data.ocr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.pdfdoc.R
import app.pdfdoc.data.files.DocumentEvents
import app.pdfdoc.data.files.DocumentStore
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.files.PasswordRequiredException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Runs OCR for a whole document in the background (foreground service with a progress
 * notification), so it survives leaving the screen.
 */
@HiltWorker
class OcrWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val docs: DocumentStore,
    private val files: FileStore,
    private val documentOcr: DocumentOcr,
    private val engine: OcrEngine,
    private val events: DocumentEvents,
) : CoroutineWorker(context, params) {

    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val uri = Uri.parse(inputData.getString(KEY_URI) ?: return Result.failure())
        val name = inputData.getString(KEY_NAME).orEmpty()
        runCatching { setForeground(foregroundInfo(name, 0, 0)) }
        val work = try {
            docs.openWorkingCopy(uri)
        } catch (e: PasswordRequiredException) {
            return Result.failure(workDataOf(KEY_ERROR to ERROR_LOCKED))
        }
        val out = files.newWorkFile()
        return try {
            val recognized = documentOcr.makeSearchable(work, out) { page, total ->
                setProgressAsync(workDataOf(KEY_PAGE to page, KEY_TOTAL to total))
                notifications.notify(NOTIFICATION_ID, notification(name, page, total))
            }
            if (recognized > 0) {
                docs.save(out, uri)
                events.notifyChanged(uri.toString())
            }
            Result.success(workDataOf(KEY_RECOGNIZED to recognized))
        } catch (e: CancellationException) {
            engine.cancel()
            throw e
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to ERROR_FAILED))
        } finally {
            work.delete()
            out.delete()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(inputData.getString(KEY_NAME).orEmpty(), 0, 0)

    private fun foregroundInfo(name: String, page: Int, total: Int): ForegroundInfo {
        val n = notification(name, page, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    private fun notification(name: String, page: Int, total: Int) =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.viewer_ocr))
            .setContentText(
                if (total > 0) applicationContext.getString(R.string.ocr_progress_named, page, total, name) else name,
            )
            .setProgress(total, page, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        const val CHANNEL = "ocr"
        const val KEY_URI = "uri"
        const val KEY_NAME = "name"
        const val KEY_PAGE = "page"
        const val KEY_TOTAL = "total"
        const val KEY_RECOGNIZED = "recognized"
        const val KEY_ERROR = "error"
        const val ERROR_LOCKED = "locked"
        const val ERROR_FAILED = "failed"
        private const val NOTIFICATION_ID = 1001

        fun uniqueName(uri: Uri) = "ocr:$uri"

        fun start(context: Context, uri: Uri, name: String) {
            val request = OneTimeWorkRequestBuilder<OcrWorker>()
                .setInputData(workDataOf(KEY_URI to uri.toString(), KEY_NAME to name))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(uri), ExistingWorkPolicy.KEEP, request)
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.ocr_channel), NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
