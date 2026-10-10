package top.niunaijun.blackbox.utils;

import android.os.Bundle;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Resolved device profile for the guest process.
 * Written by the host as fingerprint.json. mode off, or a missing file,
 * leaves the existing engine stubs in place.
 */
public final class GuestFingerprint {
    /** Captured before any Build field is rewritten. */
    public static final int REAL_SDK = android.os.Build.VERSION.SDK_INT;

    private static final GuestFingerprint INACTIVE = new GuestFingerprint(false);

    private static volatile GuestFingerprint sCurrent = INACTIVE;
    private static volatile long sStamp = Long.MIN_VALUE;
    private static volatile String sPackage = "";
    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();

    public final boolean active;
    public final String androidId;
    public final String gaid;
    public final String imei0;
    public final String imei1;
    public final String imsi;
    public final String line1;
    public final String serial;
    public final String mac;
    public final String fingerprint;
    public final String model;
    public final String device;
    public final String product;
    public final String brand;
    public final String manufacturer;
    public final String hardware;
    public final String board;
    public final String bootloader;
    public final String radio;
    public final String display;
    public final String buildId;
    public final String incremental;
    public final String release;
    public final int sdk;
    public final String patch;
    public final String type;
    public final String tags;
    public final String abis;
    public final String host;
    public final String user;
    public final long time;

    private GuestFingerprint(boolean active) {
        this(active, "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", 0, "", "", "", "", "", "", 0L);
    }

    private GuestFingerprint(boolean active, String androidId, String gaid, String imei0, String imei1,
                              String imsi, String line1, String serial, String mac, String fingerprint,
                              String model, String device, String product, String brand, String manufacturer,
                              String hardware, String board, String bootloader, String radio, String display,
                              String buildId, String incremental, String release, int sdk, String patch,
                              String type, String tags, String abis, String host, String user, long time) {
        this.active = active;
        this.androidId = androidId;
        this.gaid = gaid;
        this.imei0 = imei0;
        this.imei1 = imei1;
        this.imsi = imsi;
        this.line1 = line1;
        this.serial = serial;
        this.mac = mac;
        this.fingerprint = fingerprint;
        this.model = model;
        this.device = device;
        this.product = product;
        this.brand = brand;
        this.manufacturer = manufacturer;
        this.hardware = hardware;
        this.board = board;
        this.bootloader = bootloader;
        this.radio = radio;
        this.display = display;
        this.buildId = buildId;
        this.incremental = incremental;
        this.release = release;
        this.sdk = sdk;
        this.patch = patch;
        this.type = type;
        this.tags = tags;
        this.abis = abis;
        this.host = host;
        this.user = user;
        this.time = time;
    }

    public static GuestFingerprint get() {
        if (Boolean.TRUE.equals(BUSY.get())) return sCurrent;
        BUSY.set(Boolean.TRUE);
        try {
            String pkg = VirtualResourceManager.currentPackage();
            if (pkg.equals(sPackage) && sStamp == stamp(pkg)) return sCurrent;
            return load(pkg);
        } finally {
            BUSY.set(Boolean.FALSE);
        }
    }

    public static GuestFingerprint load(String pkg) {
        VirtualResourceManager.bindGuestProfile();
        File file = VirtualResourceManager.fingerprintFile(pkg);
        long stamp = stampOf(file);
        GuestFingerprint parsed = parse(file);
        sPackage = pkg == null ? "" : pkg;
        sStamp = stamp;
        sCurrent = parsed;
        return parsed;
    }

    public boolean applySdk() {
        return sdk > 0 && sdk <= REAL_SDK;
    }

    public String imei(int slot) {
        return slot == 1 ? imei1 : imei0;
    }

    public static int slot(Object[] args) {
        if (args == null) return 0;
        for (Object arg : args) {
            if (arg instanceof Integer) return (Integer) arg;
        }
        return 0;
    }

