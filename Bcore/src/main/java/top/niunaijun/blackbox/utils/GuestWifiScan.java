package top.niunaijun.blackbox.utils;

import android.net.wifi.ScanResult;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import black.android.net.wifi.BRWifiSsid;

/**
 * Serves the access-point list written by the host for this profile and package.
 * Signal level is computed on each read so a scanner sees the bars move.
 * The level formula matches domain WifiAround.level.
 */
public final class GuestWifiScan {
    private static long sConfigStamp = Long.MIN_VALUE;
    private static boolean sEnabled;
    private static long sScanStamp = Long.MIN_VALUE;
    private static List<Ap> sAps = Collections.emptyList();

    private GuestWifiScan() {
    }

    public static boolean enabled(String pkg) {
        File scan = VirtualResourceManager.wifiScanFile(pkg);
        if (scan == null) return false;
        File config = new File(scan.getParentFile(), "spoof_config.json");
        long stamp = stampOf(config);
        if (stamp != sConfigStamp) {
            sConfigStamp = stamp;
            sEnabled = readEnabled(config);
        }
        return sEnabled;
    }

    public static List<ScanResult> scanResults(String pkg) {
        List<Ap> aps = aps(pkg);
        if (aps.isEmpty()) return new ArrayList<>();
        long elapsed = SystemClock.elapsedRealtime();
        long timestamp = elapsed * 1000L;
        List<ScanResult> results = new ArrayList<>(aps.size());
        for (int i = 0; i < aps.size(); i++) {
            results.add(aps.get(i).toScanResult(elapsed, timestamp + i * 1000L));
        }
        return results;
    }

    /** Strongest network right now, or null when the list is empty. */
    public static Ap strongest(String pkg) {
        List<Ap> aps = aps(pkg);
        if (aps.isEmpty()) return null;
        long elapsed = SystemClock.elapsedRealtime();
        Ap best = aps.get(0);
        int bestLevel = best.level(elapsed);
        for (int i = 1; i < aps.size(); i++) {
            int level = aps.get(i).level(elapsed);
            if (level > bestLevel) {
                best = aps.get(i);
                bestLevel = level;
            }
        }
        return best;
    }

    private static List<Ap> aps(String pkg) {
        File file = VirtualResourceManager.wifiScanFile(pkg);
        long stamp = stampOf(file);
        if (stamp != sScanStamp) {
            sScanStamp = stamp;
            sAps = parse(file);
        }
        return sAps;
    }

    private static boolean readEnabled(File file) {
        String text = readText(file);
        if (text == null) return false;
        try {
            return new JSONObject(text).optBoolean("wifi_spoof", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<Ap> parse(File file) {
        String text = readText(file);
        if (text == null) return Collections.emptyList();
        try {
            JSONArray rows = new JSONObject(text).optJSONArray("networks");
            if (rows == null || rows.length() == 0) return Collections.emptyList();
            List<Ap> aps = new ArrayList<>(rows.length());
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String bssid = row.optString("bssid", "").trim();
                if (bssid.length() == 0) continue;
                aps.add(new Ap(
                        row.optString("ssid", ""),
                        bssid,
                        row.optString("capabilities", "[ESS]"),
                        row.optInt("frequency", 2412),
                        row.optInt("baseRssi", -70)
                ));
            }
            return aps;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static String readText(File file) {
        if (file == null || !file.isFile()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(data, 0, off, "UTF-8");
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static long stampOf(File file) {
        if (file == null || !file.exists()) return 0L;
        return file.lastModified() ^ file.length();
    }

    public static final class Ap {
        public final String ssid;
        public final String bssid;
        public final String capabilities;
        public final int frequency;
        public final int baseRssi;

        Ap(String ssid, String bssid, String capabilities, int frequency, int baseRssi) {
            this.ssid = ssid == null ? "" : ssid;
            this.bssid = bssid;
            this.capabilities = capabilities == null || capabilities.length() == 0 ? "[ESS]" : capabilities;
            this.frequency = frequency > 0 ? frequency : 2412;
            this.baseRssi = baseRssi;
        }

        public int level(long elapsedRealtimeMs) {
            double phase = bssid.hashCode();
            double wave = Math.sin(elapsedRealtimeMs / 1800.0 + phase);
            int jitter = (int) Math.round(wave * 5.0);
            int noise = ((bssid.hashCode() ^ (int) (elapsedRealtimeMs / 400L)) & 3) - 1;
            int value = baseRssi + jitter + noise;
            if (value < -95) return -95;
            if (value > -30) return -30;
            return value;
        }

        ScanResult toScanResult(long elapsedRealtimeMs, long timestamp) {
            ScanResult result = new ScanResult();
            // The local dump has no network name. Show the BSSID so a scanner lists the AP.
            String label = ssid.length() == 0 ? bssid.toUpperCase(Locale.US) : ssid;
            result.SSID = label;
            result.BSSID = bssid.toLowerCase(Locale.US);
            result.capabilities = capabilities;
            result.level = level(elapsedRealtimeMs);
            result.frequency = frequency;
            result.centerFreq0 = frequency;
            result.channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ;
            result.timestamp = timestamp;
            Object wifiSsid = wifiSsid(label);
            setField(result, "wifiSsid", wifiSsid);
            setField(result, "mWifiSsid", wifiSsid);
            return result;
        }

        private static Object wifiSsid(String text) {
            try {
                Object created = BRWifiSsid.get().createFromAsciiEncoded(text);
                if (created != null) return created;
            } catch (Throwable ignored) {
            }
            try {
                Class<?> cls = Class.forName("android.net.wifi.WifiSsid");
                Method fromBytes = cls.getMethod("fromBytes", byte[].class);
                return fromBytes.invoke(null, text.getBytes("UTF-8"));
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static void setField(Object target, String name, Object value) {
            if (value == null) return;
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(target, value);
                    return;
                } catch (Throwable ignored) {
                    type = type.getSuperclass();
                }
            }
        }
    }
}
