package top.niunaijun.blackbox.utils;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import top.niunaijun.blackbox.app.BActivityThread;

/** Changes only the guest's view; never writes Settings, USB state or properties on the host. */
public final class DebugStatePolicy {
    public static final String USB_STATE = "android.hardware.usb.action.USB_STATE";
    public static final Object UNHANDLED = new Object();
    private static volatile boolean enabled;

    private DebugStatePolicy() {}

    public static void configure(boolean hideAdb) {
        enabled = hideAdb;
        if (isEnabled()) {
            setBuildField("TYPE", "user");
            setBuildField("TAGS", "release-keys");
            setBuildField("IS_DEBUGGABLE", false);
        }
    }

    private static void setBuildField(String name, Object value) {
        try { Reflector.on(android.os.Build.class).field(name).set(value); }
        catch (Exception ignored) { }
    }

    public static boolean isEnabled() {
        return enabled && BActivityThread.getAppPid() != -1;
    }

    public static boolean isDebugSetting(String key) {
        return "adb_enabled".equals(key) || "adb_wifi_enabled".equals(key)
                || "development_settings_enabled".equals(key);
    }

    public static Object interceptSettingsCall(Object[] args) {
        return interceptSettingsCall(args, false);
    }

    public static Object interceptSettingsCall(Object[] args, boolean knownSettingsProvider) {
        if (!isEnabled() || args == null) return UNHANDLED;
        boolean settings = knownSettingsProvider;
        for (Object arg : args) if ("settings".equals(arg)) settings = true;
        if (!settings) return UNHANDLED;
        for (int i = 0; i + 1 < args.length; i++) {
            if (!(args[i] instanceof String) || !(args[i + 1] instanceof String)) continue;
            String operation = (String) args[i];
            if (!isDebugSetting((String) args[i + 1])) continue;
            if ("GET_global".equals(operation) || "GET_secure".equals(operation) || "GET_system".equals(operation)) {
                Bundle result = new Bundle();
                result.putString("value", "0");
                return result;
            }
            if ("PUT_global".equals(operation) || "PUT_secure".equals(operation) || "PUT_system".equals(operation)) {
                Bundle result = new Bundle();
                result.putBoolean("result", false);
                return result;
            }
        }
        return UNHANDLED;
    }

    public static boolean isSettingsUri(Uri uri) {
        return uri != null && "settings".equals(uri.getAuthority());
    }

    public static Intent guestUsbState(Intent original) {
        if (!isEnabled() || original == null || !USB_STATE.equals(original.getAction())) return original;
        Intent result = new Intent(original);
        // OEM extras may contain additional USB functions; a disconnected view has none active.
        Bundle extras = result.getExtras();
        if (extras != null) for (String key : extras.keySet()) {
            if (extras.get(key) instanceof Boolean) result.putExtra(key, false);
        }
        result.putExtra("connected", false);
        result.putExtra("configured", false);
        result.putExtra("adb", false);
        result.putExtra("mtp", false);
        result.putExtra("ptp", false);
        result.putExtra("host_connected", false);
        result.putExtra("unlocked", false);
        return result;
    }
}
