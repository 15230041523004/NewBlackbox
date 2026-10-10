package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.os.IInterface;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import black.android.net.wifi.BRIWifiManagerStub;
import black.android.net.wifi.BRWifiInfo;
import black.android.net.wifi.BRWifiSsid;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.GuestFingerprint;
import top.niunaijun.blackbox.utils.GuestWifiScan;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;


public class IWifiManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IWifiManagerProxy";

    public IWifiManagerProxy() {
        super(BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIWifiManagerStub.get().asInterface(BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getConnectionInfo")
    public static class GetConnectionInfo extends MethodHook {
        
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // WifiService validates callingPackage against the real Binder UID.
            // Keep the attribution tag intact and support older no-argument signatures.
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (args != null && args.length > 0 && parameterTypes.length > 0
                    && parameterTypes[0] == String.class) {
                args[0] = BlackBoxCore.getHostPkg();
            }
            String pkg = BActivityThread.getAppPackageName();
            boolean wifiSpoof = GuestWifiScan.enabled(pkg);
            WifiInfo wifiInfo = (WifiInfo) method.invoke(who, args);
            if (wifiInfo == null) {
                return null;
            }
            GuestFingerprint fp = GuestFingerprint.get();
            String mac = fp.active && fp.mac.length() > 0 ? fp.mac : "ac:62:5a:82:65:c4";
            String ssid = fp.active ? "AndroidAP" : "BlackBox_Wifi";
            BRWifiInfo.get(wifiInfo)._set_mMacAddress(mac);
            GuestWifiScan.Ap ap = wifiSpoof ? GuestWifiScan.strongest(pkg) : null;
            if (ap != null) {
                BRWifiInfo.get(wifiInfo)._set_mBSSID(ap.bssid);
                BRWifiInfo.get(wifiInfo)._set_mWifiSsid(BRWifiSsid.get().createFromAsciiEncoded(ap.ssid));
                BRWifiInfo.get(wifiInfo)._set_mRssi(ap.level(SystemClock.elapsedRealtime()));
                BRWifiInfo.get(wifiInfo)._set_mFrequency(ap.frequency);
                BRWifiInfo.get(wifiInfo)._set_mSupplicantState(SupplicantState.COMPLETED);
            } else {
                BRWifiInfo.get(wifiInfo)._set_mBSSID(mac);
                BRWifiInfo.get(wifiInfo)._set_mWifiSsid(BRWifiSsid.get().createFromAsciiEncoded(ssid));
            }
            return wifiInfo;
        }

        public static String intIP2StringIP(int ip) {
            return (ip & 0xFF) + "." +
                    ((ip >> 8) & 0xFF) + "." +
                    ((ip >> 16) & 0xFF) + "." +
                    (ip >> 24 & 0xFF);
        }

        public static int ip2Int(String ipString) {
            
            String[] ipSlices = ipString.split("\\.");
            int rs = 0;
            for (int i = 0; i < ipSlices.length; i++) {
                
                int intSlice = Integer.parseInt(ipSlices[i]) << 8 * i;
                
                rs = rs | intSlice;
            }
            return rs;
        }
    }

    @ProxyMethod("getScanResults")
    public static class GetScanResults extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String pkg = BActivityThread.getAppPackageName();
            if (!GuestWifiScan.enabled(pkg)) return method.invoke(who, args);
            // Android 16 IWifiManager returns com.android.modules.utils.ParceledListSlice,
            // not List. Falling through calls the real WifiService, which returns nothing
            // while location mode is off.
            List<?> results = GuestWifiScan.scanResults(pkg);
            Class<?> type = method.getReturnType();
            if (type != null && type.getName().endsWith("ParceledListSlice")) {
                return scanSlice(type, results);
            }
            return results;
        }
    }

    @ProxyMethod("startScan")
    public static class StartScan extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String pkg = BActivityThread.getAppPackageName();
            if (!GuestWifiScan.enabled(pkg)) return method.invoke(who, args);
            BlackBoxCore.get().getHandler().post(new Runnable() {
                @Override
                public void run() {
                    GuestWifiListeners.notifyScanAvailable();
                }
            });
            Class<?> type = method.getReturnType();
            if (type == boolean.class || type == Boolean.class) return Boolean.TRUE;
            return null;
        }
    }

    @ProxyMethod("registerScanResultsCallback")
    public static class RegisterScanResultsCallback extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String pkg = BActivityThread.getAppPackageName();
            if (!GuestWifiScan.enabled(pkg)) return method.invoke(who, args);
            Object callback = lastInterface(args);
            if (callback != null) GuestWifiListeners.addCallback(callback);
            return voidOrTrue(method);
        }
    }

    @ProxyMethod("unregisterScanResultsCallback")
    public static class UnregisterScanResultsCallback extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String pkg = BActivityThread.getAppPackageName();
            Object callback = lastInterface(args);
            if (callback != null) GuestWifiListeners.removeCallback(callback);
            if (!GuestWifiScan.enabled(pkg)) return method.invoke(who, args);
            return voidOrTrue(method);
        }
    }

    /** In-process slice. WifiManager only calls getList(), so the list is not parcelled. */
    private static Object scanSlice(Class<?> type, List<?> results) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor(List.class);
            ctor.setAccessible(true);
            return ctor.newInstance(results);
        } catch (Throwable first) {
            try {
                Constructor<?> empty = type.getDeclaredConstructor();
                empty.setAccessible(true);
                Object slice = empty.newInstance();
                Method append = type.getMethod("append", Object.class);
                for (Object item : results) append.invoke(slice, item);
                type.getMethod("setLastSlice", boolean.class).invoke(slice, Boolean.TRUE);
                return slice;
            } catch (Throwable second) {
                Log.e(TAG, "scan slice " + type.getName(), second);
                return ParceledListSliceCompat.create(results);
            }
        }
    }

    private static Object lastInterface(Object[] args) {
        if (args == null) return null;
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof IInterface) return args[i];
        }
        return null;
    }

    private static Object voidOrTrue(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class || type == Boolean.class) return Boolean.TRUE;
        if (type == int.class || type == Integer.class) return 0;
        return null;
    }
}
