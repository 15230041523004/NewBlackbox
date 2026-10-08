package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;

public class NetworkPermissionCompat implements IInjectHook {
    private static final String TAG = "NetworkPermissionCompat";

    private static volatile boolean sInstalled;

    @Override
    public void injectHook() {
        install();
    }

    @Override
    public boolean isBadEnv() {
        return !sInstalled;
    }

    public static void install() {
        if (sInstalled) {
            return;
        }
        synchronized (NetworkPermissionCompat.class) {
            if (sInstalled) {
                return;
            }
            try {
                Class<?> permissionManager = Class.forName("android.permission.PermissionManager");
                try {
                    Method disablePermissionCache = permissionManager.getDeclaredMethod("disablePermissionCache");
                    disablePermissionCache.setAccessible(true);
                    disablePermissionCache.invoke(null);
                    Slog.d(TAG, "disabled framework permission cache via method");
                } catch (Throwable ignored) {
                }
                try {
                    Method disablePackageCache = permissionManager.getDeclaredMethod("disablePackageNamePermissionCache");
                    disablePackageCache.setAccessible(true);
                    disablePackageCache.invoke(null);
                    Slog.d(TAG, "disabled framework package permission cache via method");
                } catch (Throwable ignored) {
                }
                try {
                    java.lang.reflect.Field permCacheField = permissionManager.getDeclaredField("sPermissionCache");
                    permCacheField.setAccessible(true);
                    Object permCache = permCacheField.get(null);
                    if (permCache != null) {
                        try {
                            Method disableLocal = permCache.getClass().getMethod("disableLocal");
                            disableLocal.invoke(permCache);
                            Slog.d(TAG, "disabled sPermissionCache via disableLocal");
                        } catch (Throwable ignored) {}
                        try {
                            Method clear = permCache.getClass().getMethod("clear");
                            clear.invoke(permCache);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {
                }
                try {
                    java.lang.reflect.Field pkgCacheField = permissionManager.getDeclaredField("sPackageNamePermissionCache");
                    pkgCacheField.setAccessible(true);
                    Object pkgCache = pkgCacheField.get(null);
                    if (pkgCache != null) {
                        try {
                            Method disableLocal = pkgCache.getClass().getMethod("disableLocal");
                            disableLocal.invoke(pkgCache);
                            Slog.d(TAG, "disabled sPackageNamePermissionCache via disableLocal");
                        } catch (Throwable ignored) {}
                        try {
                            Method clear = pkgCache.getClass().getMethod("clear");
                            clear.invoke(pkgCache);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {
                }
                sInstalled = true;
            } catch (Throwable e) {
                Slog.w(TAG, "install failed: " + e.getMessage(), e);
            }
        }
    }
}
