use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_impactor_android_NativeBridge_nativeBackendVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let value = env
        .new_string("Impactor Rust bridge 0.1.0-dev")
        .expect("failed to allocate Java string");
    value.into_raw()
}
