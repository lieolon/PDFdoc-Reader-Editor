package app.pdfdoc.data.sign

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfSignerTest {

    @get:Rule val tmp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun signsAndVerifies() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Test Signer")
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(System.currentTimeMillis() - 60_000), Date(System.currentTimeMillis() + 86_400_000), name, pair.public)
            .build(JcaContentSignerBuilder("SHA256WithRSA").build(pair.private))
        val cert: Certificate = JcaX509CertificateConverter().getCertificate(holder)

        val src = tmp.newFile("in.pdf")
        PDDocument().use { it.addPage(PDPage()); it.save(src) }
        val signed = File(tmp.root, "signed.pdf")
        val signer = PdfSigner(context)
        signer.sign(src, signed, pair.private, listOf(cert), "Approve", "Belgrade", visiblePage = 0, label = "Signed")

        val check = signer.verify(signed).single()
        assertEquals("Test Signer", check.signer)
        assertTrue(check.intact)
        assertTrue(check.coversDocument)
        assertTrue(check.selfSigned)

        // Appending bytes after signing is detected.
        val tampered = File(tmp.root, "tampered.pdf")
        tampered.writeBytes(signed.readBytes() + "\n% extra\n".toByteArray())
        assertFalse(signer.verify(tampered).single().coversDocument)
    }
}
