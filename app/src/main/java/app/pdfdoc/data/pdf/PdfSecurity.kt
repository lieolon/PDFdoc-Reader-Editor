package app.pdfdoc.data.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import javax.inject.Inject

data class PdfPermissions(
    val print: Boolean = true,
    val copy: Boolean = true,
    val modify: Boolean = true,
    val annotate: Boolean = true,
)

enum class LockState {
    /** Not encrypted. */
    OPEN,
    /** Encrypted, and the given password (or none, for owner-only protection) opens it. */
    UNLOCKED,
    /** A user password is required and the given one is missing or wrong. */
    NEEDS_PASSWORD,
}

/** Standard PDF encryption (AES-256) done locally with PDFBox. */
class PdfSecurity @Inject constructor() {

    fun state(file: File, password: String?): LockState = try {
        load(file, password).use { if (it.isEncrypted) LockState.UNLOCKED else LockState.OPEN }
    } catch (e: InvalidPasswordException) {
        LockState.NEEDS_PASSWORD
    }

    /**
     * Encrypts [source] into [target]. An empty [userPassword] means the file opens without a
     * password and only [permissions] are restricted by the owner password.
     */
    fun protect(source: File, target: File, userPassword: String, ownerPassword: String, permissions: PdfPermissions = PdfPermissions()) {
        rewrite(source, target, null) { doc ->
            val access = AccessPermission().apply {
                setCanPrint(permissions.print)
                setCanPrintFaithful(permissions.print)
                setCanExtractContent(permissions.copy)
                setCanExtractForAccessibility(true)
                setCanModify(permissions.modify)
                setCanAssembleDocument(permissions.modify)
                setCanModifyAnnotations(permissions.annotate)
                setCanFillInForm(permissions.annotate || permissions.modify)
            }
            doc.isAllSecurityToBeRemoved = true
            val policy = StandardProtectionPolicy(ownerPassword.ifEmpty { userPassword }, userPassword, access)
            policy.encryptionKeyLength = 256
            doc.protect(policy)
        }
    }

    /** Writes an unencrypted copy; requires the right [password] (null for owner-only files). */
    fun decrypt(source: File, target: File, password: String?) {
        rewrite(source, target, password) { it.isAllSecurityToBeRemoved = true }
    }

    private fun load(file: File, password: String?) =
        PDDocument.load(file, password.orEmpty(), MemoryUsageSetting.setupTempFileOnly())

    private inline fun rewrite(source: File, target: File, password: String?, block: (PDDocument) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        load(source, password).use { doc ->
            block(doc)
            doc.save(tmp)
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
