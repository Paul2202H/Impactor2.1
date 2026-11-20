
package com.impactor.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var pairingManager: ApplePairingManager

    private val usbDevices = mutableStateListOf<UsbDevice>()

    private val status =
        mutableStateOf("Scanning for Apple USB devices...")

    private val transportInfo =
        mutableStateOf("USB transport is closed")

    private val usbmuxInfo =
        mutableStateOf("USBMUX handshake has not been tested.")

    private val lockdownInfo =
        mutableStateOf("Lockdown protocol exchange has not been tested.")

    private val deviceInfo =
        mutableStateOf("No device information retrieved yet.")

    private val ipaInfo =
        mutableStateOf("No IPA selected")

    private val operationStatus =
        mutableStateOf("No installation operation has been run")

    private val diagnosticReport =
        mutableStateOf("")

    private val pairingInfo =
        mutableStateOf("Apple device has not been paired.")

    private val pairingRunningState =
        mutableStateOf(false)

    private var transport: AppleUsbTransport? = null
    private var selectedDevice: UsbDevice? = null
    private var stagedIpa: IpaInspector.Result? = null

    private var usbmux2Ready = false
    private var lockdownTcp: UsbMux2TcpConnection? = null

    private var receiverRegistered = false
    private var usbmuxTestRunning = false
    private var lockdownTestRunning = false

    private val permissionAction =
        "com.impactor.android.USB_PERMISSION"

    // ---------------------------------------------------------
    // CLIPBOARD
    // ---------------------------------------------------------

    private fun copyToClipboard(
        label: String,
        content: String
    ) {
        if (content.isBlank()) {
            Toast.makeText(
                this,
                "Nothing to copy yet.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(label, content)
        )

        Toast.makeText(
            this,
            "$label copied to clipboard",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ---------------------------------------------------------
    // USB BROADCAST RECEIVER
    // ---------------------------------------------------------

    private val usbReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            when (intent.action) {

                permissionAction -> {

                    val device =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE
                            )
                        }

                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )

                    if (granted && device != null) {
                        selectedDevice = device

                        status.value =
                            "USB permission granted. Device is not paired yet."
                    } else {
                        status.value =
                            "USB permission denied or device disconnected."
                    }

                    refreshDevices()
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    status.value = "USB device attached"
                    refreshDevices()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {

                    val detached =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE
                            )
                        }

                    if (
                        detached == null ||
                        detached.deviceId == selectedDevice?.deviceId
                    ) {
                        closeTransport()
                        selectedDevice = null
                    }

                    status.value = "USB device detached"
                    refreshDevices()
                }
            }
        }
    }

    // ---------------------------------------------------------
    // IPA FILE PICKER
    // ---------------------------------------------------------

    private val ipaPicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) inspectIpa(uri)
        }

    // ---------------------------------------------------------
    // ACTIVITY CREATION
    // ---------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager =
            getSystemService(Context.USB_SERVICE) as UsbManager

        pairingManager = ApplePairingManager(this)

        pairingInfo.value =
            if (pairingManager.hasSavedPairing()) {
                "Saved pairing identity found. Device pairing has not yet been reverified."
            } else {
                "No saved pairing identity."
            }

        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        receiverRegistered = true

        refreshDevices()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Dashboard(
                        devices = usbDevices.toList(),
                        status = status.value,
                        transportInfo = transportInfo.value,
                        usbmuxInfo = usbmuxInfo.value,
                        lockdownInfo = lockdownInfo.value,
                        deviceInfo = deviceInfo.value,
                        ipaInfo = ipaInfo.value,
                        operationStatus = operationStatus.value,
                        pairingInfo = pairingInfo.value,
                        pairingRunning = pairingRunningState.value,
                        hasDiagnosticReport =
                            diagnosticReport.value.isNotBlank(),

                        onScan = ::refreshDevices,
                        onRequestPermission = ::requestUsbPermission,
                        onProbe = ::probeUsbTransport,
                        onUsbMuxHandshake = ::testUsbMuxHandshake,
                        onConnectLockdown = ::connectLockdown,
                        onGetDeviceInformation = ::getDeviceInformation,
                        onPairAppleDevice = ::pairAppleDevice,
                        onCloseTransport = ::closeTransport,

                        onCopyDiagnostics = {
                            copyToClipboard(
                                "Impactor USB Diagnostics",
                                diagnosticReport.value
                            )
                        },

                        onCopyIpa = {
                            copyToClipboard(
                                "Impactor IPA Information",
                                ipaInfo.value
                            )
                        },

                        onChooseIpa = {
                            ipaPicker.launch(
                                arrayOf(
                                    "application/octet-stream",
                                    "application/zip",
                                    "*/*"
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------
    // USB DEVICE DISCOVERY
    // ---------------------------------------------------------

    private fun refreshDevices() {

        val found = usbManager.deviceList.values.filter {
            it.vendorId == APPLE_VENDOR_ID
        }

        usbDevices.clear()
        usbDevices.addAll(found)

        if (found.isEmpty()) {

            status.value =
                "No Apple USB device detected. Check your OTG adapter and cable."

            selectedDevice = null
            closeTransport()

        } else {

            if (
                selectedDevice == null ||
                found.none {
                    it.deviceId == selectedDevice?.deviceId
                }
            ) {
                selectedDevice = found.first()
            }

            status.value =
                "${found.size} Apple USB device(s) detected. Detection does not mean paired."
        }
    }

    // ---------------------------------------------------------
    // USB PERMISSION
    // ---------------------------------------------------------

    private fun requestUsbPermission(device: UsbDevice) {

        selectedDevice = device

        if (usbManager.hasPermission(device)) {
            status.value = "USB permission already granted."
            return
        }

        val intent =
            Intent(permissionAction).setPackage(packageName)

        val pending = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_MUTABLE
        )

        usbManager.requestPermission(device, pending)

        status.value = "Waiting for Android USB permission..."
    }

    // ---------------------------------------------------------
    // USBMUX INTERFACE DIAGNOSTICS
    // ---------------------------------------------------------

    private fun probeUsbTransport() {

        val device = selectedDevice ?: run {
            status.value = "No Apple device selected."
            return
        }

        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(device)
            return
        }

        closeTransport()

        diagnosticReport.value = ""
        transportInfo.value = "Running USB diagnostics..."
        status.value = "Inspecting Apple usbmux interface..."

        lifecycleScope.launch {
            try {

                val result = withContext(Dispatchers.IO) {

                    val interfaces =
                        AppleUsbTransport.inspectInterfaces(device)

                    val candidates =
                        AppleUsbTransport.candidates(device)

                    val report = StringBuilder()

                    report.appendLine("USB DEVICE DIAGNOSTICS")
                    report.appendLine("Date: ${java.util.Date()}")

                    report.appendLine(
                        "Product: ${device.productName ?: "Apple device"}"
                    )

                    report.appendLine(
                        "VID: %04X  PID: %04X".format(
                            device.vendorId,
                            device.productId
                        )
                    )

                    report.appendLine(
                        "Configuration count: ${device.configurationCount}"
                    )

                    report.appendLine(
                        "Total interfaces: ${interfaces.size}"
                    )

                    report.appendLine(
                        "Bulk candidates: ${candidates.size}"
                    )

                    report.appendLine()
                    report.appendLine("INTERFACE DESCRIPTORS")

                    interfaces.forEachIndexed { index, info ->

                        report.appendLine("Descriptor entry: $index")
                        report.appendLine(
                            "Configuration: ${info.configurationId}"
                        )
                        report.appendLine(
                            "Interface number: ${info.interfaceNumber}"
                        )
                        report.appendLine(
                            "Class/Subclass/Protocol: %02X/%02X/%02X".format(
                                info.interfaceClass,
                                info.interfaceSubclass,
                                info.interfaceProtocol
                            )
                        )
                        report.appendLine(
                            "Alternate setting: ${info.alternateSetting}"
                        )
                        report.appendLine(
                            "Bulk IN: ${
                                info.bulkInEndpoints.joinToString {
                                    "0x%02X".format(it)
                                }.ifEmpty { "None" }
                            }"
                        )
                        report.appendLine(
                            "Bulk OUT: ${
                                info.bulkOutEndpoints.joinToString {
                                    "0x%02X".format(it)
                                }.ifEmpty { "None" }
                            }"
                        )
                        report.appendLine(
                            "Likely usbmux: ${info.isLikelyUsbmux}"
                        )
                        report.appendLine()
                    }

                    val usbmuxCandidates =
                        candidates.filter {
                            it.isLikelyUsbmux
                        }

                    report.appendLine("APPLE USBMUX TEST")
                    report.appendLine("Expected descriptor: FF/FE/02")
                    report.appendLine(
                        "Matching candidates: ${usbmuxCandidates.size}"
                    )
                    report.appendLine()

                    if (usbmuxCandidates.isEmpty()) {

                        report.appendLine(
                            "RESULT: No Apple usbmux interface with bulk IN and OUT endpoints."
                        )

                        return@withContext Pair(
                            null,
                            report.toString()
                        )
                    }

                    report.appendLine(
                        "Only matching Apple usbmux interfaces will be attempted."
                    )
                    report.appendLine(
                        "Generic interfaces will NOT be used as fallback."
                    )
                    report.appendLine()

                    try {

                        val opened = AppleUsbTransport.openUsbmux(
                            manager = usbManager,
                            device = device
                        )

                        report.appendLine(
                            "USBMUX INTERFACE CLAIM: SUCCESS"
                        )
                        report.appendLine(
                            "Configuration: ${opened.details.configurationId}"
                        )
                        report.appendLine(
                            "Interface: ${opened.details.interfaceNumber}"
                        )
                        report.appendLine(
                            "Alternate setting: ${opened.details.alternateSetting}"
                        )
                        report.appendLine(
                            "Class/Subclass/Protocol: %02X/%02X/%02X".format(
                                opened.details.interfaceClass,
                                opened.details.interfaceSubclass,
                                opened.details.interfaceProtocol
                            )
                        )
                        report.appendLine(
                            "Bulk IN: 0x%02X (packet %d)".format(
                                opened.details.inputEndpoint,
                                opened.details.inputMaxPacket
                            )
                        )
                        report.appendLine(
                            "Bulk OUT: 0x%02X (packet %d)".format(
                                opened.details.outputEndpoint,
                                opened.details.outputMaxPacket
                            )
                        )
                        report.appendLine()
                        report.appendLine("USBMUX INTERFACE CLAIMED.")
                        report.appendLine(
                            "USBMUX version negotiation has not been run."
                        )

                        Pair(opened, report.toString())

                    } catch (e: Exception) {

                        report.appendLine(
                            "USBMUX INTERFACE CLAIM: FAILED"
                        )
                        report.appendLine(
                            e.message ?: e.javaClass.simpleName
                        )
                        report.appendLine()
                        report.appendLine(
                            "No fallback to interface 0 was attempted."
                        )

                        Pair(null, report.toString())
                    }
                }

                transport = result.first
                diagnosticReport.value = result.second
                transportInfo.value = result.second

                status.value =
                    if (result.first != null) {
                        "Apple usbmux USB interface claimed. Protocol session not established."
                    } else {
                        "Apple usbmux interface claim failed. See diagnostic report."
                    }

            } catch (t: Exception) {

                transport = null

                val errorReport =
                    "USB DIAGNOSTIC FAILED\n\n" +
                        "${t.javaClass.simpleName}: " +
                        (t.message ?: "Unknown error")

                diagnosticReport.value = errorReport
                transportInfo.value = errorReport
                status.value = "USB diagnostic failed."
            }
        }
    }

    // ---------------------------------------------------------
    // USBMUX VERSION HANDSHAKE
    // ---------------------------------------------------------

    private fun testUsbMuxHandshake() {

        val activeTransport = transport

        if (activeTransport == null) {
            status.value =
                "Claim the Apple usbmux interface first."
            return
        }

        if (usbmuxTestRunning) {
            status.value =
                "USBMUX handshake is already running."
            return
        }

        if (usbmux2Ready) {
            status.value =
                "USBMUX2 version handshake already completed. Test the TCP connection."
            return
        }

        usbmuxTestRunning = true
        usbmuxInfo.value = "Sending USBMUX version request..."
        status.value = "Testing Apple USBMUX protocol..."

        lifecycleScope.launch {
            try {

                val result = withContext(Dispatchers.IO) {
                    UsbMuxProtocol.performVersionHandshake(
                        transport = activeTransport,
                        timeoutMs = 5000
                    )
                }

                usbmux2Ready =
                    result.success && result.majorVersion == 2

                val report = buildString {

                    appendLine("APPLE USBMUX VERSION TEST")
                    appendLine("Date: ${java.util.Date()}")
                    appendLine("Result: ${result.message}")
                    appendLine("Success: ${result.success}")

                    appendLine(
                        "Version: ${
                            result.majorVersion ?: "Unknown"
                        }.${result.minorVersion ?: "Unknown"}"
                    )

                    appendLine(
                        "USBMUX2 readiness: $usbmux2Ready"
                    )

                    appendLine()
                    appendLine("RAW RESPONSE:")
                    appendLine(
                        result.rawHex.ifBlank {
                            "No response data"
                        }
                    )
                }

                usbmuxInfo.value = report
                diagnosticReport.value += "\n\n$report"

                status.value =
                    if (usbmux2Ready) {
                        "USBMUX2 version negotiation completed. TCP transport is not yet verified."
                    } else if (result.success) {
                        "USBMUX version received, but USBMUX2 was not negotiated."
                    } else {
                        "USBMUX handshake failed. Check the diagnostic report."
                    }

            } catch (e: Exception) {

                usbmux2Ready = false

                val error =
                    "USBMUX TEST ERROR\n" +
                        "${e.javaClass.simpleName}: ${e.message}"

                usbmuxInfo.value = error
                diagnosticReport.value += "\n\n$error"
                status.value = "USBMUX test failed."

            } finally {
                usbmuxTestRunning = false
            }
        }
    }

    // ---------------------------------------------------------
    // LOCKDOWN TCP + QUERYTYPE TEST
    // ---------------------------------------------------------

    private fun connectLockdown() {

        val activeTransport = transport

        if (activeTransport == null) {
            status.value =
                "Claim the Apple usbmux interface first."
            return
        }

        if (!usbmux2Ready) {
            status.value =
                "Run a successful USBMUX2 version handshake first."
            return
        }

        if (lockdownTestRunning) {
            status.value =
                "Lockdown test is already running."
            return
        }

        if (lockdownTcp?.isConnected == true) {
            status.value =
                "A Lockdown TCP connection is already open. Close it before retrying."
            return
        }

        lockdownTestRunning = true
        lockdownInfo.value =
            "Connecting to Lockdown port 62078..."
        status.value =
            "Testing Lockdown TCP and QueryType exchange..."

        lifecycleScope.launch {

            val connection = UsbMux2TcpConnection(
                transport = activeTransport,
                remotePort = 62078
            )

            try {

                val connectResult = withContext(Dispatchers.IO) {
                    connection.connect(timeoutMs = 7000)
                }

                if (!connectResult.success) {

                    val report = buildString {

                        appendLine("LOCKDOWN TCP CONNECTION FAILED")
                        appendLine("Date: ${java.util.Date()}")
                        appendLine(connectResult.message)
                        appendLine("Local port: ${connectResult.localPort}")
                        appendLine("Remote port: ${connectResult.remotePort}")
                        appendLine()
                        appendLine("Raw response:")
                        appendLine(
                            connectResult.rawResponse.ifBlank {
                                "No response data"
                            }
                        )
                    }

                    lockdownInfo.value = report
                    diagnosticReport.value += "\n\n$report"

                    withContext(Dispatchers.IO) {
                        connection.close()
                    }

                    status.value =
                        "Lockdown TCP connection failed."

                    return@launch
                }

                lockdownTcp = connection

                val queryTypePlist = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0">
                    <dict>
                        <key>Label</key>
                        <string>Impactor Android</string>
                        <key>Request</key>
                        <string>QueryType</string>
                    </dict>
                    </plist>
                """.trimIndent()

                val requestFrame =
                    createLockdownFrame(queryTypePlist)

                lockdownInfo.value =
                    "TCP handshake reported success. Sending Lockdown QueryType..."

                val sendResult = withContext(Dispatchers.IO) {
                    connection.sendData(
                        data = requestFrame,
                        timeoutMs = 7000
                    )
                }

                if (!sendResult.success) {

                    val report = buildString {

                        appendLine(
                            "LOCKDOWN TCP DATA TRANSMISSION FAILED"
                        )
                        appendLine("Date: ${java.util.Date()}")
                        appendLine()
                        appendLine(
                            "TCP connection: ${connection.isConnected}"
                        )
                        appendLine(
                            "Remote closed: ${connection.isRemoteClosed}"
                        )
                        appendLine(
                            "Local port: ${connectResult.localPort}"
                        )
                        appendLine(
                            "Remote port: ${connectResult.remotePort}"
                        )
                        appendLine()
                        appendLine("RESULT:")
                        appendLine(sendResult.message)
                        appendLine()
                        appendLine(
                            "Bytes transferred: ${sendResult.bytesTransferred}"
                        )
                        appendLine()
                        appendLine("RAW TCP RESPONSE:")
                        appendLine(
                            sendResult.rawResponse.ifBlank {
                                "No diagnostic packet available."
                            }
                        )
                    }

                    lockdownInfo.value = report
                    diagnosticReport.value += "\n\n$report"

                    runCatching {
                        withContext(Dispatchers.IO) {
                            connection.close()
                        }
                    }

                    lockdownTcp = null

                    status.value =
                        "Lockdown TCP transmission failed. Copy diagnostics for inspection."

                    return@launch
                }

                val responseBytes = withContext(Dispatchers.IO) {
                    receiveLockdownFrame(
                        connection = connection,
                        timeoutMs = 10000
                    )
                }

                val responseText =
                    responseBytes.toString(Charsets.UTF_8)

                val isBinaryPlist =
                    responseBytes.size >= 8 &&
                        responseBytes.copyOfRange(0, 8)
                            .contentEquals("bplist00".toByteArray())

                val hasType = Regex(
                    """<key>\s*Type\s*</key>\s*<string>[^<]+</string>""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(responseText)

                val report = buildString {

                    appendLine("APPLE LOCKDOWN QUERYTYPE TEST")
                    appendLine("Date: ${java.util.Date()}")
                    appendLine("TCP connection: SUCCESS REPORTED")
                    appendLine("QueryType send: SUCCESS REPORTED")
                    appendLine("Response received: YES")
                    appendLine(
                        "Response size: ${responseBytes.size} bytes"
                    )
                    appendLine("Binary plist: $isBinaryPlist")
                    appendLine("Type field detected: $hasType")
                    appendLine()
                    appendLine("RESPONSE:")

                    if (isBinaryPlist) {
                        appendLine(
                            "Binary plist received. XML text decoding is not applicable."
                        )
                        appendLine(
                            responseBytes.joinToString(" ") {
                                "%02X".format(it.toInt() and 0xFF)
                            }
                        )
                    } else {
                        appendLine(responseText)
                    }
                }

                lockdownInfo.value = report
                diagnosticReport.value += "\n\n$report"

                status.value =
                    if (hasType) {
                        "Lockdown QueryType response received. Pairing is still required for protected operations."
                    } else if (isBinaryPlist) {
                        "Lockdown returned a binary plist. Decode it before confirming QueryType."
                    } else {
                        "Lockdown response received. Inspect the response before proceeding."
                    }

            } catch (e: Exception) {

                val error =
                    "LOCKDOWN QUERYTYPE TEST ERROR\n" +
                        "${e.javaClass.simpleName}: " +
                        (e.message ?: "Unknown error")

                lockdownInfo.value = error
                diagnosticReport.value += "\n\n$error"

                status.value =
                    "Lockdown protocol exchange failed."

                if (connection.isConnected) {
                    lockdownTcp = connection
                } else {
                    withContext(Dispatchers.IO) {
                        connection.close()
                    }
                    lockdownTcp = null
                }

            } finally {
                lockdownTestRunning = false
            }
        }
    }

    // ---------------------------------------------------------
    // LOCKDOWN GETVALUE - DEVICE INFORMATION
    // ---------------------------------------------------------

    private fun getDeviceInformation() {

        val connection = lockdownTcp

        if (connection == null || !connection.isConnected) {
            status.value =
                "Run the Lockdown QueryType test first to establish a connection."
            return
        }

        if (lockdownTestRunning) {
            status.value =
                "Another Lockdown operation is running."
            return
        }

        lockdownTestRunning = true
        deviceInfo.value = "Retrieving iPad information..."
        status.value = "Sending Lockdown GetValue requests..."

        lifecycleScope.launch {

            val requestedKeys = listOf(
                "DeviceName",
                "ProductType",
                "ProductVersion",
                "BuildVersion",
                "ModelNumber",
                "DeviceClass",
                "DevicePublicKey"
            )

            val results = linkedMapOf<String, String>()

            try {

                for (key in requestedKeys) {

                    val request = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0">
                        <dict>
                            <key>Label</key>
                            <string>Impactor Android</string>
                            <key>Request</key>
                            <string>GetValue</string>
                            <key>Key</key>
                            <string>$key</string>
                        </dict>
                        </plist>
                    """.trimIndent()

                    val response = withContext(Dispatchers.IO) {

                        val sendResult = connection.sendData(
                            data = createLockdownFrame(request),
                            timeoutMs = 7000
                        )

                        if (!sendResult.success) {
                            throw IllegalStateException(
                                "GetValue send failed for $key: ${sendResult.message}"
                            )
                        }

                        receiveLockdownFrame(
                            connection = connection,
                            timeoutMs = 10000
                        )
                    }

                    val responseText =
                        response.toString(Charsets.UTF_8)

                    results[key] = when {

                        responseText.startsWith("bplist00") -> {
                            "Binary plist response received; XML parser cannot decode it."
                        }

                        extractPlistValue(
                            responseText,
                            "Error"
                        ) != null -> {
                            "Unavailable: ${
                                extractPlistValue(
                                    responseText,
                                    "Error"
                                )
                            }"
                        }

                        key == "DevicePublicKey" -> {

                            val publicKey =
                                extractPlistValue(
                                    responseText,
                                    "Value"
                                )

                            if (
                                publicKey is ByteArray &&
                                publicKey.isNotEmpty()
                            ) {
                                inspectDevicePublicKey(publicKey)
                            } else {
                                "Invalid DevicePublicKey response. Expected binary data."
                            }
                        }

                        else -> extractGetValue(responseText)
                    }
                }

                val report = buildString {

                    appendLine("APPLE LOCKDOWN GETVALUE TEST")
                    appendLine("Date: ${java.util.Date()}")
                    appendLine(
                        "Connection: Existing Lockdown TCP session"
                    )
                    appendLine()
                    appendLine("DEVICE INFORMATION")
                    appendLine()

                    results.forEach { (key, value) ->
                        appendLine("$key:")
                        appendLine(value)
                        appendLine()
                    }
                }

                deviceInfo.value = report
                diagnosticReport.value += "\n\n$report"

                status.value =
                    "Lockdown GetValue requests completed. Check the device information."

            } catch (e: Exception) {

                val error = buildString {

                    appendLine("LOCKDOWN GETVALUE FAILED")
                    appendLine("Date: ${java.util.Date()}")
                    appendLine()
                    appendLine(
                        "${e.javaClass.simpleName}: ${e.message}"
                    )
                    appendLine()
                    appendLine("Values received before failure:")

                    results.forEach { (key, value) ->
                        appendLine("$key: $value")
                    }
                }

                deviceInfo.value = error
                diagnosticReport.value += "\n\n$error"

                status.value =
                    "GetValue failed. Copy diagnostics for inspection."

                if (!connection.isConnected) {

                    runCatching {
                        withContext(Dispatchers.IO) {
                            connection.close()
                        }
                    }

                    lockdownTcp = null
                }

            } finally {
                lockdownTestRunning = false
            }
        }
    }

    // ---------------------------------------------------------
    // APPLE LOCKDOWN PAIRING
    // ---------------------------------------------------------

    private fun pairAppleDevice() {

        val connection = lockdownTcp

        if (connection == null || !connection.isConnected) {
            status.value =
                "Connect to Lockdown before pairing."
            return
        }

        if (lockdownTestRunning || pairingRunningState.value) {
            status.value =
                "Another Lockdown operation is running."
            return
        }

        pairingRunningState.value = true
        lockdownTestRunning = true

        pairingInfo.value =
            "Preparing Apple pairing request..."

        status.value =
            "Starting Lockdown pairing..."

        lifecycleScope.launch {

            try {

                // Retrieve the iPad's RSA public key.

                val publicKey = getDevicePublicKey(connection)

                pairingInfo.value =
                    "Device public key retrieved. Sending pairing request..."

                // Send the Pair request through the existing
                // USBMUX2 TCP Lockdown connection.

                val result = pairingManager.pair(
                    devicePublicKeyPem = publicKey
                ) { framedRequest ->

                    val sendResult = connection.sendData(
                        data = framedRequest,
                        timeoutMs = 10000
                    )

                    if (!sendResult.success) {

                        // Try to retrieve data that may already
                        // have been buffered by the USBMUX2 transport.

                        val bufferedResult = runCatching {
                            connection.receiveData(
                                maxBytes = 16384,
                                timeoutMs = 1000
                            )
                        }

                        val bufferedData =
                            bufferedResult.getOrNull()?.data
                                ?: ByteArray(0)

                        val bufferedHex =
                            bufferedData.joinToString(" ") {
                                "%02X".format(
                                    it.toInt() and 0xFF
                                )
                            }

                        val bufferedAscii =
                            bufferedData.map { byte ->
                                val value =
                                    byte.toInt() and 0xFF

                                if (value in 32..126) {
                                    value.toChar()
                                } else {
                                    '.'
                                }
                            }.joinToString("")

                        val pendingDiagnostics =
                            runCatching {
                                connection.getPendingDataDiagnostics()
                            }.getOrElse {
                                "Unable to retrieve pending-data diagnostics: ${it.message}"
                            }

                        val diagnostic = buildString {

                            appendLine(
                                "Pair request acknowledgement failed."
                            )

                            appendLine()
                            appendLine("SEND RESULT:")
                            appendLine(sendResult.message)

                            appendLine()
                            appendLine("TRANSFER INFORMATION:")

                            appendLine(
                                "Bytes transferred: ${sendResult.bytesTransferred}"
                            )

                            appendLine(
                                "Connection connected: ${connection.isConnected}"
                            )

                            appendLine(
                                "Remote closed: ${connection.isRemoteClosed}"
                            )

                            appendLine()
                            appendLine(
                                "BUFFERED RESPONSE SIZE: ${bufferedData.size} bytes"
                            )

                            appendLine()
                            appendLine("BUFFERED RESPONSE HEX:")

                            appendLine(
                                bufferedHex.ifBlank {
                                    "No data returned by receiveData()."
                                }
                            )

                            appendLine()
                            appendLine("BUFFERED RESPONSE TEXT:")

                            appendLine(
                                bufferedAscii.ifBlank {
                                    "No readable data."
                                }
                            )

                            appendLine()
                            appendLine("TRANSPORT DIAGNOSTICS:")
                            appendLine(pendingDiagnostics)
                        }

                        throw IllegalStateException(diagnostic)
                    }

                    // The complete request was acknowledged.
                    // Now wait for the Lockdown response.

                    receiveLockdownFrame(
                        connection = connection,
                        timeoutMs = 20000
                    )
                }

                pairingInfo.value = result.message

                val saved =
                    pairingManager.hasSavedPairing()

                status.value =
                    if (result.success && saved) {
                        "Lockdown pairing response accepted and local identity saved."
                    } else {
                        "Pairing not confirmed. Check the pairing response."
                    }

                val report = buildString {

                    appendLine("APPLE PAIRING RESULT")
                    appendLine("Date: ${java.util.Date()}")
                    appendLine()
                    appendLine("Success: ${result.success}")
                    appendLine("Saved identity: $saved")
                    appendLine()
                    appendLine("Message:")
                    appendLine(result.message)
                    appendLine()
                    appendLine("Lockdown response:")
                    appendLine(result.response)
                }

                diagnosticReport.value += "\n\n$report"

            } catch (e: Exception) {

                val error =
                    "Pairing failed: " +
                        "${e.message ?: e.javaClass.simpleName}"

                pairingInfo.value = error
                status.value = "Apple pairing failed."

                diagnosticReport.value +=
                    "\n\nAPPLE PAIRING ERROR\n$error"

            } finally {

                pairingRunningState.value = false
                lockdownTestRunning = false
            }
        }
    }

    // ---------------------------------------------------------
    // LOCKDOWN XML PLIST PARSER
    // ---------------------------------------------------------

    private fun extractGetValue(xml: String): String {

        return try {

            val error =
                extractPlistValue(xml, "Error")

            if (error != null) {
                return "Unavailable: $error"
            }

            val value =
                extractPlistValue(xml, "Value")

            when (value) {

                null -> "No Value field returned."

                is String ->
                    value.ifBlank { "Empty value" }

                is ByteArray ->
                    "Binary data returned: ${value.size} bytes."

                is Boolean ->
                    value.toString()

                is Number ->
                    value.toString()

                is Map<*, *> ->
                    "Dictionary returned with ${value.size} entries."

                is List<*> ->
                    "Array returned with ${value.size} items."

                else ->
                    "Unsupported plist value: ${value.javaClass.simpleName}"
            }

        } catch (e: Exception) {

            "Plist parsing failed: ${
                e.message ?: e.javaClass.simpleName
            }"
        }
    }

    private fun extractPlistValue(
        xml: String,
        requestedKey: String
    ): Any? {

        val trimmed = xml.trimStart()

        require(
            trimmed.startsWith("<?xml") ||
                trimmed.startsWith("<plist")
        ) {
            "Response is not an XML plist."
        }

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

                    var nextEvent = parser.next()

                    while (
                        nextEvent == XmlPullParser.TEXT &&
                        parser.isWhitespace
                    ) {
                        nextEvent = parser.next()
                    }

                    if (nextEvent != XmlPullParser.START_TAG) {
                        return null
                    }

                    return parsePlistElement(parser)
                }
            }

            event = parser.next()
        }

        return null
    }

    private fun parsePlistElement(
        parser: XmlPullParser
    ): Any? {

        return when (parser.name) {

            "string" ->
                parser.nextText()

            "integer" ->
                parser.nextText().trim().toLongOrNull()
                    ?: throw IllegalArgumentException(
                        "Invalid plist integer."
                    )

            "real" ->
                parser.nextText().trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException(
                        "Invalid plist real number."
                    )

            "data" -> {

                val encoded = parser.nextText()
                    .filterNot { it.isWhitespace() }

                if (encoded.isEmpty()) {
                    ByteArray(0)
                } else {
                    Base64.decode(encoded, Base64.DEFAULT)
                }
            }

            "true" -> true

            "false" -> false

            "date" ->
                parser.nextText()

            "array" ->
                parsePlistArray(parser)

            "dict" ->
                parsePlistDictionary(parser)

            else ->
                throw IllegalArgumentException(
                    "Unsupported plist element: ${parser.name}"
                )
        }
    }

    private fun parsePlistArray(
        parser: XmlPullParser
    ): List<Any?> {

        val values = mutableListOf<Any?>()

        while (true) {

            when (parser.next()) {

                XmlPullParser.START_TAG -> {
                    values.add(parsePlistElement(parser))
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "array") {
                        return values
                    }
                }

                XmlPullParser.END_DOCUMENT -> {
                    throw IllegalStateException(
                        "Unexpected end of plist array."
                    )
                }
            }
        }
    }

    private fun parsePlistDictionary(
        parser: XmlPullParser
    ): Map<String, Any?> {

        val values = linkedMapOf<String, Any?>()

        while (true) {

            when (parser.next()) {

                XmlPullParser.START_TAG -> {

                    if (parser.name != "key") {
                        throw IllegalArgumentException(
                            "Expected plist dictionary key."
                        )
                    }

                    val key = parser.nextText()

                    var nextEvent = parser.next()

                    while (
                        nextEvent == XmlPullParser.TEXT &&
                        parser.isWhitespace
                    ) {
                        nextEvent = parser.next()
                    }

                    if (nextEvent != XmlPullParser.START_TAG) {
                        throw IllegalArgumentException(
                            "Missing value for plist key: $key"
                        )
                    }

                    values[key] =
                        parsePlistElement(parser)
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "dict") {
                        return values
                    }
                }

                XmlPullParser.END_DOCUMENT -> {
                    throw IllegalStateException(
                        "Unexpected end of plist dictionary."
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------
    // DEVICE PUBLIC KEY RETRIEVAL
    // ---------------------------------------------------------

    private suspend fun getDevicePublicKey(
        connection: UsbMux2TcpConnection
    ): ByteArray {

        val request = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
            "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key>
                <string>Impactor Android</string>
                <key>Request</key>
                <string>GetValue</string>
                <key>Key</key>
                <string>DevicePublicKey</string>
            </dict>
            </plist>
        """.trimIndent()

        val response = withContext(Dispatchers.IO) {

            val result = connection.sendData(
                data = createLockdownFrame(request),
                timeoutMs = 7000
            )

            if (!result.success) {
                throw IllegalStateException(
                    "DevicePublicKey request failed: ${result.message}"
                )
            }

            receiveLockdownFrame(
                connection = connection,
                timeoutMs = 10000
            )
        }

        val xml = response.toString(Charsets.UTF_8)

        if (xml.startsWith("bplist00")) {
            throw IllegalStateException(
                "DevicePublicKey response is a binary plist. A binary plist decoder is required."
            )
        }

        val publicKey =
            extractPlistValue(xml, "Value")

        if (
            publicKey !is ByteArray ||
            publicKey.isEmpty()
        ) {

            val error =
                extractPlistValue(xml, "Error")

            throw IllegalStateException(
                "DevicePublicKey was not returned as binary data. " +
                    "Response: ${error ?: "Unexpected plist value"}"
            )
        }

        return publicKey
    }

    // ---------------------------------------------------------
    // RSA PUBLIC KEY VALIDATION
    // ---------------------------------------------------------

    private fun inspectDevicePublicKey(
        data: ByteArray
    ): String {

        return try {

            val pem = data.toString(Charsets.US_ASCII).trim()

            val header =
                "-----BEGIN RSA PUBLIC KEY-----"

            val footer =
                "-----END RSA PUBLIC KEY-----"

            require(pem.startsWith(header)) {
                "Unexpected PEM header. Expected BEGIN RSA PUBLIC KEY."
            }

            require(pem.endsWith(footer)) {
                "RSA public key PEM footer is missing."
            }

            val base64Content = pem
                .substringAfter(header)
                .substringBefore(footer)
                .filterNot { it.isWhitespace() }

            require(base64Content.isNotEmpty()) {
                "RSA public key contains no Base64 data."
            }

            val der =
                Base64.decode(base64Content, Base64.DEFAULT)

            var offset = 0

            fun readLength(): Int {

                require(offset < der.size) {
                    "Unexpected end of DER data."
                }

                val first =
                    der[offset++].toInt() and 0xFF

                if (first < 0x80) {
                    return first
                }

                val count = first and 0x7F

                require(
                    count in 1..4 &&
                        offset + count <= der.size
                ) {
                    "Invalid DER length."
                }

                var length = 0L

                repeat(count) {
                    length =
                        (length shl 8) or
                            (der[offset++].toLong() and 0xFFL)
                }

                require(length <= Int.MAX_VALUE) {
                    "DER element is too large."
                }

                return length.toInt()
            }

            fun readInteger(): BigInteger {

                require(
                    offset < der.size &&
                        (der[offset++].toInt() and 0xFF) == 0x02
                ) {
                    "Expected DER INTEGER."
                }

                val length = readLength()

                require(
                    length > 0 &&
                        length <= der.size - offset
                ) {
                    "Invalid RSA integer length."
                }

                val value = BigInteger(
                    1,
                    der.copyOfRange(
                        offset,
                        offset + length
                    )
                )

                offset += length

                return value
            }

            require(
                offset < der.size &&
                    (der[offset++].toInt() and 0xFF) == 0x30
            ) {
                "Expected DER SEQUENCE."
            }

            val sequenceLength = readLength()

            require(sequenceLength == der.size - offset) {
                "Invalid DER sequence length."
            }

            val modulus = readInteger()
            val exponent = readInteger()

            require(offset == der.size) {
                "Unexpected trailing DER data."
            }

            require(modulus.signum() > 0) {
                "Invalid RSA modulus."
            }

            require(exponent.signum() > 0) {
                "Invalid RSA public exponent."
            }

            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(
                    RSAPublicKeySpec(modulus, exponent)
                ) as RSAPublicKey

            val fingerprint = MessageDigest
                .getInstance("SHA-256")
                .digest(der)
                .joinToString(":") {
                    "%02X".format(it)
                }

            buildString {

                appendLine("PUBLIC KEY VALIDATION")
                appendLine()
                appendLine("Received PEM bytes: ${data.size}")
                appendLine("Decoded DER bytes: ${der.size}")
                appendLine("PEM format: RSA PUBLIC KEY")
                appendLine("DER format: PKCS#1")
                appendLine("Algorithm: ${publicKey.algorithm}")
                appendLine("Format: ${publicKey.format}")
                appendLine(
                    "RSA key size: ${publicKey.modulus.bitLength()} bits"
                )
                appendLine(
                    "Public exponent: ${publicKey.publicExponent}"
                )
                appendLine()
                appendLine("SHA-256 fingerprint:")
                appendLine(fingerprint)
                appendLine()
                appendLine("Key validation: SUCCESS")
                appendLine(
                    "This validates key encoding, not device identity."
                )
            }.trim()

        } catch (e: Exception) {

            val prefix = data.take(24).joinToString(" ") {
                "%02X".format(it)
            }

            buildString {

                appendLine("PUBLIC KEY VALIDATION FAILED")
                appendLine("Received bytes: ${data.size}")
                appendLine(
                    "Expected encoding: PKCS#1 RSA PUBLIC KEY"
                )
                appendLine()
                appendLine("First 24 bytes:")
                appendLine(prefix)
                appendLine()
                appendLine(
                    "Error: ${e.message ?: e.javaClass.simpleName}"
                )
            }.trim()
        }
    }

    // ---------------------------------------------------------
    // LOCKDOWN MESSAGE FRAMING
    // ---------------------------------------------------------

    private fun createLockdownFrame(
        xml: String
    ): ByteArray {

        val payload = xml.toByteArray(Charsets.UTF_8)

        require(payload.size in 1..1_048_576) {
            "Invalid Lockdown request size."
        }

        val length = payload.size
        val frame = ByteArray(4 + length)

        frame[0] = (length ushr 24).toByte()
        frame[1] = (length ushr 16).toByte()
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()

        System.arraycopy(
            payload,
            0,
            frame,
            4,
            payload.size
        )

        return frame
    }

    // ---------------------------------------------------------
    // LOCKDOWN RESPONSE READER
    // ---------------------------------------------------------

    private fun receiveLockdownFrame(
        connection: UsbMux2TcpConnection,
        timeoutMs: Int = 10000
    ): ByteArray {

        val deadline =
            SystemClock.elapsedRealtime() + timeoutMs

        val received = ByteArrayOutputStream()

        var expectedLength = -1

        while (
            SystemClock.elapsedRealtime() < deadline
        ) {

            val result = connection.receiveData(
                maxBytes = 4096,
                timeoutMs = 1000
            )

            if (!result.success) {
                throw IllegalStateException(result.message)
            }

            if (result.data.isEmpty()) {

                if (connection.isRemoteClosed) {
                    throw IllegalStateException(
                        "The iPad closed the Lockdown connection before replying."
                    )
                }

                continue
            }

            received.write(result.data)

            if (received.size() > 1_048_580) {
                throw IllegalStateException(
                    "Lockdown response is too large."
                )
            }

            val bytes = received.toByteArray()

            if (
                bytes.size >= 4 &&
                expectedLength == -1
            ) {

                val parsedLength =
                    ((bytes[0].toLong() and 0xFFL) shl 24) or
                        ((bytes[1].toLong() and 0xFFL) shl 16) or
                        ((bytes[2].toLong() and 0xFFL) shl 8) or
                        (bytes[3].toLong() and 0xFFL)

                if (parsedLength !in 1L..1_048_576L) {
                    throw IllegalStateException(
                        "Invalid Lockdown response length: $parsedLength"
                    )
                }

                expectedLength = parsedLength.toInt()
            }

            if (
                expectedLength > 0 &&
                bytes.size >= 4 + expectedLength
            ) {

                return bytes.copyOfRange(
                    4,
                    4 + expectedLength
                )
            }
        }

        throw IllegalStateException(
            "Timed out waiting for a complete Lockdown response. " +
                "Received ${received.size()} bytes."
        )
    }

    // ---------------------------------------------------------
    // CLOSE USB CONNECTION
    // ---------------------------------------------------------

    private fun closeTransport() {

        runCatching {
            lockdownTcp?.close()
        }

        lockdownTcp = null

        usbmux2Ready = false
        usbmuxTestRunning = false
        lockdownTestRunning = false

        pairingRunningState.value = false

        runCatching {
            transport?.close()
        }

        transport = null

        transportInfo.value =
            "USB transport is closed"

        usbmuxInfo.value =
            "USBMUX handshake has not been tested."

        lockdownInfo.value =
            "Lockdown protocol exchange has not been tested."

        deviceInfo.value =
            "No device information retrieved yet."

        pairingInfo.value =
            if (pairingManager.hasSavedPairing()) {
                "Saved pairing identity exists. Reconnect to verify the device pairing."
            } else {
                "Apple device has not been paired."
            }
    }

    // ---------------------------------------------------------
    // IPA INSPECTION
    // ---------------------------------------------------------

    private fun inspectIpa(uri: Uri) {

        operationStatus.value =
            "Copying and inspecting IPA..."

        lifecycleScope.launch {

            try {

                val result = withContext(Dispatchers.IO) {

                    IpaInspector.inspect(
                        contentResolver,
                        uri,
                        cacheDir
                    )
                }

                stagedIpa?.stagedFile?.delete()
                stagedIpa = result

                ipaInfo.value = buildString {

                    appendLine(
                        "File size: ${result.sizeBytes / (1024 * 1024)} MiB"
                    )
                    appendLine(
                        "App bundle: ${result.appBundlePath ?: "Unknown"}"
                    )
                    appendLine(
                        "Display name: ${result.bundleName ?: "Not parsed"}"
                    )
                    appendLine(
                        "Bundle ID: ${result.bundleIdentifier ?: "Not parsed"}"
                    )
                    appendLine(
                        "Version: ${result.version ?: "Not parsed"}"
                    )
                    appendLine(
                        "Minimum iOS: ${result.minimumOsVersion ?: "Not parsed"}"
                    )
                    appendLine(
                        "Info.plist format: ${result.plistFormat}"
                    )

                    result.warnings.forEach {
                        appendLine("Warning: $it")
                    }
                }.trim()

                operationStatus.value =
                    "IPA archive validated. Signing and installation are not available yet."

            } catch (t: Exception) {

                operationStatus.value =
                    "IPA inspection failed: ${
                        t.message ?: t.javaClass.simpleName
                    }"

                ipaInfo.value = "No valid IPA loaded"
            }
        }
    }

    // ---------------------------------------------------------
    // ACTIVITY DESTRUCTION
    // ---------------------------------------------------------

    override fun onDestroy() {

        closeTransport()

        stagedIpa?.stagedFile?.delete()
        stagedIpa = null

        if (receiverRegistered) {

            runCatching {
                unregisterReceiver(usbReceiver)
            }

            receiverRegistered = false
        }

        super.onDestroy()
    }

    companion object {
        private const val APPLE_VENDOR_ID = 0x05AC
    }
}

// =============================================================
// DASHBOARD UI
// =============================================================

@androidx.compose.runtime.Composable
private fun Dashboard(
    devices: List<UsbDevice>,
    status: String,
    transportInfo: String,
    usbmuxInfo: String,
    lockdownInfo: String,
    deviceInfo: String,
    ipaInfo: String,
    operationStatus: String,
    pairingInfo: String,
    pairingRunning: Boolean,
    hasDiagnosticReport: Boolean,

    onScan: () -> Unit,
    onRequestPermission: (UsbDevice) -> Unit,
    onProbe: () -> Unit,
    onUsbMuxHandshake: () -> Unit,
    onConnectLockdown: () -> Unit,
    onGetDeviceInformation: () -> Unit,
    onPairAppleDevice: () -> Unit,
    onCloseTransport: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onCopyIpa: () -> Unit,
    onChooseIpa: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),

        verticalArrangement =
            Arrangement.spacedBy(14.dp)
    ) {

        Text(
            "IMPACTOR ANDROID",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            "Native Android port • Development build",
            style = MaterialTheme.typography.bodyMedium
        )

        // -----------------------------------------------------
        // USB CONNECTION
        // -----------------------------------------------------

        SectionCard("USB CONNECTION") {

            Text(status)

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {

                Button(onClick = onScan) {
                    Text("Scan USB")
                }

                OutlinedButton(onClick = onProbe) {
                    Text("Probe interface")
                }

                OutlinedButton(onClick = onCloseTransport) {
                    Text("Close")
                }
            }

            Text(
                transportInfo,
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedButton(
                onClick = onCopyDiagnostics,
                enabled = hasDiagnosticReport,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Copy USB Diagnostic Results")
            }

            Button(
                onClick = onUsbMuxHandshake,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test USBMUX Handshake")
            }

            Text(
                usbmuxInfo,
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = onConnectLockdown,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test Lockdown QueryType")
            }

            Text(
                lockdownInfo,
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = onGetDeviceInformation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Get Device Information")
            }

            Text(
                deviceInfo,
                style = MaterialTheme.typography.bodySmall
            )

            // ---------------------------------------------
            // APPLE PAIRING
            // ---------------------------------------------

            Text(
                "APPLE DEVICE PAIRING",
                style = MaterialTheme.typography.titleMedium
            )

            Button(
                onClick = onPairAppleDevice,
                enabled = !pairingRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (pairingRunning) {
                        "Pairing..."
                    } else {
                        "Pair Apple Device"
                    }
                )
            }

            Text(
                pairingInfo,
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "Unlock your iPad and accept the Trust This Computer prompt if it appears.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // -----------------------------------------------------
        // APPLE DEVICES
        // -----------------------------------------------------

        SectionCard("APPLE DEVICES") {

            if (devices.isEmpty()) {
                Text(
                    "Connect an iPhone or iPad using USB OTG."
                )
            }

            devices.forEach { device ->

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),

                        verticalArrangement =
                            Arrangement.spacedBy(5.dp)
                    ) {

                        Text(
                            device.productName ?: "Apple USB device",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Text(
                            "VID: %04X  PID: %04X  Device ID: %d".format(
                                device.vendorId,
                                device.productId,
                                device.deviceId
                            )
                        )

                        Text(
                            "Interfaces: ${device.interfaceCount}"
                        )

                        Button(
                            onClick = {
                                onRequestPermission(device)
                            }
                        ) {
                            Text("Request USB permission")
                        }
                    }
                }
            }

            Text(
                "USB detection and permission do not establish an Apple pairing relationship.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // -----------------------------------------------------
        // IPA INSPECTOR
        // -----------------------------------------------------

        SectionCard("IPA INSPECTOR") {

            Button(onClick = onChooseIpa) {
                Text("Choose IPA file")
            }

            Text(
                ipaInfo,
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedButton(
                onClick = onCopyIpa,
                enabled =
                    ipaInfo != "No IPA selected" &&
                        ipaInfo != "No valid IPA loaded",
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Copy IPA Information")
            }

            Text(
                operationStatus,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // -----------------------------------------------------
        // FEATURE STATUS
        // -----------------------------------------------------

        SectionCard("FEATURE STATUS") {

            StatusLine("Android USB enumeration", true)
            StatusLine("Android USB permission", true)
            StatusLine("USB bulk endpoint diagnostics", true)
            StatusLine("Apple usbmux interface selection", true)
            StatusLine("USBMUX version negotiation", true)
            StatusLine("USBMUX2 setup packet transmission", true)
            StatusLine("USBMUX2 TCP handshake", true)
            StatusLine("Lockdown QueryType exchange", true)
            StatusLine("Lockdown GetValue requests", true)
            StatusLine("XML plist data parsing", true)
            StatusLine("Nested XML plist parsing", true)
            StatusLine("RSA public key validation", true)

            StatusLine(
                "Lockdown pairing",
                false
            )

            StatusLine("IPA ZIP inspection", true)
            StatusLine("Binary plist metadata parsing", false)
            StatusLine("Apple ID / Anisette authentication", false)
            StatusLine("Code signing / provisioning", false)
            StatusLine("Installation Proxy / install", false)
            StatusLine("SideStore / LiveContainer workflows", false)
        }

        Text(
            "This development build supports USBMUX2, basic Lockdown requests, and RSA public-key parsing. Pairing still requires device-side verification. Signing and IPA installation are not implemented.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// =============================================================
// REUSABLE UI COMPONENTS
// =============================================================

@androidx.compose.runtime.Composable
private fun SectionCard(
    title: String,
    content: @androidx.compose.runtime.Composable () -> Unit
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(16.dp),

            verticalArrangement =
                Arrangement.spacedBy(9.dp)
        ) {

            Text(
                title,
                style = MaterialTheme.typography.titleMedium
            )

            content()
        }
    }
}

@androidx.compose.runtime.Composable
private fun StatusLine(
    label: String,
    implemented: Boolean
) {

    Row(
        modifier = Modifier.fillMaxWidth(),

        horizontalArrangement =
            Arrangement.SpaceBetween
    ) {

        Text(
            label,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            if (implemented) {
                "Implemented"
            } else {
                "Pending"
            },

            color =
                if (implemented) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
        )
    }
}
