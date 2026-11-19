
#include <jni.h>
#include <cerrno>
#include <cstring>
#include <cstdio>
#include <sys/ioctl.h>

#define USBDEVFS_CLAIMINTERFACE  _IOR('U', 15, unsigned int)
#define USBDEVFS_RELEASEINTERFACE _IOR('U', 16, unsigned int)

extern "C"
JNIEXPORT jstring JNICALL
Java_com_impactor_android_NativeUsbDiagnostics_probeClaim(
        JNIEnv *env,
        jobject /* thiz */,
        jint fileDescriptor,
        jint interfaceNumber) {

    char result[1024];

    if (fileDescriptor < 0) {
        snprintf(
            result,
            sizeof(result),
            "Invalid USB file descriptor: %d",
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

    errno = 0;

    int claimResult = ioctl(
        fileDescriptor,
        USBDEVFS_CLAIMINTERFACE,
        &interfaceIndex
    );

    int claimError = errno;

    if (claimResult < 0) {
        snprintf(
            result,
            sizeof(result),
            "Native USB claim: FAILED\n"
            "Interface: %d\n"
            "ioctl result: %d\n"
            "errno: %d\n"
            "Error: %s",
            interfaceNumber,
            claimResult,
            claimError,
            strerror(claimError)
        );

        return env->NewStringUTF(result);
    }

    errno = 0;

    int releaseResult = ioctl(
        fileDescriptor,
        USBDEVFS_RELEASEINTERFACE,
        &interfaceIndex
    );

    int releaseError = errno;

    snprintf(
        result,
        sizeof(result),
        "Native USB claim: SUCCESS\n"
        "Interface: %d\n"
        "Native release: %s\n"
        "Release errno: %d (%s)",
        interfaceNumber,
        releaseResult == 0 ? "SUCCESS" : "FAILED",
        releaseResult == 0 ? 0 : releaseError,
        releaseResult == 0 ? "none" : strerror(releaseError)
    );

    return env->NewStringUTF(result);
}
