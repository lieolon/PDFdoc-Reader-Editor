package app.pdfdoc.data.sign

import android.content.Context
import app.pdfdoc.data.pdf.PageGeometry
import app.pdfdoc.data.pdf.PdfFonts
import app.pdfdoc.data.pdf.AppearancePainter
import app.pdfdoc.data.signature.SignatureCipher
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import com.tom_roush.pdfbox.util.Matrix
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.json.JSONArray
import org.json.JSONObject

data class CertificateInfo(
    val id: String,
    val name: String,
    val issuer: String,
    val validTo: Long,
    val selfSigned: Boolean,
)

/** Result of checking one signature of a document. */
data class SignatureCheck(
    val signer: String,
    val date: Calendar?,
    val reason: String?,
    /** The signed bytes are unchanged and the signature matches the certificate. */
    val intact: Boolean,
    /** The signature covers the whole file (no changes were appended after signing). */
    val coversDocument: Boolean,
    val selfSigned: Boolean,
    val certificateValidAtSigning: Boolean,
)

class WrongPasswordException : Exception()

private val bc = BouncyCastleProvider()

/**
 * Personal certificates (.p12/.pfx) for digital signatures. Files are stored encrypted with a
 * key from the Android Keystore; the certificate password is asked for at every signing.
 */
@Singleton
class CertificateStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SignatureCipher,
) {
    private val dir get() = File(context.filesDir, "certificates").apply { mkdirs() }
    private val index get() = File(dir, "index.json")

    @Synchronized
    fun list(): List<CertificateInfo> = runCatching {
        val a = JSONArray(index.readText())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            CertificateInfo(o.getString("id"), o.getString("name"), o.getString("issuer"), o.getLong("validTo"), o.getBoolean("self"))
        }
    }.getOrDefault(emptyList())

    /** Adds a PKCS#12 file; throws [WrongPasswordException] when it cannot be opened. */
    fun import(data: ByteArray, password: CharArray): CertificateInfo {
        val (_, chain) = open(data, password)
        return save(data, chain.first() as X509Certificate)
    }

    /** Creates a self-signed certificate valid for five years, protected by [password]. */
    fun create(name: String, email: String, organization: String, password: CharArray): CertificateInfo {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val subject = X500NameBuilder(BCStyle.INSTANCE).apply {
            addRDN(BCStyle.CN, name)
            if (organization.isNotBlank()) addRDN(BCStyle.O, organization)
            if (email.isNotBlank()) addRDN(BCStyle.EmailAddress, email)
        }.build()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger(64, SecureRandom()), Date(now - 60_000), Date(now + FIVE_YEARS), subject, pair.public,
        )
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        val signer = JcaContentSignerBuilder("SHA256WithRSA").setProvider(bc).build(pair.private)
        val cert = JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))
        val store = KeyStore.getInstance("PKCS12", bc).apply { load(null, null) }
        store.setKeyEntry("signature", pair.private, password, arrayOf<Certificate>(cert))
        val bytes = ByteArrayOutputStream().also { store.store(it, password) }.toByteArray()
        return save(bytes, cert)
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.p12.enc").delete()
        writeIndex(list().filterNot { it.id == id })
    }

    fun unlock(id: String, password: CharArray): Pair<PrivateKey, List<Certificate>> {
        val data = cipher.decrypt(File(dir, "$id.p12.enc").readBytes())
        return open(data, password)
    }

    @Synchronized
    private fun save(data: ByteArray, cert: X509Certificate): CertificateInfo {
        val info = CertificateInfo(
            id = UUID.randomUUID().toString(),
            name = commonName(cert.subjectX500Principal.name),
            issuer = commonName(cert.issuerX500Principal.name),
            validTo = cert.notAfter.time,
            selfSigned = cert.subjectX500Principal == cert.issuerX500Principal,
        )
        File(dir, "${info.id}.p12.enc").writeBytes(cipher.encrypt(data))
        writeIndex(list() + info)
        return info
    }

    private fun writeIndex(items: List<CertificateInfo>) {
        index.writeText(JSONArray(items.map {
            JSONObject().put("id", it.id).put("name", it.name).put("issuer", it.issuer).put("validTo", it.validTo).put("self", it.selfSigned)
        }).toString())
    }

    private fun open(data: ByteArray, password: CharArray): Pair<PrivateKey, List<Certificate>> {
        val store = runCatching { KeyStore.getInstance("PKCS12", bc).apply { load(ByteArrayInputStream(data), password) } }
            .getOrElse { throw WrongPasswordException() }
        val alias = store.aliases().toList().firstOrNull { store.isKeyEntry(it) } ?: throw WrongPasswordException()
        val key = runCatching { store.getKey(alias, password) as PrivateKey }.getOrElse { throw WrongPasswordException() }
        val chain = store.getCertificateChain(alias)?.toList().orEmpty()
        if (chain.isEmpty()) throw WrongPasswordException()
        return key to chain
    }

    private companion object {
        const val FIVE_YEARS = 5L * 365 * 24 * 3600 * 1000
    }
}

