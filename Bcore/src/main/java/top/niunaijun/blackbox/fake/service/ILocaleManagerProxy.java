package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.LocaleList;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

public class ILocaleManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ILocaleManagerProxy";

    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService("locale"));
    }

    @Override
    protected Object getWho() {
        try {
            IBinder binder = BRServiceManager.get().getService("locale");
            if (binder == null) return null;
            Class<?> stubClass = Class.forName("android.app.ILocaleManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, binder);
        } catch (Exception e) {
            Slog.d(TAG, "getWho error: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRServiceManager.get().getService("locale") != null) {
            replaceSystemService("locale");
            Slog.d(TAG, "Hooked LocaleManagerService");
        } else {
            Slog.d(TAG, "Skipping LocaleManagerService hook (service not found)");
        }
    }

    @Override
    public boolean isBadEnv() {
        IBinder binder = BRServiceManager.get().getService("locale");
        return binder != null && binder != this;
    }

    @ProxyMethod("getApplicationLocales")
    public static class GetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    args[0] = BlackBoxCore.getHostPkg();
                }
                return method.invoke(who, args);
            } catch (Throwable t) {
                Slog.w(TAG, "getApplicationLocales fallback: " + t.getMessage());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    return LocaleList.getEmptyLocaleList();
                }
                return null;
            }
        }
    }

    @ProxyMethod("setApplicationLocales")
    public static class SetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    args[0] = BlackBoxCore.getHostPkg();
                }
                return method.invoke(who, args);
            } catch (Throwable t) {
                Slog.w(TAG, "setApplicationLocales ignored: " + t.getMessage());
                return null;
            }
        }
    }
}

