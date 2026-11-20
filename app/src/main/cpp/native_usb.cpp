
#include <jni.h>
#include <cerrno>
#include <cstring>
#include <cstdio>
#include <cstdarg>

#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

static void append(
        char* output,
        size_t capacity,
        size_t* used,
        const char* format,
        ...) {

    if (*used >= capacity) return;

    va_list args;
    va_start(args, format);

    int written = vsnprintf(
        output + *used,
        capacity - *used,
        format,
        args
    );

    va_end(args);

    if (written > 0) {
        size_t remaining = capacity - *used;
        *used += static_cast<size_t>(written) < remaining
            ? static_cast<size_t>(written)
            : remaining - 1;
    }
}

static void readUsbRequest(
        int fd,
        unsigned char requestType,
        unsigned char request,
        unsigned short index,
        const char* label,
        char* output,
        size_t capacity,
        size_t* used) {

    unsigned char value = 0;

    struct usbdevfs_ctrltransfer transfer = {};

    transfer.bRequestType = requestType;
    transfer.bRequest = request;
    transfer.wValue = 0;
    transfer.wIndex = index;
    transfer.wLength = 1;
    transfer.timeout = 1000;
    transfer.data = &value;

    errno = 0;

    int result = ioctl(
        fd,
        USBDEVFS_CONTROL,
        &transfer
    );

    int savedError = errno;

    if (result == 1) {
        append(
            output,
            capacity,
            used,
            "%s: SUCCESS\nValue: %u (0x%02X)\n",
            label,
            value,
            value
        );
    } else {
        append(
            output,
            capacity,
            used,
            "%s: FAILED\n"
            "Result: %d\n"
            "errno: %d\n"
            "Error: %s\n",
            label,
            result,
            savedError,
            strerror(savedError)
        );
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_impactor_android_NativeUsbDiagnostics_probeClaim(
        JNIEnv* env,
        jobject,
        jint fileDescriptor,
        jint interfaceNumber) {

    char result[4096] = {};
    size_t used = 0;

    if (fileDescriptor < 0 || interfaceNumber < 0 ||
        interfaceNumber > 255) {

        append(
            result,
            sizeof(result),
            &used,
            "Invalid arguments.\nFD: %d\nInterface: %d\n",
            fileDescriptor,
            interfaceNumber
        );

        return env->NewStringUTF(result);
    }

    unsigned int interfaceIndex =
        static_cast<unsigned int>(interfaceNumber);

    append(
        result,
        sizeof(result),
        &used,
        "=== NATIVE USB DIAGNOSTICS ===\n"
        "File descriptor: %d\n"
        "Interface: %u\n\n",
        fileDescriptor,
        interfaceIndex
    );

    // Standard GET_CONFIGURATION request.
    // Device-to-host, standard, device recipient.
    readUsbRequest(
        fileDescriptor,
        0x80,
        0x08,
        0,
        "GET_CONFIGURATION",
        result,
        sizeof(result),
        &used
    );

    // Standard GET_INTERFACE request.
    // Device-to-host, standard, interface recipient.
    readUsbRequest(
        fileDescriptor,
        0x81,
        0x0A,
        static_cast<unsigned short>(interfaceIndex),
        "GET_INTERFACE",
        result,
        sizeof(result),
        &used
    );

    // Check for a kernel driver associated with this interface.
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
        append(
            result,
            sizeof(result),
            &used,
            "\nGETDRIVER: SUCCESS\nDriver: %s\n",
            driverInfo.driver
        );
    } else {
        append(
            result,
            sizeof(result),
            &used,
            "\nGETDRIVER: FAILED\n"
            "Result: %d\n"
            "errno: %d\n"
            "Error: %s\n",
            driverResult,
            driverError,
            strerror(driverError)
        );
    }

    // Diagnostic claim attempt. No other interface is attempted.
    errno = 0;

    int claimResult = ioctl(
        fileDescriptor,
        USBDEVFS_CLAIMINTERFACE,
        &interfaceIndex
    );

    int claimError = errno;

    if (claimResult < 0) {
        append(
            result,
            sizeof(result),
            &used,
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

    append(
        result,
        sizeof(result),
        &used,
        "\nCLAIMINTERFACE: SUCCESS\n"
    );

    // Immediately release a successful diagnostic claim.
    errno = 0;

    int releaseResult = ioctl(
        fileDescriptor,
        USBDEVFS_RELEASEINTERFACE,
        &interfaceIndex
    );

    int releaseError = errno;

    append(
        result,
        sizeof(result),
        &used,
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
