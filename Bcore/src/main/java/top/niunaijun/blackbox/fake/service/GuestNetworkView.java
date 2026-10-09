package top.niunaijun.blackbox.fake.service;

import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Slog;

/** Guest-visible network metadata only. Network handles and socket routing stay real. */
public final class GuestNetworkView {
    private static final String TAG = "GuestNetworkView";
    private static HandlerThread callbacks;

    private GuestNetworkView() {}

    public static boolean isGuest() {
        return BActivityThread.isThreadInit() && BActivityThread.getAppConfig() != null;
    }

    public static boolean isTunnel(String name) {
        if (name == null) return false;
        return name.matches("(?i)(tun|tap|ppp|pptp|wg|ipsec|vpn)[0-9_.-]*")
                || name.startsWith("utun") || name.startsWith("wireguard");
    }

    public static NetworkCapabilities capabilities(NetworkCapabilities source) {
        if (source == null || !source.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return source;
        NetworkCapabilities copy = new NetworkCapabilities(source);
        try {
            Class<?> type = Class.forName("android.net.NetworkCapabilities$Builder");
            Object builder = type.getConstructor(NetworkCapabilities.class).newInstance(source);
            type.getMethod("removeTransportType", int.class).invoke(builder, NetworkCapabilities.TRANSPORT_VPN);
            type.getMethod("addCapability", int.class).invoke(builder, NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
            copy = (NetworkCapabilities) type.getMethod("build").invoke(builder);
        } catch (Throwable unavailable) {
            // Older Android versions do not have the public Builder.
            set(copy, "mTransportTypes", number(copy, "mTransportTypes") & ~(1L << NetworkCapabilities.TRANSPORT_VPN));
            set(copy, "mNetworkCapabilities", number(copy, "mNetworkCapabilities") | (1L << NetworkCapabilities.NET_CAPABILITY_NOT_VPN));
        }
        // VpnTransportInfo/session name and underlying network list also identify the tunnel.
        set(copy, "mTransportInfo", null);
        set(copy, "mUnderlyingNetworks", null);
        set(copy, "mOwnerUid", -1);
        set(copy, "mAdministratorUids", new int[0]);
        set(copy, "mUids", null);
        if (copy.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                || !copy.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            throw new IllegalStateException("VPN capabilities were not redacted");
        }
        Slog.d(TAG, "redacted VPN capabilities; real network handle preserved");
        return copy;
    }

    private static long number(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return ((Number) field.get(target)).longValue(); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(name, e); }
    }

    private static void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException e) { Slog.w(TAG, "cannot redact " + name + ": " + e.getMessage()); }
    }

    private static Object query(Object service, String name, Object first) throws Exception {
        if (service == null) return null;
        for (Method method : service.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!method.getName().equals(name) || types.length < 1 || !types[0].isInstance(first)) continue;
            Object[] args = new Object[types.length]; args[0] = first;
            for (int i = 1; i < types.length; i++) {
                if (types[i] == String.class) args[i] = i == 1 ? BlackBoxCore.getHostPkg() : null;
                else if (types[i] == boolean.class) args[i] = false;
                else if (types[i] == int.class) args[i] = BlackBoxCore.getHostUid();
            }
            return method.invoke(service, args);
        }
        return null;
    }

    public static LinkProperties linkProperties(Object service, LinkProperties source) {
        if (source == null || !isTunnel(source.getInterfaceName())) return source;
        // Use real physical link information, never a made-up Network that breaks binding/DNS.
        try {
            for (Method method : service.getClass().getMethods()) {
                if (!method.getName().equals("getAllNetworks") || method.getParameterTypes().length != 0) continue;
                Network[] networks = (Network[]) method.invoke(service);
                if (networks == null) break;
                for (Network network : networks) {
                    NetworkCapabilities caps = (NetworkCapabilities) query(service, "getNetworkCapabilities", network);
                    if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                            || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                    LinkProperties physical = (LinkProperties) query(service, "getLinkProperties", network);
                    if (physical != null && !isTunnel(physical.getInterfaceName())) return physical;
                }
                break;
            }
        } catch (Exception e) { Slog.w(TAG, "physical link query: " + e.getMessage()); }
        // No physical link available during a transition: expose an empty snapshot, not tun routes.
        return new LinkProperties();
    }

    public static Object result(Object service, Object result) {
        if (result instanceof NetworkCapabilities) return capabilities((NetworkCapabilities) result);
        if (result instanceof LinkProperties) return linkProperties(service, (LinkProperties) result);
        if (result instanceof NetworkInfo && ((NetworkInfo) result).getType() == 17) {
            NetworkInfo original = (NetworkInfo) result;
            NetworkInfo copy = new NetworkInfo(1, 0, "WIFI", "");
            set(copy, "mIsAvailable", original.isAvailable());
            copy.setDetailedState(original.getDetailedState(), null, null);
            return copy;
        }
        if (result instanceof NetworkInfo[]) {
            NetworkInfo[] copy = ((NetworkInfo[]) result).clone();
            for (int i = 0; i < copy.length; i++) copy[i] = (NetworkInfo) result(service, copy[i]);
            return copy;
        }
        return result;
    }

    public static Bundle callbackData(Object service, Bundle original) {
        Bundle copy = new Bundle(original);
        copy.setClassLoader(NetworkCapabilities.class.getClassLoader());
        for (String key : copy.keySet()) {
            Object value = copy.get(key);
            Object redacted = result(service, value);
            if (value != redacted && redacted instanceof android.os.Parcelable) {
                copy.putParcelable(key, (android.os.Parcelable) redacted);
            }
        }
        return copy;
    }

    public static void wrapCallbacks(Object service, Object[] args) {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof Messenger)) continue;
            final Messenger destination = (Messenger) args[i];
            synchronized (GuestNetworkView.class) {
                if (callbacks == null) {
                    callbacks = new HandlerThread("GuestNetworkCallbacks"); callbacks.start();
                }
            }
            args[i] = new Messenger(new Handler(callbacks.getLooper()) {
                @Override public void handleMessage(Message incoming) {
                    Message outgoing = Message.obtain(incoming);
                    try {
                        Bundle data = incoming.peekData();
                        if (data != null) outgoing.setData(callbackData(service, data));
                        destination.send(outgoing);
                    } catch (RemoteException e) {
                        Slog.d(TAG, "network callback receiver stopped");
                    } catch (Throwable e) {
                        Slog.w(TAG, "network callback redaction failed: " + e.getMessage());
                    }
                }
            });
        }
    }
}
