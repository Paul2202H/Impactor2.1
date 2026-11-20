# Impactor Android — Native Android Port (development project)

This is a native Kotlin/Jetpack Compose Android project scaffold and early USB/IPA inspection implementation for the Impactor Android port.

**It is not yet a complete sideloading application. It does not currently pair with iOS, sign IPA files, or install apps.**

## Included in this revision

- Native Android application shell using Kotlin and Jetpack Compose Material 3.
- Android USB Host enumeration filtered to Apple vendor ID `0x05AC`.
- Android USB permission request handling.
- USB interface discovery for bulk IN/OUT endpoint pairs.
- Experimental USB interface claim and low-level bulk read/write wrapper.
- SAF file picker and streamed copy of a selected IPA to app-private cache.
- IPA ZIP structure inspection and basic XML Info.plist field extraction.
- Rust JNI bridge skeleton and ARM64 Cargo build script.
- Initial upstream architecture analysis and explicit feature acceptance matrix.

## Current critical limitation

The upstream `idevice` backend expects to reach usbmuxd through a Unix-domain or TCP socket. Android applications cannot assume that desktop usbmuxd daemon/socket exists. The current `AppleUsbTransport` is only a low-level Android USB bulk-transfer wrapper. It is **not** an implemented usbmux protocol or a compatible `idevice` provider. Lockdown pairing, service startup, signing, and installation are therefore still pending.

## Build prerequisites

- Android Studio with Android SDK Platform 36 installed.
- JDK 17 or the JDK version required by the installed Android Gradle Plugin.
- Gradle 8.13+ compatible with Android Gradle Plugin 8.13.2.
- For the native bridge: Rust toolchain, Android NDK, and `cargo-ndk`.

Open the project root in Android Studio, allow Gradle sync, then build the `app` debug variant. This environment did not have Android SDK/Gradle installed, so compilation was not performed here.

## Rust bridge

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
chmod +x scripts/build-rust-android.sh
./scripts/build-rust-android.sh
```

The current Rust library is a JNI smoke-test only. It does not yet link upstream Impactor crates.

## Important source documents

- `docs/PORTING_ANALYSIS.md` — upstream workspace and architecture analysis.
- `docs/IMPLEMENTATION_STATUS.md` — what is implemented, what is pending, and required acceptance tests.

## License

Review the upstream root license and each crate/dependency license independently before redistributing a combined binary. The upstream workspace contains components with different licenses; do not assume the entire resulting Android application is MIT-licensed.
