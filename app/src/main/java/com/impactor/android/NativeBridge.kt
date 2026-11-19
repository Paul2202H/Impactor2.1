package com.impactor.android

/**
 * JNI boundary for the reusable Rust core.
 * The native library is optional until the Rust build step is integrated.
 */
object NativeBridge {
    private var loaded = false

    init {
        loaded = try {
            System.loadLibrary("impactor_android")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    external fun nativeBackendVersion(): String

    fun backendStatus(): String =
        if (loaded) nativeBackendVersion() else "Rust backend not packaged yet"
}
