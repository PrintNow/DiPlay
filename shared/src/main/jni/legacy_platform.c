/*
 * Linux calls that Android 4.4 has no framework API for: usbfs configuration and alternate
 * setting selection (UsbDeviceConnection.setConfiguration/setInterface are API 21) and clearing
 * O_NONBLOCK on a VPN tun descriptor (VpnService.Builder.setBlocking is API 21).
 *
 * Every function returns 0 or a negative errno.
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_compat_LegacyPlatformNative_setConfiguration(
        JNIEnv *env, jobject thiz, jint fd, jint value) {
    (void) env;
    (void) thiz;
    unsigned int configuration = (unsigned int) value;
    return ioctl(fd, USBDEVFS_SETCONFIGURATION, &configuration) < 0 ? -errno : 0;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_compat_LegacyPlatformNative_setInterface(
        JNIEnv *env, jobject thiz, jint fd, jint interface_number, jint alternate_setting) {
    (void) env;
    (void) thiz;
    struct usbdevfs_setinterface setting = {
        .interface = (unsigned int) interface_number,
        .altsetting = (unsigned int) alternate_setting,
    };
    return ioctl(fd, USBDEVFS_SETINTERFACE, &setting) < 0 ? -errno : 0;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_compat_LegacyPlatformNative_setBlocking(
        JNIEnv *env, jobject thiz, jint fd) {
    (void) env;
    (void) thiz;
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0) return -errno;
    if ((flags & O_NONBLOCK) == 0) return 0;
    return fcntl(fd, F_SETFL, flags & ~O_NONBLOCK) < 0 ? -errno : 0;
}
