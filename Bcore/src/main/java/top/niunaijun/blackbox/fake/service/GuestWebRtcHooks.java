package top.niunaijun.blackbox.fake.service;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.ArtMethodHook;
import top.niunaijun.blackbox.utils.Slog;

public final class GuestWebRtcHooks {
    private static final String TAG = "GuestWebRtcHooks";
    private static Class<?> sNetworkMonitorClass;
    private static Method sStartMonitoringBackup;
    private static boolean sInstalled;

    private GuestWebRtcHooks() {}

    public static synchronized void install(Application app) {
        if (sInstalled || app == null) return;
        sInstalled = true;
        try {
            ClassLoader cl = app.getClassLoader();
            Class<?> netMonClass = null;
            try {
                netMonClass = cl.loadClass("org.webrtc.NetworkMonitor");
            } catch (ClassNotFoundException e) {
                Slog.d(TAG, "org.webrtc.NetworkMonitor not present in application classloader");
                return;
            }

            sNetworkMonitorClass = netMonClass;

            Method target = null;
            for (Method m : netMonClass.getDeclaredMethods()) {
                if (m.getName().equals("startMonitoring")) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 3 && Context.class.isAssignableFrom(p[0]) && p[1] == long.class && p[2] == String.class) {
                        target = m;
                        break;
                    }
                }
            }

            if (target == null) {
                Slog.w(TAG, "startMonitoring method not found on " + netMonClass.getName());
                return;
            }

            Method replacement = Hook.class.getDeclaredMethod("startMonitoring", Context.class, long.class, String.class);
            Method backup = Backup.class.getDeclaredMethod("startMonitoring", Context.class, long.class, String.class);

            if (!ArtMethodHook.hook(target, replacement, backup)) {
                Slog.w(TAG, "Failed to hook NetworkMonitor.startMonitoring with ArtMethodHook");
                return;
            }

