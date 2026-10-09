package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;

import top.niunaijun.blackbox.utils.ArtMethodHook;
import top.niunaijun.blackbox.utils.Slog;

/** Installed for every bound guest, independently of camera/microphone permissions. */
public final class GuestNetworkHooks {
    private static boolean installed;
    private static Method enumeration, byName, byIndex, byAddress;

    private GuestNetworkHooks() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        enumeration = hook("getNetworkInterfaces");
        byName = hook("getByName", String.class);
        byIndex = hook("getByIndex", int.class);
        byAddress = hook("getByInetAddress", InetAddress.class);
    }

    private static Method hook(String name, Class<?>... parameters) {
        try {
            Method original = NetworkInterface.class.getDeclaredMethod(name, parameters);
            Method replacement = Hooks.class.getDeclaredMethod(name, parameters);
            Method backup = Backups.class.getDeclaredMethod(name, parameters);
            if (!ArtMethodHook.hook(original, replacement, backup)) throw new IllegalStateException("ART redirect failed");
            Slog.i("GuestNetworkHooks", "hooked NetworkInterface." + name);
            return backup;
        } catch (Throwable e) { Slog.w("GuestNetworkHooks", name + ": " + e.getMessage()); return null; }
    }

    private static Object call(Method method, Object... args) throws SocketException {
        try { return ArtMethodHook.call(method, null, args); }
        catch (SocketException e) { throw e; }
        catch (RuntimeException e) { throw e; }
        catch (Error e) { throw e; }
        catch (Throwable e) { throw new SocketException(e.toString()); }
    }

    private static NetworkInterface visible(NetworkInterface value) {
        return value != null && GuestNetworkView.isGuest() && GuestNetworkView.isTunnel(value.getName()) ? null : value;
    }

    public static final class Hooks {
        @SuppressWarnings("unchecked")
        public static Enumeration<NetworkInterface> getNetworkInterfaces() throws SocketException {
            Enumeration<NetworkInterface> all = (Enumeration<NetworkInterface>) call(enumeration);
            if (all == null || !GuestNetworkView.isGuest()) return all;
            ArrayList<NetworkInterface> visible = new ArrayList<>();
            while (all.hasMoreElements()) {
                NetworkInterface value = all.nextElement();
                if (!GuestNetworkView.isTunnel(value.getName())) visible.add(value);
            }
            return Collections.enumeration(visible);
        }
        public static NetworkInterface getByName(String name) throws SocketException { return visible((NetworkInterface) call(byName, name)); }
        public static NetworkInterface getByIndex(int index) throws SocketException { return visible((NetworkInterface) call(byIndex, index)); }
        public static NetworkInterface getByInetAddress(InetAddress address) throws SocketException { return visible((NetworkInterface) call(byAddress, address)); }
    }

    public static final class Backups {
        public static Enumeration<NetworkInterface> getNetworkInterfaces() throws SocketException { return null; }
        public static NetworkInterface getByName(String name) throws SocketException { return null; }
        public static NetworkInterface getByIndex(int index) throws SocketException { return null; }
        public static NetworkInterface getByInetAddress(InetAddress address) throws SocketException { return null; }
    }
}
