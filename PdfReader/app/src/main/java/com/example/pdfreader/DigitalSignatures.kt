package com.example.pdfreader

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.ess.ESSCertIDv2
import org.bouncycastle.asn1.ess.SigningCertificateV2
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.IssuerSerial
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSAttributeTableGenerator
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.bouncycastle.util.Selector
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

/** A certificate with its private key, from a PKCS#12 (.p12 / .pfx) file. */
class SigningIdentity(val key: PrivateKey, val chain: List<X509Certificate>) {
    val certificate get() = chain.first()
    val name: String get() = commonName(X500Name(certificate.subjectX500Principal.name))
}

/** What a signature in a document says. */
data class SignatureCheck(
    val signer: String,
    val signedAt: Calendar?,
    val reason: String,
    /** The signed bytes are unchanged and the signature matches the certificate. */
    val intact: Boolean,
    /** Nothing was added to the file after this signature. */
    val coversWholeFile: Boolean,
    /** The certificate was issued by the signer themselves (identity not confirmed by anyone). */
    val selfSigned: Boolean,
    val format: String,
)

internal fun commonName(name: X500Name): String =
    name.getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.let { IETFUtils.valueToString(it) } ?: name.toString()

/**
 * Certificate-based digital signatures (PAdES baseline B: CAdES detached CMS with the
 * signing-certificate-v2 attribute), made and checked on the phone.
 */
object DigitalSignatures {

    private fun certDir(context: Context) = File(context.filesDir, "certificates").apply { mkdirs() }

    /** Certificates kept in the app, by display name. */
    fun stored(context: Context): List<File> = certDir(context).listFiles { f -> f.name.endsWith(".p12") }.orEmpty().sortedBy { it.name }

    /** Opens a PKCS#12 keystore; throws for a wrong password or a file without a private key. */
    fun load(file: File, password: CharArray): SigningIdentity {
        val store = KeyStore.getInstance("PKCS12")
        file.inputStream().use { store.load(it, password) }
        for (alias in store.aliases()) {
            if (!store.isKeyEntry(alias)) continue
            val key = store.getKey(alias, password) as? PrivateKey ?: continue
            val chain = store.getCertificateChain(alias)?.filterIsInstance<X509Certificate>().orEmpty()
            if (chain.isNotEmpty()) return SigningIdentity(key, chain)
        }
        throw java.io.IOException("No signing key in this file")
    }

    /** Copies an imported .p12/.pfx into the app after checking that [password] opens it. */
    fun import(context: Context, bytes: ByteArray, password: CharArray): File {
        val temp = File(context.cacheDir, "import.p12")
        temp.writeBytes(bytes)
        try {
            val identity = load(temp, password)
            val target = File(certDir(context), safeName(identity.name) + ".p12")
            temp.copyTo(target, overwrite = true)
            return target
        } finally {
            temp.delete()
        }
    }

    /**
     * Creates a personal (self-signed) certificate for [name] valid for five years. Others can
     * see that the document is unchanged, but not who signed it, unless they trust this certificate.
     */
    fun createPersonal(context: Context, name: String, email: String, password: CharArray): File {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500NameBuilder(BCStyle.INSTANCE).apply {
            addRDN(BCStyle.CN, name)
            if (email.isNotBlank()) addRDN(BCStyle.EmailAddress, email)
        }.build()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger(64, SecureRandom()), Date(now - 60_000), Date(now + 5L * 365 * 24 * 3600 * 1000), subject, keys.public,
        )
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation))
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)))
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("signature", keys.private, password, arrayOf<Certificate>(certificate))
        }
        val file = File(certDir(context), safeName(name) + ".p12")
        file.outputStream().use { store.store(it, password) }
        return file
    }

    private fun safeName(name: String) = name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(60).ifBlank { "certificate" }

    /** Signs [source] and writes the signed file (the original plus an incremental update) to [output]. */
    fun sign(context: Context, source: File, identity: SigningIdentity, reason: String, location: String, output: OutputStream) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(source).use { doc ->
            if (doc.isEncrypted) throw java.io.IOException("Remove the password before signing")
            val signature = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED)
                setName(identity.name)
                if (reason.isNotBlank()) setReason(reason)
                if (location.isNotBlank()) setLocation(location)
                setSignDate(Calendar.getInstance())
            }
            doc.addSignature(signature, SignatureInterface { content -> cms(content.readBytes(), identity) })
            doc.saveIncremental(output)
        }
    }

    /** A detached CMS signature over [data] with PAdES attributes. */
    fun cms(data: ByteArray, identity: SigningIdentity): ByteArray {
        val algorithm = if (identity.key.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
        val signer = JcaContentSignerBuilder(algorithm).build(identity.key)
        val cert = identity.certificate
        // signing-certificate-v2 binds the certificate to the signature (required by PAdES).
        val certHash = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val holder = X509CertificateHolder(cert.encoded)
        val issuerSerial = IssuerSerial(GeneralNames(GeneralName(holder.issuer)), holder.serialNumber)
        val signingCertificate = Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, DERSet(SigningCertificateV2(arrayOf(ESSCertIDv2(certHash, issuerSerial)))))
        val vector = ASN1EncodableVector().apply { add(signingCertificate) }
        val defaults = DefaultSignedAttributeTableGenerator(AttributeTable(vector))
        // PAdES puts the signing time in the signature dictionary, not in the CMS.
        val attributes = CMSAttributeTableGenerator { parameters -> defaults.getAttributes(parameters).remove(CMSAttributes.signingTime) }
        val generator = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(
                JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
                    .setSignedAttributeGenerator(attributes)
                    .build(signer, cert),
            )
            addCertificates(JcaCertStore(identity.chain))
        }
        return generator.generate(CMSProcessableByteArray(data), false).encoded
    }

    /** Checks every signature in [file]. */
    fun verify(context: Context, file: File): List<SignatureCheck> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val bytes = file.readBytes()
        return PDDocument.load(file).use { doc ->
            doc.signatureDictionaries.map { sig ->
                val range = sig.byteRange
                val covers = range != null && range.size == 4 && range[2] + range[3] == bytes.size
                val format = sig.subFilter ?: ""
                try {
                    val cms = CMSSignedData(CMSProcessableByteArray(sig.getSignedContent(bytes)), sig.getContents(bytes))
                    val signer: SignerInformation = cms.signerInfos.signers.first()
                    @Suppress("UNCHECKED_CAST")
                    val certificate = cms.certificates.getMatches(signer.sid as Selector<X509CertificateHolder>).firstOrNull()
                        ?: throw java.io.IOException("No certificate")
                    val intact = signer.verify(JcaSimpleSignerInfoVerifierBuilder().build(certificate))
                    SignatureCheck(
                        signer = commonName(certificate.subject).ifBlank { sig.name.orEmpty() },
                        signedAt = sig.signDate,
                        reason = sig.reason.orEmpty(),
                        intact = intact,
                        coversWholeFile = covers,
                        selfSigned = certificate.subject == certificate.issuer,
                        format = format,
                    )
                } catch (e: Exception) {
                    SignatureCheck(sig.name.orEmpty(), sig.signDate, sig.reason.orEmpty(), intact = false, coversWholeFile = covers, selfSigned = false, format = format)
                }
            }
        }
    }
}
