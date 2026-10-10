package top.niunaijun.blackbox.fake.service;

import android.os.IInterface;
import android.os.Process;
import android.os.storage.StorageVolume;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import black.android.os.mount.BRIMountServiceStub;
import black.android.os.storage.BRIStorageManagerStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class IStorageManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IStorageManagerProxy";

    public IStorageManagerProxy() {
        super(BRServiceManager.get().getService("mount"));
    }

    @Override
    protected Object getWho() {
        IInterface mount;
        if (BuildCompat.isOreo()) {
            mount = BRIStorageManagerStub.get().asInterface(BRServiceManager.get().getService("mount"));
        } else {
            mount = BRIMountServiceStub.get().asInterface(BRServiceManager.get().getService("mount"));
        }
        return mount;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("mount");
        try {
            replaceSystemService("storage");
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceAllAppPkg(args);
        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable t) {
            Throwable cause = t;
            if (cause instanceof InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof SecurityException) {
                Slog.w(TAG, "Suppressed SecurityException in IStorageManager." + method.getName() + ": " + cause.getMessage());
                Class<?> returnType = method.getReturnType();
                if (returnType == long.class || returnType == Long.class) {
                    return 1024L * 1024L * 1024L;
                } else if (returnType == int.class || returnType == Integer.class) {
                    return 0;
                } else if (returnType == boolean.class || returnType == Boolean.class) {
                    return false;
                }
                return null;
            }
            throw cause;
        }
    }

    @ProxyMethod("getVolumeList")
    public static class GetVolumeList extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args == null) {
                StorageVolume[] volumeList = BlackBoxCore.getBStorageManager().getVolumeList(BActivityThread.getBUid(), null, 0, BActivityThread.getUserId());
                if (volumeList == null) {
                    return method.invoke(who, args);
                }
                return volumeList;
            }
            try {
                int uid = (int) args[0];
                String packageName = (String) args[1];
                int flags = (int) args[2];
                StorageVolume[] volumeList = BlackBoxCore.getBStorageManager().getVolumeList(uid, packageName, flags, BActivityThread.getUserId());
                if (volumeList == null) {
                    return method.invoke(who, args);
                }
                return volumeList;
            } catch (Throwable t) {
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("mkdirs")
    public static class mkdirs extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("getCacheQuotaBytes")
    public static class GetCacheQuotaBytes extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length >= 2 && args[1] instanceof Integer) {
                    args[1] = Process.myUid();
                }
                return method.invoke(who, args);
            } catch (Throwable t) {
                return 1024L * 1024L * 1024L;
            }
        }
    }

    @ProxyMethod("getCacheSizeBytes")
    public static class GetCacheSizeBytes extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length >= 2 && args[1] instanceof Integer) {
                    args[1] = Process.myUid();
                }
                return method.invoke(who, args);
            } catch (Throwable t) {
                return 0L;
            }
        }
    }

    @ProxyMethod("getAllocatableBytes")
    public static class GetAllocatableBytes extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                MethodParameterUtils.replaceAllAppPkg(args);
                return method.invoke(who, args);
            } catch (Throwable t) {
                return 10L * 1024L * 1024L * 1024L;
            }
        }
    }

    @ProxyMethod("allocateBytes")
    public static class AllocateBytes extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                MethodParameterUtils.replaceAllAppPkg(args);
                return method.invoke(who, args);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
