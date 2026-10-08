package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Proxy for android.permission.IPermissionChecker ("permission_checker") service.
 * Used on Android 12+ (API 31+) by PermissionChecker and system frameworks.
 */
public class IPermissionCheckerProxy extends BinderInvocationStub {
    public static final String TAG = "IPermissionCheckerProxy";
    private static final String SERVICE_NAME = "permission_checker";

    public IPermissionCheckerProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        IBinder service = BRServiceManager.get().getService(SERVICE_NAME);
        if (service == null) {
            return null;
        }
        try {
            Class<?> stubClass = Class.forName("android.permission.IPermissionChecker$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, service);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to resolve IPermissionChecker: " + t.getMessage());
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE_NAME);
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new CheckPermission());
        addMethodHook(new CheckOp());
        addMethodHook(new FinishDataDelivery());
    }

    @ProxyMethod("checkPermission")
    public static class CheckPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0; // PERMISSION_GRANTED
        }
    }

    @ProxyMethod("checkOp")
    public static class CheckOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0; // PERMISSION_GRANTED
        }
    }

    @ProxyMethod("finishDataDelivery")
    public static class FinishDataDelivery extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}

