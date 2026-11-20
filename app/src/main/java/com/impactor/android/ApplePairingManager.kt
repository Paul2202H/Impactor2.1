
package com.impactor.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApplePairingManager(context: Context) {

    private val appContext = context.applicationContext

    private val storageFile =
        File(appContext.filesDir, "apple_pairing_identity.enc")

    private val provider = BouncyCastleProvider()

    data class PairingResult(
        val success: Boolean,
        val message: String,
        val response: String = ""
    )

    private data class Identity(
        val hostId: String,
        val systemBuid: String,
        val rootPrivateKey: ByteArray,
        val hostPrivateKey: ByteArray,
        val rootCertificate: ByteArray,
        val hostCertificate: ByteArray,
        var lastDeviceCertificate: ByteArray? = null,
        var lastPairResponse: String? = null
    )

    fun hasSavedPairing(): Boolean {
        return try {
            val identity = loadIdentity()
            identity?.lastDeviceCertificate != null
        } catch (_: Exception) {
            false
        }
    }

    suspend fun pair(
        devicePublicKeyPem: ByteArray,
        exchange: suspend (ByteArray) -> ByteArray
    ): PairingResult = withContext(Dispatchers.IO) {

        try {
            val devicePublicKey =
                parseDevicePublicKey(devicePublicKeyPem)

            val identity = loadOrCreateIdentity()

            val rootPrivateKey =
                KeyFactory.getInstance("RSA").generatePrivate(
                    PKCS8EncodedKeySpec(identity.rootPrivateKey)
                )

            val rootCertificate =
                parseCertificate(identity.rootCertificate)

            val deviceCertificate = createCertificate(
                subjectName = "CN=Apple Device",
                issuerName = rootCertificate.subjectX500Principal.name,
                subjectPublicKey = devicePublicKey,
                issuerPrivateKey = rootPrivateKey,
                issuerPublicKey = rootCertificate.publicKey,
                isCertificateAuthority = false
            )

            val deviceCertificatePem =
                certificateToPem(deviceCertificate)

            val requestXml = createPairRequest(
                identity,
                deviceCertificatePem
            )

            val responseBytes =
                exchange(createLockdownFrame(requestXml))

            if (isBinaryPlist(responseBytes)) {
                return@withContext PairingResult(
                    false,
                    "The iPad returned a binary plist. " +
                        "Pairing was not processed. A binary plist decoder is required."
                )
            }

            val responseText =
                responseBytes.toString(StandardCharsets.UTF_8)

            val response = parseResponse(responseText)

            if (!response.error.isNullOrBlank()) {
                return@withContext PairingResult(
                    false,
                    "The iPad rejected pairing: ${response.error}",
                    responseText
                )
            }

            if (response.result != null &&
                !response.result.equals("Success", true)
            ) {
                return@withContext PairingResult(
                    false,
                    "Unexpected pairing result: ${response.result}",
                    responseText
                )
            }

            if (!response.hasPairingResponse) {
                return@withContext PairingResult(
                    false,
                    "The response did not contain a recognizable pairing result.",
                    responseText
                )
            }

            identity.lastDeviceCertificate = deviceCertificatePem
            identity.lastPairResponse = responseText

            saveIdentity(identity)

            PairingResult(
                true,
                "Lockdown accepted the pairing request and the local identity was saved.",
                responseText
            )

        } catch (e: Exception) {
            PairingResult(
                false,
                "${e.javaClass.simpleName}: ${e.message ?: "Unknown pairing error"}"
            )
        }
    }

    private data class ParsedResponse(
        val result: String?,
        val error: String?,
        val hasPairingResponse: Boolean
    )

    private fun parseResponse(xml: String): ParsedResponse {
        val result = findPlistString(xml, "Result")
        val error = findPlistString(xml, "Error")

        val hasPairingResponse =
            result != null ||
            error != null ||
            xml.contains("<dict", ignoreCase = true)

        return ParsedResponse(result, error, hasPairingResponse)
    }

    private fun isBinaryPlist(data: ByteArray): Boolean {
        return data.size >= 8 &&
            data.copyOfRange(0, 8).contentEquals(
                "bplist00".toByteArray(StandardCharsets.US_ASCII)
            )
    }

    private fun loadOrCreateIdentity(): Identity {
        loadIdentity()?.let { return it }

        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)

        val rootKeyPair = generator.generateKeyPair()
        val hostKeyPair = generator.generateKeyPair()

        val rootCertificate = createCertificate(
            subjectName = "CN=Impactor Android Root",
            issuerName = null,
            subjectPublicKey = rootKeyPair.public,
            issuerPrivateKey = rootKeyPair.private,
            issuerPublicKey = rootKeyPair.public,
            isCertificateAuthority = true
        )

        val hostCertificate = createCertificate(
            subjectName = "CN=Impactor Android Host",
            issuerName = rootCertificate.subjectX500Principal.name,
            subjectPublicKey = hostKeyPair.public,
            issuerPrivateKey = rootKeyPair.private,
            issuerPublicKey = rootKeyPair.public,
            isCertificateAuthority = false
        )

        val identity = Identity(
            hostId = UUID.randomUUID().toString().uppercase(),
            systemBuid = UUID.randomUUID().toString().uppercase(),
            rootPrivateKey = rootKeyPair.private.encoded,
            hostPrivateKey = hostKeyPair.private.encoded,
            rootCertificate = certificateToPem(rootCertificate),
            hostCertificate = certificateToPem(hostCertificate)
        )

        saveIdentity(identity)
        return identity
    }

    private fun createCertificate(
        subjectName: String,
        issuerName: String?,
        subjectPublicKey: java.security.PublicKey,
        issuerPrivateKey: PrivateKey,
        issuerPublicKey: java.security.PublicKey,
        isCertificateAuthority: Boolean
    ): java.security.cert.X509Certificate {

        val now = System.currentTimeMillis()

        val subject =
            org.bouncycastle.asn1.x500.X500Name(subjectName)

        val issuer =
            org.bouncycastle.asn1.x500.X500Name(
                issuerName ?: subjectName
            )

        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now.coerceAtLeast(1L)),
            Date(now - 60_000L),
            Date(now + 10L * 365L * 24L * 60L * 60L * 1000L),
            subject,
            subjectPublicKey
        )

        val extensionUtils = JcaX509ExtensionUtils()

        builder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(isCertificateAuthority)
        )

        val usage = if (isCertificateAuthority) {
            KeyUsage.keyCertSign or KeyUsage.cRLSign
        } else {
            KeyUsage.digitalSignature or KeyUsage.keyEncipherment
        }

        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(usage)
        )

        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            extensionUtils.createSubjectKeyIdentifier(subjectPublicKey)
        )

        builder.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            extensionUtils.createAuthorityKeyIdentifier(issuerPublicKey)
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(provider)
            .build(issuerPrivateKey)

        return JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(builder.build(signer))
    }

    private fun certificateToPem(
        certificate: java.security.cert.X509Certificate
    ): ByteArray {
        val output = StringWriter()

        PemWriter(output).use { writer ->
            writer.writeObject(PemObject("CERTIFICATE", certificate.encoded))
        }

        return output.toString().toByteArray(StandardCharsets.US_ASCII)
    }

    private fun parseCertificate(
        certificatePem: ByteArray
    ): java.security.cert.X509Certificate {
        val text = certificatePem.toString(StandardCharsets.US_ASCII)

        val encoded = text
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .filterNot { it.isWhitespace() }

        val der = Base64.decode(encoded, Base64.DEFAULT)

        return java.security.cert.CertificateFactory
            .getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der))
            as java.security.cert.X509Certificate
    }

    private fun parseDevicePublicKey(
        pemBytes: ByteArray
    ): RSAPublicKey {

        val pem = pemBytes.toString(StandardCharsets.US_ASCII)

        val isPkcs1 =
            pem.contains("-----BEGIN RSA PUBLIC KEY-----")

        val encoded = pem
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("-----END RSA PUBLIC KEY-----", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filterNot { it.isWhitespace() }

        require(encoded.isNotEmpty()) {
            "Device public key PEM is empty."
        }

        val der = Base64.decode(encoded, Base64.DEFAULT)

        val subjectPublicKeyInfo =
            if (isPkcs1) {
                val rsaKey =
                    org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(
                        ASN1Primitive.fromByteArray(der)
                    )

                SubjectPublicKeyInfo(
                    AlgorithmIdentifier(
                        PKCSObjectIdentifiers.rsaEncryption,
                        DERNull.INSTANCE
                    ),
                    rsaKey
                )
            } else {
                SubjectPublicKeyInfo.getInstance(
                    ASN1Primitive.fromByteArray(der)
                )
            }

        val publicKey = JcaPEMKeyConverter()
            .setProvider(provider)
            .getPublicKey(subjectPublicKeyInfo)

        require(publicKey is RSAPublicKey) {
            "The device public key is not RSA."
        }

        require(publicKey.modulus.bitLength() >= 2048) {
            "The device RSA key is smaller than 2048 bits."
        }

        return publicKey
    }

    private fun createPairRequest(
        identity: Identity,
        deviceCertificate: ByteArray
    ): String {

        fun encode(data: ByteArray) =
            Base64.encodeToString(data, Base64.NO_WRAP)

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
            "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key>
                <string>Impactor Android</string>

                <key>Request</key>
                <string>Pair</string>

                <key>ProtocolVersion</key>
                <string>2</string>

                <key>PairRecord</key>
                <dict>
                    <key>DeviceCertificate</key>
                    <data>${encode(deviceCertificate)}</data>

                    <key>HostCertificate</key>
                    <data>${encode(identity.hostCertificate)}</data>

                    <key>HostID</key>
                    <string>${identity.hostId}</string>

                    <key>RootCertificate</key>
                    <data>${encode(identity.rootCertificate)}</data>

                    <key>SystemBUID</key>
                    <string>${identity.systemBuid}</string>
                </dict>

                <key>PairingOptions</key>
                <dict>
                    <key>ExtendedPairingErrors</key>
                    <true/>
                </dict>
            </dict>
            </plist>
        """.trimIndent()
    }

    private fun createLockdownFrame(xml: String): ByteArray {
        val payload = xml.toByteArray(StandardCharsets.UTF_8)

        require(payload.isNotEmpty() && payload.size <= 1_048_576)

        val frame = ByteArray(payload.size + 4)
        val length = payload.size

        frame[0] = (length ushr 24).toByte()
        frame[1] = (length ushr 16).toByte()
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()

        System.arraycopy(payload, 0, frame, 4, payload.size)
        return frame
    }

    private fun findPlistString(
        xml: String,
        requestedKey: String
    ): String? {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false

        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            if (
                event == XmlPullParser.START_TAG &&
                parser.name == "key"
            ) {
                val key = parser.nextText()

                if (key == requestedKey) {
                    var next = parser.next()

                    while (
                        next == XmlPullParser.TEXT &&
                        parser.isWhitespace
                    ) {
                        next = parser.next()
                    }

                    if (
                        next == XmlPullParser.START_TAG &&
                        parser.name == "string"
                    ) {
                        return parser.nextText()
                    }

                    return null
                }
            }

            event = parser.next()
        }

        return null
    }

    private fun getStorageKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val alias = "impactor_apple_pairing_storage"

        if (keyStore.containsAlias(alias)) {
            return keyStore.getKey(alias, null) as SecretKey
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )

        return generator.generateKey()
    }

    private fun saveIdentity(identity: Identity) {
        val plainBytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(1)
                data.writeUTF(identity.hostId)
                data.writeUTF(identity.systemBuid)
                data.writeBytesField(identity.rootPrivateKey)
                data.writeBytesField(identity.hostPrivateKey)
                data.writeBytesField(identity.rootCertificate)
                data.writeBytesField(identity.hostCertificate)
                data.writeNullableBytes(identity.lastDeviceCertificate)
                data.writeNullableString(identity.lastPairResponse)
            }
            output.toByteArray()
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getStorageKey())

        val encrypted = cipher.doFinal(plainBytes)
        val iv = cipher.iv

        val temporaryFile = File(
            storageFile.parentFile,
            storageFile.name + ".tmp"
        )

        DataOutputStream(temporaryFile.outputStream()).use { output ->
            output.writeInt(iv.size)
            output.write(iv)
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
        }

        if (storageFile.exists() && !storageFile.delete()) {
            temporaryFile.delete()
            throw IllegalStateException("Could not replace pairing identity.")
        }

        if (!temporaryFile.renameTo(storageFile)) {
            temporaryFile.delete()
            throw IllegalStateException("Could not save pairing identity.")
        }
    }

    private fun loadIdentity(): Identity? {
        if (!storageFile.exists()) return null

        val encryptedData = DataInputStream(
            storageFile.inputStream()
        ).use { input ->
            val ivLength = input.readInt()
            require(ivLength in 12..16)

            val iv = ByteArray(ivLength)
            input.readFully(iv)

            val encryptedLength = input.readInt()
            require(encryptedLength in 1..1_000_000)

            val encrypted = ByteArray(encryptedLength)
            input.readFully(encrypted)

            Pair(iv, encrypted)
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getStorageKey(),
            GCMParameterSpec(128, encryptedData.first)
        )

        val plainBytes = cipher.doFinal(encryptedData.second)

        return DataInputStream(
            ByteArrayInputStream(plainBytes)
        ).use { input ->
            require(input.readInt() == 1) {
                "Unsupported pairing identity version."
            }

            Identity(
                hostId = input.readUTF(),
                systemBuid = input.readUTF(),
                rootPrivateKey = input.readBytesField(),
                hostPrivateKey = input.readBytesField(),
                rootCertificate = input.readBytesField(),
                hostCertificate = input.readBytesField(),
                lastDeviceCertificate = input.readNullableBytes(),
                lastPairResponse = input.readNullableString()
            )
        }
    }

    private fun DataOutputStream.writeBytesField(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readBytesField(): ByteArray {
        val length = readInt()
        require(length in 1..1_000_000)
        return ByteArray(length).also { readFully(it) }
    }

    private fun DataOutputStream.writeNullableBytes(value: ByteArray?) {
        if (value == null) {
            writeInt(-1)
        } else {
            writeBytesField(value)
        }
    }

    private fun DataInputStream.readNullableBytes(): ByteArray? {
        val length = readInt()
        if (length == -1) return null

        require(length in 1..1_000_000)
        return ByteArray(length).also { readFully(it) }
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) {
            writeInt(-1)
        } else {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }
    }

    private fun DataInputStream.readNullableString(): String? {
        val length = readInt()
        if (length == -1) return null

        require(length in 0..1_000_000)
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(StandardCharsets.UTF_8)
    }
}
