package dev.n2k02.localadb;

import android.os.ParcelFileDescriptor;

/** Only supported methods: verify shell identity and install one selected APK. */
interface IDirectApkInstallService {
    void destroy() = 16777114;
    int getServiceUid() = 1;
    String installApk(in ParcelFileDescriptor apk, long length, String installerPackageName) = 2;
}
