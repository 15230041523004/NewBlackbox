package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.location.LocationManager;
import android.os.IInterface;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

import black.android.location.BRILocationListener;
import black.android.location.BRILocationManagerStub;
import black.android.location.provider.BRProviderProperties;
import black.android.location.provider.ProviderProperties;
import black.android.os.BRServiceManager;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import java.util.Collections;
import java.util.List;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.location.BLocation;
import top.niunaijun.blackbox.fake.frameworks.BLocationManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;


public class ILocationManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ILocationManagerProxy";

    public ILocationManagerProxy() {
        super(BRServiceManager.get().getService(Context.LOCATION_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRILocationManagerStub.get().asInterface(BRServiceManager.get().getService(Context.LOCATION_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (isLocationRead(method.getName())) {
            Object spoofed = spoofedLocationResult(method, args);
            if (spoofed != NO_SPOOF) return spoofed;
        }
        MethodParameterUtils.replaceFirstAppPkg(args);
        top.niunaijun.blackbox.utils.AttributionSourceUtils.fixAttributionSourceInArgs(args);

        String packageName = BActivityThread.getAppPackageName();
        if (packageName != null && packageName.equals("com.google.android.gms")) {
            if (method.getName().equals("getLastLocation") ||
                method.getName().equals("getLastKnownLocation") ||
                method.getName().equals("requestLocationUpdates")) {
                Log.w(TAG, "Blocking location request from Google Play Services to prevent crash");
                return null;
            }
        }

        return super.invoke(proxy, method, args);
    }

    private static final Object NO_SPOOF = new Object();

    private static boolean isLocationRead(String name) {
        return "getLastLocation".equals(name)
                || "getLastKnownLocation".equals(name)
                || "getCurrentLocation".equals(name)
                || "requestLocationUpdates".equals(name)
                || "registerLocationListener".equals(name);
    }

    /** Client package from the request, before it is rewritten to the host package. */
    private static String clientPackage(Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                if (arg == null || !arg.getClass().getName().contains("AttributionSource")) continue;
                String name = attributionPackage(arg);
                if (isGuestPackage(name)) return name;
            }
            for (Object arg : args) {
                if (arg instanceof String && isGuestPackage((String) arg)) return (String) arg;
            }
        }
        String process = BActivityThread.getAppPackageName();
        return isGuestPackage(process) ? process : null;
    }

    private static String attributionPackage(Object attributionSource) {
        try {
            Method getter = attributionSource.getClass().getMethod("getPackageName");
            Object name = getter.invoke(attributionSource);
            return name instanceof String ? (String) name : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isGuestPackage(String name) {
        if (name == null || !name.contains(".") || name.contains(" ") || name.contains("/")) return false;
        String host = BlackBoxCore.getHostPkg();
        return host == null || !host.equals(name);
    }

    private static Object spoofedLocationResult(Method method, Object[] args) {
        String pkg = clientPackage(args);
        if (pkg == null) return NO_SPOOF;
        int userId = BActivityThread.getUserId();
        if (BLocationManager.get().getPattern(userId, pkg) == BLocationManager.CLOSE_MODE) return NO_SPOOF;
        BLocation loc = BLocationManager.get().getLocation(userId, pkg);
        if (loc == null || loc.isEmpty()) return NO_SPOOF;
        String name = method.getName();
        if (name.startsWith("request") || name.startsWith("register") || "getCurrentLocation".equals(name)) {
            IBinder listener = listenerBinder(args);
            if (listener != null) {
                BLocationManager.get().requestLocationUpdates(listener, pkg);
            }
            if (args != null) {
                Location fix = loc.convert2SystemLocation();
                for (Object arg : args) {
                    if (arg != null && !(arg instanceof String) && !(arg instanceof Number) && !(arg instanceof Boolean)) {
                        notifyListener(arg, fix);
                    }
                }
            }
            Class<?> type = method.getReturnType();
            if (type == boolean.class || type == Boolean.class) return Boolean.TRUE;
            if (type == int.class || type == Integer.class) return 0;
            if (Location.class.isAssignableFrom(type)) return loc.convert2SystemLocation();
            return null;
        }
        if (Location.class.isAssignableFrom(method.getReturnType()) || method.getReturnType() == Object.class) {
            return loc.convert2SystemLocation();
        }
        return NO_SPOOF;
    }

    private static IBinder listenerBinder(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof IBinder) return (IBinder) arg;
            if (arg instanceof IInterface) return ((IInterface) arg).asBinder();
        }
        return null;
    }

    @ProxyMethod("registerGnssStatusCallback")
    public static class RegisterGnssStatusCallback extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    @ProxyMethod("getLastLocation")
    public static class GetLastLocation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                BLocation loc = BLocationManager.get().getLocation(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
                if (loc != null) return loc.convert2SystemLocation();
            }
            top.niunaijun.blackbox.utils.AttributionSourceUtils.fixAttributionSourceInArgs(args);
            MethodParameterUtils.replaceFirstAppPkg(args);
            try {
                Object loc = method.invoke(who, args);
                if (loc != null) return loc;
            } catch (Throwable t) {
                Log.d(TAG, "getLastLocation fallback: " + t.getMessage());
            }
            return createSafeFallbackLocation();
        }
    }

    @ProxyMethod("getLastKnownLocation")
    public static class GetLastKnownLocation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                BLocation loc = BLocationManager.get().getLocation(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
                if (loc != null) return loc.convert2SystemLocation();
            }
            top.niunaijun.blackbox.utils.AttributionSourceUtils.fixAttributionSourceInArgs(args);
            MethodParameterUtils.replaceFirstAppPkg(args);
            try {
                Object loc = method.invoke(who, args);
                if (loc != null) return loc;
            } catch (Throwable t) {
                Log.d(TAG, "getLastKnownLocation fallback: " + t.getMessage());
            }
            return createSafeFallbackLocation();
        }
    }

    @ProxyMethod("getCurrentLocation")
    public static class GetCurrentLocation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                BLocation loc = BLocationManager.get().getLocation(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
                if (loc != null) return loc.convert2SystemLocation();
            }
            top.niunaijun.blackbox.utils.AttributionSourceUtils.fixAttributionSourceInArgs(args);
            MethodParameterUtils.replaceFirstAppPkg(args);
            try {
                Object loc = method.invoke(who, args);
                if (loc != null) return loc;
            } catch (Throwable t) {
                Log.d(TAG, "getCurrentLocation fallback: " + t.getMessage());
            }
            return createSafeFallbackLocation();
        }
    }

    @ProxyMethod("registerLocationListener")
    public static class RegisterLocationListener extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            top.niunaijun.blackbox.utils.AttributionSourceUtils.fixAttributionSourceInArgs(args);
            MethodParameterUtils.replaceFirstAppPkg(args);
            scheduleFallbackLocationCallback(args);
            try {
                return method.invoke(who, args);
            } catch (Throwable t) {
                Log.d(TAG, "registerLocationListener suppressed: " + t.getMessage());
                return null;
            }
        }
    }

    @ProxyMethod("requestLocationUpdates")
    public static class RequestLocationUpdates extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                if (args[1] instanceof IInterface) {
                    IInterface listener = (IInterface) args[1];
                    BLocationManager.get().requestLocationUpdates(listener.asBinder());
                    return 0;
                }
            }
            top.niunaijun.blackbox.utils.AttributionSourceUtils.fixAttributionSourceInArgs(args);
            MethodParameterUtils.replaceFirstAppPkg(args);
            scheduleFallbackLocationCallback(args);
            try {
                return method.invoke(who, args);
            } catch (Throwable t) {
                Log.d(TAG, "requestLocationUpdates suppressed: " + t.getMessage());
                return 0;
            }
        }
    }

    @ProxyMethod("removeUpdates")
    public static class RemoveUpdates extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] instanceof IInterface) {
                IInterface listener = (IInterface) args[0];
                BLocationManager.get().removeUpdates(listener.asBinder());
                return 0;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getProviderProperties")
    public static class GetProviderProperties extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Object providerProperties = method.invoke(who, args);
                if (providerProperties != null && BLocationManager.isFakeLocationEnable()) {
                    BRProviderProperties.get(providerProperties)._set_mHasNetworkRequirement(false);
                    if (BLocationManager.get().getCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName()) == null) {
                        BRProviderProperties.get(providerProperties)._set_mHasCellRequirement(false);
                    }
                }
                return providerProperties;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    @ProxyMethod("removeGpsStatusListener")
    public static class RemoveGpsStatusListener extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("getBestProvider")
    public static class GetBestProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return LocationManager.GPS_PROVIDER;
        }
    }

    @ProxyMethod("getAllProviders")
    public static class GetAllProviders extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return Arrays.asList(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER);
        }
    }

    @ProxyMethod("isProviderEnabledForUser")
    public static class isProviderEnabledForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    @ProxyMethod("isProviderEnabled")
    public static class IsProviderEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    @ProxyMethod("isLocationEnabledForUser")
    public static class IsLocationEnabledForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    @ProxyMethod("isLocationEnabled")
    public static class IsLocationEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    @ProxyMethod("setExtraLocationControllerPackageEnabled")
    public static class setExtraLocationControllerPackageEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    public static Location createSafeFallbackLocation() {
        if (BLocationManager.isFakeLocationEnable()) {
            try {
                BLocation loc = BLocationManager.get().getLocation(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
                if (loc != null && !loc.isEmpty()) {
                    return loc.convert2SystemLocation();
                }
            } catch (Throwable ignored) {
            }
        }
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(55.751244);
        location.setLongitude(37.618423);
        location.setAltitude(150.0);
        location.setSpeed(0.0f);
        location.setBearing(0.0f);
        location.setAccuracy(15.0f);
        location.setTime(System.currentTimeMillis());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        }
        Bundle extras = new Bundle();
        extras.putInt("satellites", 12);
        location.setExtras(extras);
        return location;
    }

    private static void scheduleFallbackLocationCallback(final Object[] args) {
        if (args == null) return;
        for (final Object arg : args) {
            if (arg != null && (arg instanceof IInterface || arg instanceof IBinder)) {
                BlackBoxCore.get().getHandler().postDelayed(() -> {
                    try {
                        notifyListener(arg, createSafeFallbackLocation());
                    } catch (Throwable ignored) {
                    }
                }, 150);
            }
        }
    }

    private static void notifyListener(Object listener, Location location) {
        if (listener == null || location == null) return;
        try {
            if (listener instanceof IBinder) {
                try {
                    Class<?> stubClass = Class.forName("android.location.ILocationListener$Stub");
                    Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                    listener = asInterface.invoke(null, listener);
                } catch (Throwable ignored) {
                }
            }
            if (listener == null) return;
            for (Method m : listener.getClass().getMethods()) {
                if (m.getName().equals("onLocationChanged")) {
                    Class<?>[] pTypes = m.getParameterTypes();
                    if (pTypes.length == 1 && pTypes[0] == Location.class) {
                        m.invoke(listener, location);
                        return;
                    } else if (pTypes.length == 2 && pTypes[0] == Location.class) {
                        m.invoke(listener, location, null);
                        return;
                    } else if (pTypes.length >= 1 && List.class.isAssignableFrom(pTypes[0])) {
                        if (pTypes.length == 1) {
                            m.invoke(listener, Collections.singletonList(location));
                        } else {
                            m.invoke(listener, Collections.singletonList(location), null);
                        }
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "notifyListener error: " + t.getMessage());
        }
    }
}
