# Impactor Android — Initial Repository Analysis

Analysis date: 2026-11-19
Upstream: https://github.com/claration/impactor
Upstream branch inspected: `main`
Upstream workspace version observed: `2.7.3`

## 1. Repository structure observed

The upstream Cargo workspace contains:

- `apps/plumeimpactor` — desktop application entry point and Iced UI.
- `apps/plumesign` — signing-related application target.
- `crates/plume_core` — Apple developer authentication, certificate/provisioning and signing support.
- `crates/plume_utils` — IPA/bundle processing, signing orchestration, device operations and tweak handling.
- `crates/plume_store` — account and refresh data persistence.
- `crates/plume_gestalt` — macOS-specific support.
- `3rdparty/omnisette` — Anisette support.
- `3rdparty/decompress` — archive/decompression support.
- `3rdparty/apple-codesign-0.29.0` — patched Apple code-signing dependency.

The workspace uses Rust edition 2024 and resolver 3. The upstream workspace pins `idevice` to a Git revision and enables its `full` and `ring` features.

## 2. Reusable components

### Strong candidates for reuse

- `plume_core`: Apple developer API/session logic, certificate identity, provisioning, entitlement/signing primitives.
- `plume_utils::Bundle` and `Package`: application bundle and IPA package handling.
- `plume_utils::Signer`: metadata edits, app registration, provisioning and code-sign orchestration.
- `plume_utils::Tweak`: supported tweak and ElleKit processing.
- `plume_store`: account and refresh metadata, subject to Android-safe storage changes.
- `idevice`: Lockdown, Installation Proxy, Misagent, AFC, pairing records, remote pairing and other Apple service protocols.

### Desktop-only or unsuitable as the Android UI

`apps/plumeimpactor` currently uses Iced, wgpu, tray-icon, rfd, desktop notification, auto-launch, GTK and platform-specific macOS/Windows support. The Android product should not attempt to reuse this desktop UI. Replace it with Kotlin, Jetpack Compose and Material 3 while keeping backend crates separate.

## 3. Critical USB transport finding

The inspected `idevice` implementation exposes `UsbmuxdAddr` as a Unix-domain socket or TCP socket. Its `UsbmuxdProvider` connects to the usbmuxd daemon through that address. The Impactor `Device` implementation constructs this provider and uses it for Lockdown, Installation Proxy, Misagent and pairing-record operations.

That works on desktop systems where a usbmuxd service is installed or provided by the OS. A normal Android application cannot assume `/var/run/usbmuxd` exists, and must not require a separate daemon or root access.

### Required Android adaptation

1. Kotlin uses Android `UsbManager` to enumerate Apple USB devices and request user permission.
2. A native USB transport layer opens the permitted device/interface/endpoints and performs bulk I/O.
3. Implement the usbmux framing/protocol over that USB transport.
4. Adapt `idevice` so its existing higher-level code can use an injected transport stream/provider rather than only Unix/TCP usbmuxd sockets.
5. Feed the discovered device identity and connection handle into the existing Lockdown and service clients.
6. Keep pairing records in app-private encrypted storage and export only when explicitly requested.

The exact Apple interface/endpoint selection, transfer sizes, timeouts, reconnect behavior and device-mode variations must be verified against real hardware. VID `0x05AC` is a useful discovery filter, not proof that a complete usable Apple transport is available.

## 4. Proposed architecture

- `app/`: Kotlin + Jetpack Compose UI, Android USB Host API, SAF, lifecycle and encrypted storage.
- `native/impactor_android/`: JNI boundary and Android-specific Rust orchestration.
- Upstream backend crates: imported as workspace dependencies or vendored/patched submodules after dependency and license review.
- `AndroidUsbTransport`: Android-specific byte-stream implementation.
- `AndroidUsbmuxProvider`: custom provider/transport adapter consumed by `idevice`.
- `DeviceRepository`: device discovery, pairing state and device metadata.
- `IpaRepository`: SAF URI handling, bounded-memory ZIP inspection and app-private staging.
- `SigningRepository`: reusable `plume_core` and `plume_utils` signing flows.

## 5. Signing and storage notes

- Apple authentication and provisioning must remain legitimate Apple-server workflows.
- Never log Apple passwords, session tokens, private keys or pairing secrets.
- Android Keystore should protect an encryption key used to encrypt locally stored signing material. P12 export needs an explicit user action and a user-selected destination.
- SAF URIs are not ordinary filesystem paths. IPA inputs should be streamed/copied to controlled app-private staging before calling path-based Rust bundle APIs.
- Large IPA files must be processed as streams and staged with adequate free-space checks.
- Free-account signing limits and developer-account limits must be displayed accurately.

## 6. Licensing review required

The upstream root workspace declares MIT, but `plume_core` declares MPL-2.0. The patched `apple-codesign`, `idevice`, `omnisette`, decompression and all transitive dependencies require their own license notices and compliance review. Do not label the entire Android app MIT without completing this review.

## 7. Current scaffold status

This repository is an Android foundation scaffold only:

- Compose dashboard: started.
- Apple VID USB discovery: started.
- Android USB permission request: started.
- SAF document picker: started.
- JNI bridge skeleton: started.
- Rust cross-build script: started.

Not implemented or verified yet:

- Apple usbmux transport over Android USB.
- Lockdown pairing/trust.
- Device information retrieval.
- IPA metadata extraction and signing.
- Apple authentication and provisioning.
- Installation Proxy installation.
- SideStore/LiveContainer presets.
- A release APK.

No APK is included or claimed to exist at this stage.

## 8. Follow-up implementation update (2026-11-19)

The Android scaffold now includes an experimental `AppleUsbTransport` that enumerates interfaces with bulk IN/OUT endpoints, claims a selected interface after Android permission is granted, and exposes bounded bulk read/write methods. It also includes `IpaInspector`, which stages SAF-selected files in app-private cache and inspects the IPA ZIP structure without loading the complete archive into memory. Basic XML Info.plist fields are extracted; binary plist remains unsupported in this Kotlin-only stage.

This is not yet the required usbmux adapter. A raw USB bulk channel must not be confused with an Apple device protocol session. The transport needs correct interface selection, framing, usbmux protocol implementation, reconnect semantics, and real-device verification before it can be passed to `idevice` higher-level services.