            sStartMonitoringBackup = backup;
            Slog.i(TAG, "WebRTC NetworkMonitor.startMonitoring successfully hooked!");
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to install WebRTC hooks", t);
        }
    }

    public static void onStartMonitoring(Object networkMonitor, Context context, long nativeObserver, String fieldTrials) {
        Slog.i(TAG, "onStartMonitoring called with nativeObserver=" + nativeObserver + ", fieldTrials=" + fieldTrials);
        if (fieldTrials == null || fieldTrials.isEmpty()) {
            fieldTrials = "getAllNetworksFromCache:false includeOtherUidNetworks:true";
        } else if (!fieldTrials.contains("getAllNetworksFromCache:false")) {
            fieldTrials = fieldTrials + " getAllNetworksFromCache:false includeOtherUidNetworks:true";
        }

        try {
            ArtMethodHook.call(sStartMonitoringBackup, networkMonitor, context, nativeObserver, fieldTrials);
        } catch (Throwable t) {
            Slog.w(TAG, "Error in startMonitoring backup call", t);
        }

        ensureNetworksDelivered(networkMonitor, nativeObserver, context);
    }

    private static void ensureNetworksDelivered(Object networkMonitor, long nativeObserver, Context context) {
        try {
            if (networkMonitor == null) return;
            if (context == null) {
                context = BlackBoxCore.getContext();
            }
            Class<?> netMonClass = networkMonitor.getClass();
            ClassLoader cl = netMonClass.getClassLoader();

            // Locate detector field in NetworkMonitor (extends BroadcastReceiver)
            Object detector = null;
            for (Field f : netMonClass.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(networkMonitor);
                if (val instanceof android.content.BroadcastReceiver) {
                    detector = val;
                    break;
                }
            }

            Network hostActiveNetwork = null;
            Network[] hostAllNetworks = null;
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    hostActiveNetwork = cm.getActiveNetwork();
                    hostAllNetworks = cm.getAllNetworks();
                }
            } catch (Throwable ignored) {
            }

            List<Object> networkInfos = new ArrayList<>();

            if (detector != null) {
                Slog.i(TAG, "Found WebRTC detector: " + detector.getClass().getName());

                // Find ConnectivityManager wrapper inside detector
                Object cmWrapper = null;
                for (Field df : detector.getClass().getDeclaredFields()) {
                    df.setAccessible(true);
                    Object dVal = df.get(detector);
                    if (dVal != null && !dVal.getClass().getName().startsWith("java.") && !dVal.getClass().getName().startsWith("android.")) {
                        for (Field xf : dVal.getClass().getDeclaredFields()) {
                            if (ConnectivityManager.class.isAssignableFrom(xf.getType())) {
                                cmWrapper = dVal;
                                break;
                            }
                        }
                        if (cmWrapper != null) break;
                    }
                }

                if (cmWrapper != null) {
                    Slog.i(TAG, "Found detector ConnectivityManager wrapper: " + cmWrapper.getClass().getName());
                    for (Field xf : cmWrapper.getClass().getDeclaredFields()) {
                        xf.setAccessible(true);
                        if (xf.getType() == boolean.class) {
                            xf.setBoolean(cmWrapper, false);
                        } else if (Collection.class.isAssignableFrom(xf.getType())) {
                            @SuppressWarnings("unchecked")
                            Collection<Network> col = (Collection<Network>) xf.get(cmWrapper);
                            if (col != null) {
                                if (hostActiveNetwork != null) col.add(hostActiveNetwork);
                                if (hostAllNetworks != null) {
                                    for (Network n : hostAllNetworks) {
                                        if (n != null) col.add(n);
                                    }
                                }
                                Slog.i(TAG, "Pre-populated detector network cache with " + col.size() + " networks");
                            }
                        }
                    }
                }

                // Call detector method returning List to get active networks
                Method methodA = null;
                for (Method dm : detector.getClass().getDeclaredMethods()) {
                    if (List.class.isAssignableFrom(dm.getReturnType()) && dm.getParameterTypes().length == 0) {
                        methodA = dm;
                        break;
                    }
                }

                if (methodA != null) {
                    methodA.setAccessible(true);
                    List<?> list = (List<?>) methodA.invoke(detector);
                    if (list != null && !list.isEmpty()) {
                        for (Object item : list) {
                            if (item != null) networkInfos.add(item);
                        }
                        Slog.i(TAG, "Detector returned " + networkInfos.size() + " active network(s)");
                    } else {
                        Slog.w(TAG, "Detector returned 0 active networks, using synthetic construction");
                    }
                }
            }

            // If detector returned 0 networks, build synthetic NetworkInformation
            if (networkInfos.isEmpty() && cl != null) {
                Object synth = createSyntheticNetworkInformation(cl, hostActiveNetwork);
                if (synth != null) {
                    networkInfos.add(synth);
                    Slog.i(TAG, "Added synthetic NetworkInformation successfully");
                }
            }

            if (!networkInfos.isEmpty()) {
                Method notifyListMethod = null;
                Method notifyConnMethod = null;
                for (Method m : netMonClass.getDeclaredMethods()) {
                    if (m.getName().equals("nativeNotifyOfActiveNetworkList")) {
                        notifyListMethod = m;
                    } else if (m.getName().equals("nativeNotifyConnectionTypeChanged")) {
                        notifyConnMethod = m;
                    }
                }

                if (notifyListMethod != null) {
                    notifyListMethod.setAccessible(true);
                    Object arr = Array.newInstance(networkInfos.get(0).getClass(), networkInfos.size());
                    for (int i = 0; i < networkInfos.size(); i++) {
                        Array.set(arr, i, networkInfos.get(i));
                    }
                    notifyListMethod.invoke(networkMonitor, nativeObserver, arr);
                    Slog.i(TAG, "Called nativeNotifyOfActiveNetworkList with " + networkInfos.size() + " network(s)!");
                }

                if (notifyConnMethod != null) {
                    notifyConnMethod.setAccessible(true);
                    notifyConnMethod.invoke(networkMonitor, nativeObserver);
                    Slog.i(TAG, "Called nativeNotifyConnectionTypeChanged successfully");
                }
            } else {
                Slog.e(TAG, "Failed to produce any NetworkInformation for WebRTC!");
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Error ensuring active networks delivered: " + t.getMessage(), t);
        }
    }

    private static Object createSyntheticNetworkInformation(ClassLoader cl, Network network) {
        try {
            Class<?> netInfoClass = cl.loadClass("org.webrtc.NetworkChangeDetector$NetworkInformation");
            @SuppressWarnings("unchecked")
            Class<Enum> connTypeClass = (Class<Enum>) cl.loadClass("org.webrtc.NetworkChangeDetector$ConnectionType");
            Class<?> ipAddrClass = cl.loadClass("org.webrtc.NetworkChangeDetector$IPAddress");

            Constructor<?> netInfoCtor = netInfoClass.getDeclaredConstructor(
                    String.class, connTypeClass, connTypeClass, long.class,
                    Array.newInstance(ipAddrClass, 0).getClass()
            );
            netInfoCtor.setAccessible(true);

            Constructor<?> ipAddrCtor = ipAddrClass.getDeclaredConstructor(byte[].class);
            ipAddrCtor.setAccessible(true);

            Object connWifi = Enum.valueOf(connTypeClass, "CONNECTION_WIFI");
            Object connNone = Enum.valueOf(connTypeClass, "CONNECTION_NONE");

            long handle = (network != null) ? network.getNetworkHandle() : 109L;
            String ifaceName = "wlan0";
            List<Object> ipList = new ArrayList<>();

            try {
                Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
                if (nifs != null) {
                    while (nifs.hasMoreElements()) {
                        NetworkInterface nif = nifs.nextElement();
                        if (nif.isLoopback() || !nif.isUp()) continue;
                        String name = nif.getName();
                        if (name != null && (name.startsWith("wlan") || name.startsWith("rmnet") || name.startsWith("eth"))) {
                            ifaceName = name;
                            Enumeration<InetAddress> addrs = nif.getInetAddresses();
                            while (addrs.hasMoreElements()) {
                                InetAddress addr = addrs.nextElement();
                                if (!addr.isLoopbackAddress()) {
                                    byte[] raw = addr.getAddress();
                                    if (raw != null) {
                                        ipList.add(ipAddrCtor.newInstance(raw));
                                    }
                                }
                            }
                            if (!ipList.isEmpty()) break;
                        }
                    }
                }
            } catch (Throwable t) {
                Slog.w(TAG, "Failed to enumerate interfaces: " + t.getMessage());
            }

            if (ipList.isEmpty()) {
                byte[] raw = new byte[]{(byte) 192, (byte) 168, (byte) 1, (byte) 123};
                ipList.add(ipAddrCtor.newInstance(raw));
            }

            Object ipArray = Array.newInstance(ipAddrClass, ipList.size());
            for (int i = 0; i < ipList.size(); i++) {
                Array.set(ipArray, i, ipList.get(i));
            }

            return netInfoCtor.newInstance(ifaceName, connWifi, connNone, handle, ipArray);
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to create synthetic NetworkInformation: " + t.getMessage(), t);
            return null;
        }
    }

    public static final class Hook {
        public void startMonitoring(Context context, long nativeObserver, String fieldTrials) {
            GuestWebRtcHooks.onStartMonitoring(this, context, nativeObserver, fieldTrials);
        }
    }

    public static final class Backup {
        public void startMonitoring(Context context, long nativeObserver, String fieldTrials) {
        }
    }
}
