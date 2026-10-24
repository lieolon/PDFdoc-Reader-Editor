package app.pdfdoc.data.files

import android.net.Uri
import app.pdfdoc.data.pdf.LockState
import app.pdfdoc.data.pdf.PdfSecurity
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when a document needs a password that is unknown or wrong. */
class PasswordRequiredException(val uri: Uri) : IOException("Password required for $uri")

/**
 * Gives screens a decrypted private working copy of a document and writes results back.
 * Documents opened with a password stay encrypted with it when saved, so editing never
 * silently removes protection. Passwords live in memory only, for the current process.
 */
@Singleton
class DocumentStore @Inject constructor(
    private val files: FileStore,
    private val security: PdfSecurity,
) {
    private val passwords = ConcurrentHashMap<String, String>()

    fun password(uri: Uri): String? = passwords[uri.toString()]

    fun rememberPassword(uri: Uri, password: String) {
        passwords[uri.toString()] = password
    }

    fun forgetPassword(uri: Uri) {
        passwords.remove(uri.toString())
    }

    /** Copies the document into a private file and removes encryption with the known password. */
    fun openWorkingCopy(uri: Uri): File {
        val copy = files.copyToWork(uri)
        return when (security.state(copy, password(uri))) {
            LockState.OPEN -> copy
            LockState.UNLOCKED -> {
                val plain = files.newWorkFile()
                security.decrypt(copy, plain, password(uri))
                copy.delete()
                plain
            }
            LockState.NEEDS_PASSWORD -> {
                copy.delete()
                throw PasswordRequiredException(uri)
            }
        }
    }

    /** Writes [file] to [target], re-encrypting it if [source] was opened with a password. */
    fun save(file: File, target: Uri, source: Uri = target) {
        val password = password(source)
        if (password == null) {
            files.writeTo(target, file)
            return
        }
        val protected = files.newWorkFile()
        try {
            security.protect(file, protected, password, password)
            files.writeTo(target, protected)
            if (target != source) rememberPassword(target, password)
        } finally {
            protected.delete()
        }
    }
}
