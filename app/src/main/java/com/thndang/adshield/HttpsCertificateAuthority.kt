package com.thndang.adshield

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object HttpsCertificateAuthority {

    private const val CA_FILE =
        "adshield-personal-ca.p12"
    private const val PREF_CA_PASSWORD =
        "https_ca_password"
    private const val ROOT_ALIAS =
        "adshield-root"
    private const val DAY =
        24L * 60L * 60L * 1000L

    private val secureRandom =
        SecureRandom()

    private val hostContexts =
        ConcurrentHashMap<
            String,
            SSLContext
        >()

    @Volatile
    private var sharedLeafKeyPair:
        KeyPair? = null

    data class CaMaterial(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    fun isReady(
        context: Context
    ): Boolean =
        File(
            context.filesDir,
            CA_FILE
        ).isFile

    @Synchronized
    fun ensureCa(
        context: Context
    ): CaMaterial {
        loadCa(context)
            ?.let {
                return it
            }

        val keyPair =
            generateRsaKeyPair()

        val now =
            System.currentTimeMillis()

        val subject =
            X500Name(
                "CN=AdShield Personal CA," +
                    "O=AdShield,C=VN"
            )

        val builder =
            JcaX509v3CertificateBuilder(
                subject,
                positiveSerial(),
                Date(now - DAY),
                Date(
                    now +
                        3650L * DAY
                ),
                subject,
                keyPair.public
            )

        val extensions =
            JcaX509ExtensionUtils()

        builder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(true)
        )

        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(
                KeyUsage.keyCertSign or
                    KeyUsage.cRLSign
            )
        )

        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            extensions
                .createSubjectKeyIdentifier(
                    keyPair.public
                )
        )

        builder.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            extensions
                .createAuthorityKeyIdentifier(
                    keyPair.public
                )
        )

        val signer =
            JcaContentSignerBuilder(
                "SHA256withRSA"
            )
                .build(
                    keyPair.private
                )

        val certificate =
            JcaX509CertificateConverter()
                .getCertificate(
                    builder.build(
                        signer
                    )
                )

        certificate.verify(
            keyPair.public
        )

        saveCa(
            context,
            keyPair.private,
            certificate
        )

        return CaMaterial(
            keyPair.private,
            certificate
        )
    }

    fun certificateBytes(
        context: Context
    ): ByteArray =
        ensureCa(context)
            .certificate
            .encoded

    fun caFingerprint(
        context: Context
    ): String {
        val digest =
            java.security
                .MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(
                    certificateBytes(
                        context
                    )
                )

        return digest.joinToString(
            ":"
        ) {
            "%02X".format(it)
        }
    }

    fun sslContextForHost(
        context: Context,
        rawHost: String
    ): SSLContext {
        val host =
            rawHost
                .trim()
                .removePrefix("[")
                .removeSuffix("]")
                .lowercase()

        return hostContexts
            .getOrPut(
                host
            ) {
                buildHostSslContext(
                    context,
                    host
                )
            }
    }

    private fun buildHostSslContext(
        context: Context,
        host: String
    ): SSLContext {
        val ca =
            ensureCa(context)

        val leafKey =
            sharedLeafKeyPair
                ?: synchronized(this) {
                    sharedLeafKeyPair
                        ?: generateRsaKeyPair()
                            .also {
                                sharedLeafKeyPair =
                                    it
                            }
                }

        val now =
            System.currentTimeMillis()

        val subject =
            X500Name(
                "CN=" +
                    host.replace(
                        ",",
                        "_"
                    )
            )

        val issuer =
            X500Name(
                ca.certificate
                    .subjectX500Principal
                    .name
            )

        val builder =
            JcaX509v3CertificateBuilder(
                issuer,
                positiveSerial(),
                Date(now - DAY),
                Date(
                    now +
                        14L * DAY
                ),
                subject,
                leafKey.public
            )

        val isIp =
            runCatching {
                InetAddress
                    .getByName(host)
                    .hostAddress == host
            }.getOrDefault(false)

        builder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(false)
        )

        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(
                KeyUsage.digitalSignature or
                    KeyUsage.keyEncipherment
            )
        )

        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(
                KeyPurposeId.id_kp_serverAuth
            )
        )

        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(
                GeneralName(
                    if (isIp) {
                        GeneralName.iPAddress
                    } else {
                        GeneralName.dNSName
                    },
                    host
                )
            )
        )

        val signer =
            JcaContentSignerBuilder(
                "SHA256withRSA"
            )
                .build(
                    ca.privateKey
                )

        val leafCert =
            JcaX509CertificateConverter()
                .getCertificate(
                    builder.build(
                        signer
                    )
                )

        leafCert.verify(
            ca.certificate.publicKey
        )

        val password =
            "adshield-host"
                .toCharArray()

        val keyStore =
            KeyStore.getInstance(
                KeyStore.getDefaultType()
            )

        keyStore.load(
            null,
            null
        )

        keyStore.setKeyEntry(
            "host",
            leafKey.private,
            password,
            arrayOf(
                leafCert,
                ca.certificate
            )
        )

        val kmf =
            KeyManagerFactory
                .getInstance(
                    KeyManagerFactory
                        .getDefaultAlgorithm()
                )

        kmf.init(
            keyStore,
            password
        )

        return SSLContext
            .getInstance("TLS")
            .apply {
                init(
                    kmf.keyManagers,
                    null,
                    secureRandom
                )
            }
    }

    private fun generateRsaKeyPair():
        KeyPair =
        KeyPairGenerator
            .getInstance("RSA")
            .apply {
                initialize(
                    2048,
                    secureRandom
                )
            }
            .generateKeyPair()

    private fun positiveSerial():
        BigInteger =
        BigInteger(
            160,
            secureRandom
        ).abs()
            .add(
                BigInteger.ONE
            )

    private fun password(
        context: Context
    ): CharArray {
        val prefs =
            context
                .getSharedPreferences(
                    MainActivity.PREFS,
                    Context.MODE_PRIVATE
                )

        var value =
            prefs.getString(
                PREF_CA_PASSWORD,
                null
            )

        if (
            value.isNullOrBlank()
        ) {
            value =
                UUID.randomUUID()
                    .toString() +
                    UUID.randomUUID()
                        .toString()

            prefs.edit()
                .putString(
                    PREF_CA_PASSWORD,
                    value
                )
                .apply()
        }

        return value.toCharArray()
    }

    private fun saveCa(
        context: Context,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ) {
        val keyStore =
            KeyStore.getInstance(
                "PKCS12"
            )

        val password =
            password(context)

        keyStore.load(
            null,
            password
        )

        keyStore.setKeyEntry(
            ROOT_ALIAS,
            privateKey,
            password,
            arrayOf(
                certificate
            )
        )

        FileOutputStream(
            File(
                context.filesDir,
                CA_FILE
            )
        ).use {
            keyStore.store(
                it,
                password
            )
        }
    }

    private fun loadCa(
        context: Context
    ): CaMaterial? {
        val file =
            File(
                context.filesDir,
                CA_FILE
            )

        if (!file.isFile) {
            return null
        }

        return runCatching {
            val password =
                password(context)

            val keyStore =
                KeyStore.getInstance(
                    "PKCS12"
                )

            FileInputStream(
                file
            ).use {
                keyStore.load(
                    it,
                    password
                )
            }

            val privateKey =
                keyStore.getKey(
                    ROOT_ALIAS,
                    password
                ) as PrivateKey

            val certificate =
                keyStore
                    .getCertificate(
                        ROOT_ALIAS
                    ) as X509Certificate

            CaMaterial(
                privateKey,
                certificate
            )
        }.getOrNull()
    }
}
