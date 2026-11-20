
#include <jni.h>
#include <cerrno>
#include <cstring>
#include <cstdio>

#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_impactor_android_NativeUsbDiagnostics_probeClaim(
        JNIEnv *env,
        jobject,
        jint fileDescriptor,
        jint interfaceNumber) {

    char result[2048] = {};

    if (fileDescriptor < 0) {
        snprintf(
            result,
            sizeof(result),
            "Invalid file descriptor: %d",
            fileDescriptor
        );
        return env->NewStringUTF(result);
    }

    if (interfaceNumber < 0) {
        snprintf(
            result,
            sizeof(result),
            "Invalid interface number: %d",
            interfaceNumber
        );
        return env->NewStringUTF(result);
    }

    unsigned int interfaceIndex =
        static_cast<unsigned int>(interfaceNumber);

    int used = 0;

    used += snprintf(
        result + used,
        sizeof(result) - used,
        "=== NATIVE USB DIAGNOSTICS ===\n"
        "File descriptor: %d\n"
        "Interface: %u\n",
        fileDescriptor,
        interfaceIndex
    );

    // Check whether a kernel driver is associated with this interface.
    struct usbdevfs_getdriver driverInfo = {};
    driverInfo.interface = interfaceIndex;

    errno = 0;

    int driverResult = ioctl(
        fileDescriptor,
        USBDEVFS_GETDRIVER,
        &driverInfo
    );

    int driverError = errno;

    if (driverResult == 0) {
        used += snprintf(
            result + used,
            sizeof(result) - used,
            "\nGETDRIVER: SUCCESS\n"
            "Driver: %s\n",
            driverInfo.driver
        );
    } else {
        used += snprintf(
            result + used,
            sizeof(result) - used,
            "\nGETDRIVER: FAILED\n"
            "Result: %d\n"
            "errno: %d\n"
            "Error: %s\n",
            driverResult,
            driverError,
            strerror(driverError)
        );
    }

    // Attempt the claim only as a diagnostic.
    errno = 0;

    int claimResult = ioctl(
        fileDescriptor,
        USBDEVFS_CLAIMINTERFACE,
        &interfaceIndex
    );

    int claimError = errno;

    if (claimResult < 0) {
        snprintf(
            result + used,
            sizeof(result) - used,
            "\nCLAIMINTERFACE: FAILED\n"
            "Result: %d\n"
            "errno: %d\n"
            "Error: %s\n"
            "No interface fallback was attempted.\n",
            claimResult,
            claimError,
            strerror(claimError)
        );

        return env->NewStringUTF(result);
    }

    used += snprintf(
        result + used,
        sizeof(result) - used,
        "\nCLAIMINTERFACE: SUCCESS\n"
    );

    // Release immediately. This is not a production transport claim.
    errno = 0;

    int releaseResult = ioctl(
        fileDescriptor,
        USBDEVFS_RELEASEINTERFACE,
        &interfaceIndex
    );

    int releaseError = errno;

    snprintf(
        result + used,
        sizeof(result) - used,
        "RELEASEINTERFACE: %s\n"
        "Result: %d\n"
        "errno: %d\n"
        "Error: %s\n",
        releaseResult == 0 ? "SUCCESS" : "FAILED",
        releaseResult,
        releaseResult == 0 ? 0 : releaseError,
        releaseResult == 0 ? "none" : strerror(releaseError)
    );

    return env->NewStringUTF(result);
}
