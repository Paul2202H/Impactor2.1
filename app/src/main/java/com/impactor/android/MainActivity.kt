
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

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager

    private val usbDevices = mutableStateListOf<UsbDevice>()

    private val status = mutableStateOf(
        "Scanning for Apple USB devices..."
    )

    private val transportInfo = mutableStateOf(
        "USB transport is closed"
    )

    private val usbmuxInfo = mutableStateOf(
        "USBMUX handshake has not been tested."
    )

    private val lockdownInfo = mutableStateOf(
        "Lockdown TCP connection has not been tested."
    )

    private val ipaInfo = mutableStateOf("No IPA selected")

    private val operationStatus = mutableStateOf(
        "No installation operation has been run"
    )

    private val diagnosticReport = mutableStateOf("")

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

        override fun onReceive(context: Context, intent: Intent) {

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
            if (uri != null) {
                inspectIpa(uri)
            }
        }

    // ---------------------------------------------------------
    // ACTIVITY CREATION
    // ---------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager =
            getSystemService(Context.USB_SERVICE) as UsbManager

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
                        ipaInfo = ipaInfo.value,
                        operationStatus = operationStatus.value,
                        hasDiagnosticReport =
                            diagnosticReport.value.isNotBlank(),

                        onScan = ::refreshDevices,
                        onRequestPermission = ::requestUsbPermission,
                        onProbe = ::probeUsbTransport,
                        onUsbMuxHandshake = ::testUsbMuxHandshake,
                        onConnectLockdown = ::connectLockdown,
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

        val intent = Intent(permissionAction)
            .setPackage(packageName)

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
                        report.appendLine("Configuration: ${info.configurationId}")
                        report.appendLine("Interface number: ${info.interfaceNumber}")

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
                        candidates.filter { it.isLikelyUsbmux }

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

                        return@withContext Pair(null, report.toString())
                    }

                    report.appendLine(
                        "Only matching Apple usbmux interfaces will be attempted."
                    )

                    report.appendLine(
                        "Generic interfaces will NOT be used as fallback."
                    )

                    report.appendLine()

                    try {

                        val opened =
                            AppleUsbTransport.openUsbmux(
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

                        report.appendLine("USBMUX INTERFACE CLAIM: FAILED")
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
            status.value = "Claim the Apple usbmux interface first."
            return
        }

        if (usbmuxTestRunning) {
            status.value = "USBMUX handshake is already running."
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

                diagnosticReport.value +=
                    "\n\n$report"

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
    // LOCKDOWN TCP CONNECTION TEST
    // ---------------------------------------------------------

    private fun connectLockdown() {

        val activeTransport = transport

        if (activeTransport == null) {
            status.value = "Claim the Apple usbmux interface first."
            return
        }

        if (!usbmux2Ready) {
            status.value =
                "Run a successful USBMUX2 version handshake first."
            return
        }

        if (lockdownTestRunning) {
            status.value = "TCP connection test is already running."
            return
        }

        if (lockdownTcp?.isConnected == true) {
            status.value =
                "The experimental TCP connection is already established."
            return
        }

        lockdownTestRunning = true

        lockdownInfo.value =
            "Attempting experimental USBMUX2 TCP connection to port 62078..."

        status.value =
            "Testing USBMUX2 pseudo-TCP connection..."

        lifecycleScope.launch {

            val connection = UsbMux2TcpConnection(
                transport = activeTransport,
                remotePort = 62078
            )

            try {

                val result = withContext(Dispatchers.IO) {
                    connection.connect(timeoutMs = 7000)
                }

                val report = buildString {

                    appendLine("APPLE LOCKDOWN TCP TEST")
                    appendLine("Date: ${java.util.Date()}")
                    appendLine("Result: ${result.message}")
                    appendLine("Success: ${result.success}")
                    appendLine("Local port: ${result.localPort}")
                    appendLine("Remote port: ${result.remotePort}")

                    appendLine()
                    appendLine("RAW RESPONSE:")

                    appendLine(
                        result.rawResponse.ifBlank {
                            "No TCP response data."
                        }
                    )

                    appendLine()
                    appendLine(
                        "Lockdown protocol exchange: NOT IMPLEMENTED"
                    )

                    appendLine(
                        "Pairing: NOT IMPLEMENTED"
                    )
                }

                lockdownInfo.value = report

                if (result.success) {
                    lockdownTcp = connection
                    status.value =
                        "Experimental USBMUX2 TCP handshake reported success."
                } else {
                    connection.close()
                    status.value =
                        "Experimental TCP connection failed."
                }

                diagnosticReport.value +=
                    "\n\n$report"

            } catch (e: Exception) {

                connection.close()

                val error =
                    "LOCKDOWN TCP TEST ERROR\n" +
                        "${e.javaClass.simpleName}: ${e.message}"

                lockdownInfo.value = error
                diagnosticReport.value += "\n\n$error"

                status.value =
                    "Experimental TCP connection failed."

            } finally {
                lockdownTestRunning = false
            }
        }
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

        runCatching {
            transport?.close()
        }

        transport = null

        transportInfo.value = "USB transport is closed"
        usbmuxInfo.value =
            "USBMUX handshake has not been tested."

        lockdownInfo.value =
            "Lockdown TCP connection has not been tested."
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
    ipaInfo: String,
    operationStatus: String,
    hasDiagnosticReport: Boolean,
    onScan: () -> Unit,
    onRequestPermission: (UsbDevice) -> Unit,
    onProbe: () -> Unit,
    onUsbMuxHandshake: () -> Unit,
    onConnectLockdown: () -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        Text(
            "IMPACTOR ANDROID",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            "Native Android port • Development build",
            style = MaterialTheme.typography.bodyMedium
        )

        SectionCard("USB CONNECTION") {

            Text(status)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                Text("Test Lockdown TCP Connection")
            }

            Text(
                lockdownInfo,
                style = MaterialTheme.typography.bodySmall
            )
        }

        SectionCard("APPLE DEVICES") {

            if (devices.isEmpty()) {
                Text("Connect an iPhone or iPad using USB OTG.")
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
                        verticalArrangement = Arrangement.spacedBy(5.dp)
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

                        Text("Interfaces: ${device.interfaceCount}")

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
                "Connected does not mean paired. Lockdown pairing is not implemented yet.",
                style = MaterialTheme.typography.bodySmall
            )
        }

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
                enabled = ipaInfo != "No IPA selected" &&
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

        SectionCard("FEATURE STATUS") {

            StatusLine("Android USB enumeration", true)
            StatusLine("Android USB permission", true)
            StatusLine("USB bulk endpoint diagnostics", true)
            StatusLine("Apple usbmux interface selection", true)
            StatusLine("USBMUX version negotiation", true)
            StatusLine("USBMUX2 setup packet transmission", true)
            StatusLine("USBMUX2 TCP connection", false)
            StatusLine("Lockdown protocol exchange", false)
            StatusLine("Lockdown pairing", false)
            StatusLine("IPA ZIP inspection", true)
            StatusLine("Binary plist metadata parsing", false)
            StatusLine("Apple ID / Anisette authentication", false)
            StatusLine("Code signing / provisioning", false)
            StatusLine("Installation Proxy / install", false)
            StatusLine("SideStore / LiveContainer workflows", false)
        }

        Text(
            "This development build does not yet pair, sign, or install applications. USB interface probing and USBMUX protocol tests are diagnostic only.",
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
            verticalArrangement = Arrangement.spacedBy(9.dp)
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
        horizontalArrangement = Arrangement.SpaceBetween
    ) {

        Text(
            label,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            if (implemented) "Implemented" else "Pending",
            color = if (implemented) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }
}
