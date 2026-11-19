#!/usr/bin/env bash
set -euo pipefail

# Run on a build machine with Rust, Android NDK, cargo-ndk, and Android SDK installed.
# This builds only the JNI bridge scaffold. Existing Impactor crates are integrated
# after Android USB transport work is validated.
cd "$(dirname "$0")/../native/impactor_android"

rustup target add aarch64-linux-android
cargo ndk -t arm64-v8a -o ../../app/src/main/jniLibs build --release
