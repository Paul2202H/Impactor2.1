
private fun probeUsbTransport() {
    val device = selectedDevice ?: run {
        status.value = "No Apple device selected"
        return
    }

    if (!usbManager.hasPermission(device)) {
        requestUsbPermission(device)
        return
    }

    // Close any previous connection on the main thread.
    closeTransport()
    status.value = "Inspecting USB interfaces..."

    lifecycleScope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                val interfaces = AppleUsbTransport.inspectInterfaces(device)
                val candidates = AppleUsbTransport.candidates(device)

                val report = StringBuilder()

                report.appendLine("USB DEVICE DIAGNOSTICS")
                report.appendLine("Product: ${device.productName ?: "Apple device"}")
                report.appendLine("VID: %04X  PID: %04X".format(
                    device.vendorId,
                    device.productId
                ))
                report.appendLine("Total interfaces: ${interfaces.size}")
                report.appendLine("Bulk candidates: ${candidates.size}")
                report.appendLine()

                report.appendLine("INTERFACE DESCRIPTORS")

                interfaces.forEach { info ->
                    report.appendLine("Interface ${info.interfaceNumber}")
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
                    return@withContext Pair(null, report.toString())
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
                            "CLAIM: FAILED - ${e.message ?: e.javaClass.simpleName}"
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
                        "Apple usbmux handshake is still NOT implemented."
                    )
                }

                Pair(openedTransport, report.toString())
            }

            // Update the transport and Compose state on the main thread.
            transport = result.first
            transportInfo.value = result.second

            status.value = if (result.first != null) {
                "USB interface opened. Apple protocol session not established."
            } else {
                "USB interface claim failed. See diagnostic report."
            }

        } catch (t: Exception) {
            transportInfo.value =
                "USB diagnostic failed: ${t.message ?: t.javaClass.simpleName}"

            status.value = "USB diagnostic failed"
        }
    }
}
