package app.pdfdoc.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun sharePdf(context: Context, uri: Uri, title: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, title))
}

fun openPdfWith(context: Context, uri: Uri, title: String) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(view, title))
}

/** Hands the original PDF bytes to the system print framework. */
fun printPdf(context: Context, uri: Uri, jobName: String) {
    val printManager = context.getSystemService(PrintManager::class.java) ?: return
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal.isCanceled) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(jobName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            callback: WriteResultCallback,
        ) {
            try {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
    printManager.print(jobName, adapter, null)
}