fun commonName(dn: String): String = runCatching {
    X500Name(dn).getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.let(IETFUtils::valueToString)
}.getOrNull() ?: dn

/** Signs PDFs with a certificate (PAdES-compatible detached CMS) and checks existing signatures. */
class PdfSigner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Writes a signed copy of [source] to [target]. With [visiblePage] >= 0 a signature block
     * with the signer's name and date is drawn at the bottom right of that page first.
     */
    fun sign(
        source: File,
        target: File,
        key: PrivateKey,
        chain: List<Certificate>,
        reason: String,
        location: String,
        visiblePage: Int,
        label: String,
    ) {
        val cert = chain.first() as X509Certificate
        val name = commonName(cert.subjectX500Principal.name)
        var input = source
        val stamped = File(target.parentFile, "${target.name}.stamp")
        if (visiblePage >= 0) {
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                if (visiblePage < doc.numberOfPages) {
                    drawBlock(doc, visiblePage, label, name)
                    doc.save(stamped)
                    input = stamped
                }
            }
        }
        try {
            PDDocument.load(input).use { doc ->
                val signature = PDSignature().apply {
                    setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                    setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                    this.name = name
                    this.reason = reason.ifBlank { null }
                    this.location = location.ifBlank { null }
                    signDate = Calendar.getInstance()
                }
                val options = SignatureOptions().apply { preferredSignatureSize = SignatureOptions.DEFAULT_SIGNATURE_SIZE * 2 }
                doc.addSignature(signature, Cms(key, chain), options)
                target.outputStream().use { doc.saveIncremental(it) }
                options.close()
            }
        } finally {
            stamped.delete()
        }
    }

    private fun drawBlock(doc: PDDocument, pageIndex: Int, label: String, name: String) {
        val page = doc.getPage(pageIndex)
        val g = PageGeometry.of(page)
        val font = PdfFonts.load(doc, context)
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
        val lines = listOf(label, name, date).map { AppearancePainter.safeText(font, it) }
        val size = 8f
        val width = lines.maxOf { font.getStringWidth(it) / 1000f * size } + 16f
        val height = lines.size * size * 1.35f + 10f
        val left = g.displayWidth - width - 28f
        val top = g.displayHeight - height - 28f
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.transform(g.matrix())
            cs.setStrokingColor(0.08f, 0.4f, 0.75f)
            cs.setLineWidth(1f)
            cs.addRect(left, top, width, height)
            cs.stroke()
            cs.setNonStrokingColor(0.08f, 0.4f, 0.75f)
            cs.beginText()
            lines.forEachIndexed { i, line ->
                cs.setFont(font, size)
                cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, left + 8f, top + 5f + size + i * size * 1.35f))
                cs.showText(line)
            }
            cs.endText()
        }
    }

    fun verify(file: File): List<SignatureCheck> {
        val bytes = file.readBytes()
        return PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            doc.signatureDictionaries.map { sig ->
                val check = runCatching {
                    val contents = sig.getContents(bytes)
                    val signed = sig.getSignedContent(bytes)
                    val cms = CMSSignedData(CMSProcessableByteArray(signed), contents)
                    val signer = cms.signerInfos.signers.first()
                    @Suppress("UNCHECKED_CAST")
                    val selector = signer.sid as org.bouncycastle.util.Selector<X509CertificateHolder>
                    val holder = cms.certificates.getMatches(selector).first() as X509CertificateHolder
                    val intact = signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(bc).build(holder))
                    val when_ = sig.signDate?.time ?: Date()
                    Triple(intact, holder, when_)
                }.getOrNull()
                val range = sig.byteRange
                val covers = range != null && range.size == 4 && range[2].toLong() + range[3] == bytes.size.toLong()
                val holder = check?.second
                SignatureCheck(
                    signer = holder?.subject?.toString()?.let(::commonName) ?: sig.name.orEmpty(),
                    date = sig.signDate,
                    reason = sig.reason,
                    intact = check?.first == true,
                    coversDocument = covers,
                    selfSigned = holder != null && holder.subject == holder.issuer,
                    certificateValidAtSigning = holder?.isValidOn(check.third) == true,
                )
            }
        }
    }

    private class Cms(private val key: PrivateKey, private val chain: List<Certificate>) : SignatureInterface {
        override fun sign(content: InputStream): ByteArray {
            val algorithm = if (key.algorithm == "EC" || key.algorithm == "ECDSA") "SHA256withECDSA" else "SHA256withRSA"
            val signer = JcaContentSignerBuilder(algorithm).setProvider(bc).build(key)
            val generator = CMSSignedDataGenerator()
            generator.addSignerInfoGenerator(
                JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().setProvider(bc).build())
                    .build(signer, chain.first() as X509Certificate),
            )
            generator.addCertificates(JcaCertStore(chain))
            return generator.generate(StreamData(content), false).encoded
        }
    }

    private class StreamData(private val input: InputStream) : CMSTypedData {
        override fun getContentType(): ASN1ObjectIdentifier = CMSObjectIdentifiers.data
        override fun getContent(): Any = input
        override fun write(out: OutputStream) {
            input.copyTo(out)
        }
    }
}
