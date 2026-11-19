
package com.impactor.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    private val ipaInfo = mutableStateOf(
        "No IPA selected"
    )

    private val operationStatus = mutableStateOf(
        "No installation operation has been run"
    )

    private var transport: AppleUsbTransport? = null
    private var selectedDevice: UsbDevice? = null
    private var stagedIpa: IpaInspector.Result? = null

    private val permissionAction =
        "com.impactor.android.USB_PERMISSION"

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

                    status.value =
                        if (granted && device != null) {
                            selectedDevice = device
                            "USB permission granted. Device is not paired yet."
                        } else {
                            "USB permission denied or device disconnected."
                        }

                    refreshDevices()
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    status.value = "USB device attached"
                    refreshDevices()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    closeTransport()
                    selectedDevice = null
                    status.value = "USB device detached"
                    refreshDevices()
                }
            }
        }
    }

    private val ipaPicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                inspectIpa(uri)
            }
        }

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
                        ipaInfo = ipaInfo.value,
                        operationStatus = operationStatus.value,
                        onScan = ::refreshDevices,
                        onRequestPermission = ::requestUsbPermission,
                        onProbe = ::probeUsbTransport,
                        onCloseTransport = ::closeTransport,
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

        usbDevices.clear()

        val found = usbManager.deviceList.values.filter {
            it.vendorId == APPLE_VENDOR_ID
        }

        usbDevices.addAll(found)

        if (found.isEmpty()) {

            status.value =
                "No Apple USB device detected. Check your OTG adapter and cable."

            selectedDevice = null
            closeTransport()

        } else {

            status.value =
                "${found.size} Apple USB device(s) detected. Detection does not mean paired."

            if (
                selectedDevice == null ||
                found.none {
                    it.deviceId == selectedDevice?.deviceId
                }
            ) {
                selectedDevice = found.first()
            }
        }
    }

    // ---------------------------------------------------------
    // USB PERMISSION
    // ---------------------------------------------------------

    private fun requestUsbPermission(device: UsbDevice) {

        selectedDevice = device

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
    // USB INTERFACE DIAGNOSTICS
    // ---------------------------------------------------------

    private fun probeUsbTransport() {

        val device = selectedDevice ?: run {
            status.value = "No Apple device selected"
            return
        }

        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(device)
            return
        }

        closeTransport()

        status.value = "Inspecting USB interfaces..."

        lifecycleScope.launch {

            try {

                val result = withContext(Dispatchers.IO) {

                    val interfaces =
                        AppleUsbTransport.inspectInterfaces(device)

                    val candidates =
                        AppleUsbTransport.candidates(device)

                    val report = StringBuilder()

                    report.appendLine("USB DEVICE DIAGNOSTICS")
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
                        "Total interfaces: ${interfaces.size}"
                    )

                    report.appendLine(
                        "Bulk candidates: ${candidates.size}"
                    )

                    report.appendLine()

                    report.appendLine("INTERFACE DESCRIPTORS")

                    interfaces.forEach { info ->

                        report.appendLine(
                            "Interface ${info.interfaceNumber}"
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
                            "Likely usbmux descriptor: ${info.isLikelyUsbmux}"
                        )

                        report.appendLine()
                    }

                    if (candidates.isEmpty()) {

                        report.appendLine(
                            "RESULT: No interfaces with both bulk IN and OUT endpoints."
                        )

                        return@withContext Pair(
                            null,
                            report.toString()
                        )
                    }

                    report.appendLine("INTERFACE CLAIM TESTS")

                    var openedTransport: AppleUsbTransport? = null

                    candidates.forEachIndexed { index, candidate ->

                        if (openedTransport != null) {
                            return@forEachIndexed
                        }

                        report.appendLine(
                            "Trying interface ${candidate.usbInterface.id} " +
                                "(candidate $index)"
                        )

                        try {

                            val opened = AppleUsbTransport.open(
                                manager = usbManager,
                                device = device,
                                candidateIndex = index,
                                allowUnverifiedInterface = true
                            )

                            openedTransport = opened

                            report.appendLine("CLAIM: SUCCESS")

                            report.appendLine(
                                "Interface: ${opened.details.interfaceNumber}"
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

                            report.appendLine(
                                "Likely usbmux: ${opened.isLikelyUsbmux}"
                            )

                        } catch (e: Exception) {

                            report.appendLine(
                                "CLAIM: FAILED - ${
                                    e.message ?: e.javaClass.simpleName
                                }"
                            )
                        }

                        report.appendLine()
                    }

                    if (openedTransport == null) {

                        report.appendLine(
                            "RESULT: Android could not claim any tested interface."
                        )

                    } else {

                        report.appendLine(
                            "RESULT: USB interface successfully claimed."
                        )

                        report.appendLine(
                            "Apple usbmux handshake is NOT implemented."
                        )
                    }

                    Pair(
                        openedTransport,
                        report.toString()
                    )
                }

                // Update app state on the main thread.
                transport = result.first
                transportInfo.value = result.second

                status.value =
                    if (result.first != null) {
                        "USB interface opened. Apple protocol session not established."
                    } else {
                        "USB interface claim failed. See diagnostic report."
                    }

            } catch (t: Exception) {

                transportInfo.value =
                    "USB diagnostic failed: ${
                        t.message ?: t.javaClass.simpleName
                    }"

                status.value = "USB diagnostic failed"
            }
        }
    }

    // ---------------------------------------------------------
    // CLOSE USB CONNECTION
    // ---------------------------------------------------------

    private fun closeTransport() {

        runCatching {
            transport?.close()
        }

        transport = null
        transportInfo.value = "USB transport is closed"
    }

    // ---------------------------------------------------------
    // IPA INSPECTION
    // ---------------------------------------------------------

    private fun inspectIpa(uri: Uri) {

        operationStatus.value = "Copying and inspecting IPA..."

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

    override fun onDestroy() {

        closeTransport()

        stagedIpa?.stagedFile?.delete()

        unregisterReceiver(usbReceiver)

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
    ipaInfo: String,
    operationStatus: String,
    onScan: () -> Unit,
    onRequestPermission: (UsbDevice) -> Unit,
    onProbe: () -> Unit,
    onCloseTransport: () -> Unit,
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

            Text(
                operationStatus,
                style = MaterialTheme.typography.bodySmall
            )
        }

        SectionCard("FEATURE STATUS") {

            StatusLine("Android USB enumeration", true)
            StatusLine("Android USB permission", true)
            StatusLine("USB bulk endpoint diagnostics", true)
            StatusLine("usbmux / Lockdown pairing", false)
            StatusLine("IPA ZIP inspection", true)
            StatusLine("Binary plist metadata parsing", false)
            StatusLine("Apple ID / Anisette authentication", false)
            StatusLine("Code signing / provisioning", false)
            StatusLine("Installation Proxy / install", false)
            StatusLine("SideStore / LiveContainer workflows", false)
        }

        Text(
            "This development build does not yet pair, sign, or install applications. USB endpoint probing is diagnostic only.",
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
