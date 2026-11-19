# Implementation Status and Acceptance Matrix

This file deliberately distinguishes code present from behavior proven on hardware.

| Capability | Status | Evidence / limitation |
|---|---|---|
| Native Kotlin / Compose app shell | Implemented in source | Not compiled in this environment |
| Apple VID USB enumeration | Implemented in source | Depends on phone USB Host support and cable/adapter |
| Android USB permission request | Implemented in source | Runtime test still required |
| USB interface / bulk endpoint discovery | Implemented in source | Does not identify the correct Apple protocol interface on every model |
| USB interface claim and bulk read/write wrapper | Implemented in source | Not yet exercised against an iPhone/iPad |
| usbmux protocol framing/session | Not implemented | Must be implemented and verified against Apple's USB transport |
| Lockdown handshake / Pair / ValidatePair | Not implemented | Requires usbmux transport and device trust interaction |
| Device metadata retrieval | Not implemented | Requires Lockdown session |
| IPA SAF selection and staged copy | Implemented in source | Uses app-private cache; large-file/device storage testing pending |
| IPA ZIP structure inspection | Implemented in source | Finds Payload/*.app/Info.plist |
| XML Info.plist fields | Partially implemented | Binary plist is common and is not parsed by current Kotlin inspector |
| Binary plist parsing | Not implemented | Integrate Rust `plist` crate or audited Android plist parser |
| Apple Account / 2FA / Anisette | Not implemented | Port and audit upstream auth dependencies for Android |
| Certificate / provisioning profile flow | Not implemented | Requires Apple developer services and credentials |
| IPA code signing | Not implemented | Upstream signer crates not yet integrated into Android build |
| Installation Proxy | Not implemented | Requires working usbmux + Lockdown + service transport |
| SideStore / LiveContainer presets | Not implemented | Must be based on current metadata/entitlements and real-device tests |
| Debug / release APK | Not produced | Build tools/Android SDK/Gradle are unavailable in this execution environment |

## Required real-device acceptance work

1. Build and install on at least one ARM64 Android phone with USB Host support.
2. Connect a physical iPhone/iPad over a known data-capable OTG cable.
3. Record USB descriptors for supported iOS versions and device families.
4. Verify correct interface selection and bulk transfer framing.
5. Implement and test usbmux ListDevices, Connect and device message exchange.
6. Complete Lockdown Pair and ValidatePair with user approval on the Apple device.
7. Retrieve DeviceName, ProductType, ProductVersion, BuildVersion and UniqueDeviceID.
8. Start Installation Proxy and install a known, legitimately signed test IPA.
9. Integrate upstream authentication/signing and test certificate expiry/revocation paths.
10. Test detach/reconnect, screen lock, phone sleep, large IPA, low storage and interrupted transfers.

Do not mark a row complete based only on a UI control or a successful USB permission prompt.
