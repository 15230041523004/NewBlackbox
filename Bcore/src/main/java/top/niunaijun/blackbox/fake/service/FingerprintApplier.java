package top.niunaijun.blackbox.fake.service;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import top.niunaijun.blackbox.utils.ArtMethodHook;
import top.niunaijun.blackbox.utils.GuestFingerprint;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Applies one guest device profile after the engine has finished its own
 * startup and before the guest application class is loaded.
 * Telephony, Wi-Fi and settings proxies read the same file on each call.
 * Build fields change only for this process, so the guest must be started again.
 */
public final class FingerprintApplier {
    private static final String TAG = "FingerprintApplier";
    private static volatile boolean sHooks;

    private static Method bSecureGet;
    private static Method bSecureGetUser;
    private static Method bGlobalGet;
    private static Method bGlobalGetUser;
    private static Method bPropGet;
    private static Method bPropGetDef;
    private static Method bPropGetInt;
    private static Method bSerial;
    private static Method bGaid;

    private FingerprintApplier() {
    }

    public static void install(String pkg) {
        GuestFingerprint fp = GuestFingerprint.load(pkg);
        if (fp.active) {
            applyBuild(fp);
            Slog.i(TAG, "profile " + fp.model + " for " + pkg);
        } else {
            Slog.i(TAG, "off for " + pkg);
        }
        if (sHooks) return;
        sHooks = true;
        hookSettings();
        hookProperties();
        hookSerial();
        hookAdvertisingId();
    }

    private static void applyBuild(GuestFingerprint fp) {
        set(Build.class, "FINGERPRINT", fp.fingerprint);
        set(Build.class, "MODEL", fp.model);
        set(Build.class, "DEVICE", fp.device);
        set(Build.class, "PRODUCT", fp.product);
        set(Build.class, "BRAND", fp.brand);
        set(Build.class, "MANUFACTURER", fp.manufacturer);
        set(Build.class, "HARDWARE", fp.hardware);
        set(Build.class, "BOARD", fp.board);
        set(Build.class, "BOOTLOADER", fp.bootloader);
        set(Build.class, "RADIO", fp.radio);
        set(Build.class, "DISPLAY", fp.display);
        set(Build.class, "ID", fp.buildId);
        set(Build.class, "HOST", fp.host);
        set(Build.class, "USER", fp.user);
        set(Build.class, "TYPE", fp.type);
        set(Build.class, "TAGS", fp.tags);
        set(Build.class, "SERIAL", fp.serial);
        if (fp.time > 0L) set(Build.class, "TIME", fp.time);
        String[] abis = fp.abiArray();
        if (abis.length > 0) {
            set(Build.class, "SUPPORTED_ABIS", abis);
            set(Build.class, "SUPPORTED_64_BIT_ABIS", new String[]{fp.firstAbi()});
            String second = fp.secondAbi();
            set(Build.class, "SUPPORTED_32_BIT_ABIS", second.length() == 0 ? new String[0] : new String[]{second});
            set(Build.class, "CPU_ABI", fp.firstAbi());
            set(Build.class, "CPU_ABI2", second);
        }
        set(Build.VERSION.class, "RELEASE", fp.release);
        set(Build.VERSION.class, "INCREMENTAL", fp.incremental);
        set(Build.VERSION.class, "SECURITY_PATCH", fp.patch);
        set(Build.VERSION.class, "CODENAME", "REL");
        if (fp.applySdk()) {
            set(Build.VERSION.class, "SDK_INT", fp.sdk);
            set(Build.VERSION.class, "SDK", Integer.toString(fp.sdk));
        }
    }

    private static void set(Class<?> type, String name, Object value) {
        try {
            Reflector.on(type).field(name).set(value);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            try {
                Field flags = Field.class.getDeclaredField("accessFlags");
                flags.setAccessible(true);
                flags.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            } catch (Throwable ignored) {
            }
            field.set(null, value);
        } catch (Throwable t) {
            Slog.w(TAG, "skip Build." + name + ": " + t.getMessage());
        }
    }

