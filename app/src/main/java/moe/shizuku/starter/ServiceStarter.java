package moe.shizuku.starter;

import java.io.File;
import java.lang.reflect.InvocationTargetException;

import dalvik.system.DexClassLoader;
import dev.n2k02.localadb.BuildConfig;
import dev.n2k02.localadb.ShizukuUserServicePayload;

/**
 * Shizuku launches this name from the installed manager APK's classpath.
 * Delegate to the unchanged, checksum-pinned upstream starter in our shell payload.
 */
public final class ServiceStarter {
    public static void main(String[] args) throws Exception {
        File directory = new File("/data/local/tmp/" + BuildConfig.APPLICATION_ID + "-shizuku");
        File payload = new File(directory, "base.apk");
        ShizukuUserServicePayload.verify(payload);
        // The boot parent avoids resolving this trampoline, or the manager's older
        // Shizuku API classes, instead of the payload's own complete implementation.
        ClassLoader loader = new DexClassLoader(payload.getPath(), directory.getPath(),
                directory.getPath(), ClassLoader.getSystemClassLoader().getParent());
        Class<?> starter = loader.loadClass("moe.shizuku.starter.ServiceStarter");
        try {
            starter.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw failure;
        }
    }
}
