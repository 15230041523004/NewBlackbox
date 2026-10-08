package top.niunaijun.blackbox.fake.service;

import android.content.pm.PackageManager;

import java.lang.reflect.Method;
import java.util.Arrays;

import black.android.app.BRActivityThread;
import black.android.app.BRContextImpl;
import black.android.os.BRServiceManager;
import black.android.permission.BRIPermissionManagerStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class IPermissionManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IPermissionManagerProxy";

    private static final String P = "permissionmgr";

    public IPermissionManagerProxy() {
        super(BRServiceManager.get().getService(P));
    }

    @Override
    protected Object getWho() {
        return BRIPermissionManagerStub.get().asInterface(BRServiceManager.get().getService(P));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("permissionmgr");
        replaceSystemService("permission");
        try {
            BRActivityThread.getWithException()._set_sPermissionManager(proxyInvocation);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to set sPermissionManager via BlackReflection: " + t.getMessage());
        }
        try {
            Reflector.on("android.app.ActivityThread")
                    .field("sPermissionManager")
                    .set(proxyInvocation);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addPermissionAsync", true));
        addMethodHook(new ValueMethodProxy("addPermission", true));
        addMethodHook(new ValueMethodProxy("performDexOpt", true));
        addMethodHook(new ValueMethodProxy("performDexOptIfNeeded", false));
        addMethodHook(new ValueMethodProxy("performDexOptSecondary", true));
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("checkDeviceIdentifierAccess", false));
        addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));
        addMethodHook(new CheckPermission());
        addMethodHook(new CheckUidPermission());
        addMethodHook(new CheckPackageNamePermission());
        addMethodHook(new IsPermissionRevokedByPolicy());
        if (BuildCompat.isOreo()) {
            addMethodHook(new ValueMethodProxy("notifyDexLoad", 0));
            addMethodHook(new ValueMethodProxy("notifyPackageUse", 0));
            addMethodHook(new ValueMethodProxy("setInstantAppCookie", false));
            addMethodHook(new ValueMethodProxy("isInstantApp", false));
        }
    }

    @ProxyMethod("checkPermission")
    public static class CheckPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "IPermissionManager checkPermission: " + Arrays.toString(args));
            return PackageManager.PERMISSION_GRANTED;
        }
    }

    @ProxyMethod("checkUidPermission")
    public static class CheckUidPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "IPermissionManager checkUidPermission: " + Arrays.toString(args));
            return PackageManager.PERMISSION_GRANTED;
        }
    }

    @ProxyMethod("checkPackageNamePermission")
    public static class CheckPackageNamePermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "IPermissionManager checkPackageNamePermission: " + Arrays.toString(args));
            return PackageManager.PERMISSION_GRANTED;
        }
    }

    @ProxyMethod("isPermissionRevokedByPolicy")
    public static class IsPermissionRevokedByPolicy extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
