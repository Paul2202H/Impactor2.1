
package com.impactor.android

object NativeUsbDiagnostics {

    init {
        System.loadLibrary("impactor_usb")
    }

    external fun probeClaim(
        fileDescriptor: Int,
        interfaceNumber: Int
    ): String
}