    private static void hookSettings() {
        bSecureGet = hookStatic(android.provider.Settings.Secure.class, "getString",
                SecureHook.class, SecureBackup.class, ContentResolver.class, String.class);
        bSecureGetUser = hookStatic(android.provider.Settings.Secure.class, "getStringForUser",
                SecureUserHook.class, SecureUserBackup.class, ContentResolver.class, String.class, int.class);
        bGlobalGet = hookStatic(android.provider.Settings.Global.class, "getString",
                GlobalHook.class, GlobalBackup.class, ContentResolver.class, String.class);
        bGlobalGetUser = hookStatic(android.provider.Settings.Global.class, "getStringForUser",
                GlobalUserHook.class, GlobalUserBackup.class, ContentResolver.class, String.class, int.class);
    }

    private static void hookProperties() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            bPropGet = hookStatic(properties, "get", PropHook.class, PropBackup.class, String.class);
            bPropGetDef = hookStatic(properties, "get", PropDefHook.class, PropDefBackup.class, String.class, String.class);
            bPropGetInt = hookStatic(properties, "getInt", PropIntHook.class, PropIntBackup.class, String.class, int.class);
        } catch (Throwable t) {
            Slog.w(TAG, "SystemProperties hook skipped: " + t.getMessage());
        }
    }

    private static void hookSerial() {
        bSerial = hookStatic(Build.class, "getSerial", SerialHook.class, SerialBackup.class);
    }

    private static void hookAdvertisingId() {
        try {
            Class<?> client = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient");
            bGaid = hookStatic(client, "getAdvertisingIdInfo", GaidHook.class, GaidBackup.class, Context.class);
            if (bGaid == null) Slog.i(TAG, "AdvertisingIdClient hook was not installed");
        } catch (Throwable t) {
            Slog.i(TAG, "AdvertisingIdClient is not in this process");
        }
    }

    private static Method hookStatic(Class<?> target, String name, Class<?> hookClass, Class<?> backupClass,
                                     Class<?>... params) {
        try {
            Method original = target.getDeclaredMethod(name, params);
            Method replacement = hookClass.getDeclaredMethod(name, params);
            Method backup = backupClass.getDeclaredMethod(name, params);
            original.setAccessible(true);
            replacement.setAccessible(true);
            backup.setAccessible(true);
            if (!ArtMethodHook.hook(original, replacement, backup)) {
                Slog.w(TAG, "copy failed " + target.getSimpleName() + "." + name);
                return null;
            }
            Slog.i(TAG, "hooked " + target.getSimpleName() + "." + name);
            return backup;
        } catch (Throwable t) {
            Slog.w(TAG, "skip " + target.getSimpleName() + "." + name + ": " + t.getMessage());
            return null;
        }
    }

    static String secureValue(String name) {
        GuestFingerprint fp = GuestFingerprint.get();
        if (!fp.active || !"android_id".equals(name)) return null;
        return fp.androidId;
    }

    static String globalValue(String name) {
        GuestFingerprint fp = GuestFingerprint.get();
        if (!fp.active) return null;
        if ("serial".equals(name) || "serialno".equals(name) || "device_serial".equals(name)) return fp.serial;
        return null;
    }

    static Object call(Method backup, Object... args) throws Throwable {
        if (backup == null) return null;
        return ArtMethodHook.call(backup, null, args);
    }

    public static final class SecureHook {
        public static String getString(ContentResolver resolver, String name) {
            String spoofed = secureValue(name);
            if (spoofed != null) return spoofed;
            try {
                return (String) call(bSecureGet, resolver, name);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    public static final class SecureBackup {
        public static String getString(ContentResolver resolver, String name) { return null; }
    }

    public static final class SecureUserHook {
        public static String getStringForUser(ContentResolver resolver, String name, int userHandle) {
            String spoofed = secureValue(name);
            if (spoofed != null) return spoofed;
            try {
                return (String) call(bSecureGetUser, resolver, name, userHandle);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    public static final class SecureUserBackup {
        public static String getStringForUser(ContentResolver resolver, String name, int userHandle) { return null; }
    }

    public static final class GlobalHook {
        public static String getString(ContentResolver resolver, String name) {
            String spoofed = globalValue(name);
            if (spoofed != null) return spoofed;
            try {
                return (String) call(bGlobalGet, resolver, name);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    public static final class GlobalBackup {
        public static String getString(ContentResolver resolver, String name) { return null; }
    }

    public static final class GlobalUserHook {
        public static String getStringForUser(ContentResolver resolver, String name, int userHandle) {
            String spoofed = globalValue(name);
            if (spoofed != null) return spoofed;
            try {
                return (String) call(bGlobalGetUser, resolver, name, userHandle);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    public static final class GlobalUserBackup {
        public static String getStringForUser(ContentResolver resolver, String name, int userHandle) { return null; }
    }

    public static final class PropHook {
        public static String get(String key) {
            String spoofed = GuestFingerprint.get().property(key);
            if (spoofed != null) return spoofed;
            try {
                Object value = call(bPropGet, key);
                return value == null ? "" : value.toString();
            } catch (Throwable t) {
                return "";
            }
        }
    }

    public static final class PropBackup {
        public static String get(String key) { return ""; }
    }

    public static final class PropDefHook {
        public static String get(String key, String def) {
            String spoofed = GuestFingerprint.get().property(key);
            if (spoofed != null) return spoofed;
            try {
                Object value = call(bPropGetDef, key, def);
                return value == null ? def : value.toString();
            } catch (Throwable t) {
                return def;
            }
        }
    }

    public static final class PropDefBackup {
        public static String get(String key, String def) { return def; }
    }

    public static final class PropIntHook {
        public static int getInt(String key, int def) {
            String spoofed = GuestFingerprint.get().property(key);
            if (spoofed != null) {
                try {
                    return Integer.parseInt(spoofed);
                } catch (NumberFormatException ignored) {
                }
            }
            try {
                Object value = call(bPropGetInt, key, def);
                return value instanceof Integer ? (Integer) value : def;
            } catch (Throwable t) {
                return def;
            }
        }
    }

    public static final class PropIntBackup {
        public static int getInt(String key, int def) { return def; }
    }

    public static final class SerialHook {
        public static String getSerial() {
            GuestFingerprint fp = GuestFingerprint.get();
            if (fp.active && fp.serial.length() > 0) return fp.serial;
            try {
                Object value = call(bSerial);
                return value == null ? Build.UNKNOWN : value.toString();
            } catch (Throwable t) {
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                return Build.UNKNOWN;
            }
        }
    }

    public static final class SerialBackup {
        public static String getSerial() { return Build.UNKNOWN; }
    }

    public static final class GaidHook {
        public static Object getAdvertisingIdInfo(Context context) {
            GuestFingerprint fp = GuestFingerprint.get();
            if (fp.active && fp.gaid.length() > 0) {
                try {
                    return info(fp.gaid);
                } catch (Throwable t) {
                    Slog.w(TAG, "advertising id: " + t.getMessage());
                }
            }
            try {
                return call(bGaid, context);
            } catch (Throwable t) {
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                return null;
            }
        }

        private static Object info(String id) throws Exception {
            Class<?> type = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient$Info");
            Constructor<?> ctor = type.getDeclaredConstructor(String.class, boolean.class);
            ctor.setAccessible(true);
            return ctor.newInstance(id, Boolean.FALSE);
        }
    }

    public static final class GaidBackup {
        public static Object getAdvertisingIdInfo(Context context) { return null; }
    }
}
