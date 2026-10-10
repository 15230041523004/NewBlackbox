package top.niunaijun.blackbox.fake.service;

import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Bundle;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Scan listeners captured in the guest process. A spoofed startScan wakes them
 * without asking the real radio to scan.
 */
public final class GuestWifiListeners {
    private static final List<WeakReference<Object>> RECEIVERS = new ArrayList<>();
    private static final List<WeakReference<Object>> CALLBACKS = new ArrayList<>();

    private GuestWifiListeners() {
    }

    public static boolean wantsScan(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg instanceof IntentFilter
                    && ((IntentFilter) arg).hasAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                return true;
            }
        }
        return false;
    }

    public static void addReceiver(Object receiver) {
        if (receiver == null) return;
        synchronized (RECEIVERS) {
            add(RECEIVERS, receiver);
        }
    }

    public static void removeReceiver(Object receiver) {
        if (receiver == null) return;
        synchronized (RECEIVERS) {
            remove(RECEIVERS, receiver);
        }
    }

    public static void addCallback(Object callback) {
        if (callback == null) return;
        synchronized (CALLBACKS) {
            add(CALLBACKS, callback);
        }
    }

    public static void removeCallback(Object callback) {
        if (callback == null) return;
        synchronized (CALLBACKS) {
            remove(CALLBACKS, callback);
        }
    }

    public static void notifyScanAvailable() {
        List<Object> receivers = copy(RECEIVERS);
        List<Object> callbacks = copy(CALLBACKS);
        Intent intent = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        intent.putExtra(WifiManager.EXTRA_RESULTS_UPDATED, true);
        for (Object receiver : receivers) {
            try {
                Method perform = receiver.getClass().getMethod(
                        "performReceive",
                        Intent.class, int.class, String.class, Bundle.class,
                        boolean.class, boolean.class, int.class);
                perform.invoke(receiver, intent, 0, null, null, false, false, 0);
            } catch (Throwable ignored) {
            }
        }
        for (Object callback : callbacks) {
            try {
                Method ready = callback.getClass().getMethod("onScanResultsAvailable");
                ready.invoke(callback);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void add(List<WeakReference<Object>> list, Object value) {
        remove(list, value);
        list.add(new WeakReference<>(value));
    }

    private static void remove(List<WeakReference<Object>> list, Object value) {
        Iterator<WeakReference<Object>> it = list.iterator();
        while (it.hasNext()) {
            Object item = it.next().get();
            if (item == null || item == value) it.remove();
        }
    }

    private static List<Object> copy(List<WeakReference<Object>> list) {
        List<Object> out = new ArrayList<>();
        synchronized (list) {
            Iterator<WeakReference<Object>> it = list.iterator();
            while (it.hasNext()) {
                Object item = it.next().get();
                if (item == null) it.remove();
                else out.add(item);
            }
        }
        return out;
    }
}
