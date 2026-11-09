package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.AttributionSourceUtils;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;

public class ITelecomManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ITelecomManagerProxy";

    private static final Map<Object, Object> sPhoneAccounts = new ConcurrentHashMap<>();

    public ITelecomManagerProxy() {
        super(BRServiceManager.get().getService(Context.TELECOM_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder telecom = BRServiceManager.get().getService(Context.TELECOM_SERVICE);
        if (telecom == null) {
            Slog.e(TAG, "Failed to get TELECOM_SERVICE binder");
            return null;
        }
        try {
            Class<?> stubClass = Class.forName("com.android.internal.telecom.ITelecomService$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, telecom);
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to get ITelecomService stub", t);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.TELECOM_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceAllAppPkg(args);
        AttributionSourceUtils.fixAttributionSourceInArgs(args);
        try {
            return super.invoke(proxy, method, args);
        } catch (SecurityException se) {
            Slog.w(TAG, "Suppressed SecurityException in Telecom method " + method.getName() + ": " + se.getMessage());
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == List.class) return new ArrayList<>();
            return null;
        }
    }

    @ProxyMethod("registerPhoneAccount")
    public static class RegisterPhoneAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "registerPhoneAccount intercepted and absorbed for sandbox safety");
            if (args != null && args.length > 0 && args[0] != null) {
                try {
                    Object phoneAccount = args[0];
                    Method getAccountHandle = phoneAccount.getClass().getMethod("getAccountHandle");
                    Object handle = getAccountHandle.invoke(phoneAccount);
                    if (handle != null) {
                        sPhoneAccounts.put(handle, phoneAccount);
                    }
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    @ProxyMethod("unregisterPhoneAccount")
    public static class UnregisterPhoneAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "unregisterPhoneAccount intercepted");
            if (args != null && args.length > 0 && args[0] != null) {
                sPhoneAccounts.remove(args[0]);
            }
            return null;
        }
    }

    @ProxyMethod("getPhoneAccount")
    public static class GetPhoneAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] != null) {
                Object cached = sPhoneAccounts.get(args[0]);
                if (cached != null) return cached;
            }
            try {
                return method.invoke(who, args);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    @ProxyMethod("getCallCapablePhoneAccounts")
    public static class GetCallCapablePhoneAccounts extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<>();
        }
    }

    @ProxyMethod("getSelfManagedPhoneAccounts")
    public static class GetSelfManagedPhoneAccounts extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<>(sPhoneAccounts.keySet());
        }
    }

    @ProxyMethod("getAllPhoneAccounts")
    public static class GetAllPhoneAccounts extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<>(sPhoneAccounts.values());
        }
    }

    @ProxyMethod("getAllPhoneAccountHandles")
    public static class GetAllPhoneAccountHandles extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<>(sPhoneAccounts.keySet());
        }
    }

    @ProxyMethod("isOutgoingCallPermitted")
    public static class IsOutgoingCallPermitted extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "isOutgoingCallPermitted returning false so guest VoIP app falls back to standalone call engine");
            return false;
        }
    }

    @ProxyMethod("isIncomingCallPermitted")
    public static class IsIncomingCallPermitted extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "isIncomingCallPermitted returning false so guest VoIP app falls back to standalone call engine");
            return false;
        }
    }

    @ProxyMethod("placeCall")
    public static class PlaceCall extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "placeCall intercepted");
            return null;
        }
    }

    @ProxyMethod("clearAccounts")
    public static class ClearAccounts extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            sPhoneAccounts.clear();
            return null;
        }
    }
}