    /** Null means the caller should keep the real property. */
    public String property(String key) {
        if (!active || key == null) return null;
        switch (key) {
            case "ro.build.fingerprint": return fingerprint;
            case "ro.build.display.id": return display;
            case "ro.build.id": return buildId;
            case "ro.build.version.release": return release;
            case "ro.build.version.incremental": return incremental;
            case "ro.build.version.security_patch": return patch;
            case "ro.build.type": return type;
            case "ro.build.tags": return tags;
            case "ro.build.host": return host;
            case "ro.build.user": return user;
            case "ro.build.version.sdk": return applySdk() ? Integer.toString(sdk) : null;
            case "ro.product.model": return model;
            case "ro.product.brand": return brand;
            case "ro.product.name": return product;
            case "ro.product.device": return device;
            case "ro.product.board": return board;
            case "ro.product.manufacturer": return manufacturer;
            case "ro.hardware": return hardware;
            case "ro.bootloader": return bootloader;
            case "ro.serialno":
            case "ro.boot.serialno": return serial;
            case "ro.product.cpu.abi": return firstAbi();
            case "ro.product.cpu.abilist": return abis;
            case "ro.product.cpu.abilist64": return firstAbi();
            case "ro.product.cpu.abilist32": return secondAbi();
            default: return null;
        }
    }

    public String firstAbi() {
        int comma = abis.indexOf(',');
        return comma < 0 ? abis : abis.substring(0, comma);
    }

    public String secondAbi() {
        int comma = abis.indexOf(',');
        if (comma < 0 || comma + 1 >= abis.length()) return "";
        return abis.substring(comma + 1);
    }

    public String[] abiArray() {
        if (TextUtils.isEmpty(abis)) return new String[0];
        return abis.split(",");
    }

    public static Object interceptSettingsCall(Object[] args) {
        GuestFingerprint fp = get();
        if (!fp.active || args == null) return null;
        for (int i = 0; i + 1 < args.length; i++) {
            if (!(args[i] instanceof String) || !(args[i + 1] instanceof String)) continue;
            String operation = (String) args[i];
            if (!operation.startsWith("GET_")) continue;
            String value = setting((String) args[i + 1], operation);
            if (value == null) continue;
            Bundle bundle = new Bundle();
            bundle.putString("value", value);
            return bundle;
        }
        return null;
    }

    public static String setting(String key, String operation) {
        GuestFingerprint fp = get();
        if (!fp.active || key == null) return null;
        if ("android_id".equals(key)) return fp.androidId;
        if ("serial".equals(key) || "serialno".equals(key) || "device_serial".equals(key)) {
            if (operation == null || operation.contains("global") || operation.contains("secure")) return fp.serial;
        }
        return null;
    }

    private static GuestFingerprint parse(File file) {
        if (file == null || !file.isFile()) return INACTIVE;
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            JSONObject json = new JSONObject(sb.toString());
            String mode = json.optString("mode", "off");
            boolean active = "auto".equals(mode) || "manual".equals(mode);
            if (!active) return INACTIVE;
            String androidId = json.optString("android_id", "");
            if (androidId.length() == 0) return INACTIVE;
            return new GuestFingerprint(
                    true,
                    androidId,
                    json.optString("gaid", ""),
                    json.optString("imei0", ""),
                    json.optString("imei1", ""),
                    json.optString("imsi", ""),
                    json.optString("line1", ""),
                    json.optString("serial", ""),
                    json.optString("mac", ""),
                    json.optString("fingerprint", ""),
                    json.optString("model", ""),
                    json.optString("device", ""),
                    json.optString("product", ""),
                    json.optString("brand", ""),
                    json.optString("manufacturer", ""),
                    json.optString("hardware", ""),
                    json.optString("board", ""),
                    json.optString("bootloader", ""),
                    json.optString("radio", ""),
                    json.optString("display", ""),
                    json.optString("build_id", ""),
                    json.optString("incremental", ""),
                    json.optString("release", ""),
                    json.optInt("sdk", 0),
                    json.optString("patch", ""),
                    json.optString("type", "user"),
                    json.optString("tags", "release-keys"),
                    json.optString("abis", ""),
                    json.optString("host", ""),
                    json.optString("user", ""),
                    json.optLong("time", 0L)
            );
        } catch (Throwable t) {
            Slog.w("GuestFingerprint", "fingerprint.json: " + t.getMessage());
            return INACTIVE;
        }
    }

    private static long stamp(String pkg) {
        return stampOf(VirtualResourceManager.fingerprintFile(pkg));
    }

    private static long stampOf(File file) {
        if (file == null || !file.exists()) return 0L;
        return file.lastModified() ^ file.length();
    }
}
