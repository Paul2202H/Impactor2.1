
package com.impactor.android

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/** Streams a SAF-selected IPA into app-private cache and inspects its ZIP structure. */
object IpaInspector {

    data class Result(
        val stagedFile: File,
        val sizeBytes: Long,
        val appBundlePath: String?,
        val infoPlistPath: String?,
        val bundleName: String?,
        val bundleIdentifier: String?,
        val version: String?,
        val minimumOsVersion: String?,
        val plistFormat: String,
        val warnings: List<String>
    )

    fun inspect(
        resolver: ContentResolver,
        uri: Uri,
        cacheDir: File
    ): Result {
        val staged = File.createTempFile("ipa_", ".ipa", cacheDir)

        try {
            val input = resolver.openInputStream(uri)
                ?: error("Cannot open selected document")

            input.use { source ->
                FileOutputStream(staged).use { target ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    val reserveBytes = 32L * 1024L * 1024L

                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break

                        copied += count

                        require(
                            copied <= (cacheDir.usableSpace - reserveBytes).coerceAtLeast(0L)
                        ) {
                            "Not enough free app storage to stage this IPA safely"
                        }

                        target.write(buffer, 0, count)
                    }

                    target.fd.sync()
                }
            }

            require(staged.length() > 0) {
                "Selected IPA is empty"
            }

            ZipFile(staged).use { zip ->

                val infoEntry = zip.entries().asSequence().firstOrNull {
                    it.name.matches(
                        Regex("Payload/[^/]+\\.app/Info\\.plist")
                    )
                } ?: error(
                    "This ZIP does not contain Payload/<App>.app/Info.plist"
                )

                val appPath = infoEntry.name.removeSuffix("Info.plist")

                val bytes = zip.getInputStream(infoEntry).use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0

                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break

                        total += count

                        require(total <= 4 * 1024 * 1024) {
                            "Info.plist exceeds 4 MiB safety limit"
                        }

                        output.write(buffer, 0, count)
                    }

                    output.toByteArray()
                }

                val parsed = parseXmlPlist(bytes)

                val warnings = mutableListOf<String>()

                if (parsed == null) {
                    warnings +=
                        "Info.plist is binary or unsupported XML; metadata could not be parsed by this build."
                }

                return Result(
                    stagedFile = staged,
                    sizeBytes = staged.length(),
                    appBundlePath = appPath,
                    infoPlistPath = infoEntry.name,
                    bundleName = parsed?.get("CFBundleDisplayName")
                        ?: parsed?.get("CFBundleName"),
                    bundleIdentifier = parsed?.get("CFBundleIdentifier"),
                    version = parsed?.get("CFBundleShortVersionString")
                        ?: parsed?.get("CFBundleVersion"),
                    minimumOsVersion = parsed?.get("MinimumOSVersion"),
                    plistFormat = if (parsed == null) "unparsed" else "XML",
                    warnings = warnings
                )
            }

        } catch (t: Throwable) {
            staged.delete()
            throw t
        }
    }

    private fun parseXmlPlist(
        bytes: ByteArray
    ): Map<String, String>? {

        if (bytes.isEmpty() || bytes[0] != '<'.code.toByte()) {
            return null
        }

        return runCatching {
            val parser = Xml.newPullParser()

            // Disable document type declarations.
            parser.setFeature(
                XmlPullParser.FEATURE_PROCESS_DOCDECL,
                false
            )

            // Disable external general entities.
            try {
                parser.setFeature(
                    "http://xml.org/sax/features/external-general-entities",
                    false
                )
            } catch (_: Exception) {
                // Unsupported parser feature.
            }

            // Disable external parameter entities.
            try {
                parser.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities",
                    false
                )
            } catch (_: Exception) {
                // Unsupported parser feature.
            }

            parser.setInput(
                bytes.inputStream(),
                "UTF-8"
            )

            val values = linkedMapOf<String, String>()
            var pendingKey: String? = null

            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {

                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {

                        "key" -> {
                            pendingKey = parser.nextText()
                        }

                        "string", "integer", "real" -> {
                            val value = parser.nextText()

                            pendingKey?.let { key ->
                                values[key] = value
                            }

                            pendingKey = null
                        }
                    }
                }

                event = parser.next()
            }

            values

        }.getOrNull()
    }
}
