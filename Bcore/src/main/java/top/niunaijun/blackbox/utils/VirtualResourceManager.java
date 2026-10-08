package top.niunaijun.blackbox.utils;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Central access point for per-profile, per-package virtual media resources.
 *
 * Directory layout (all under app's filesDir):
 *   virtual/<profileId>/<packageName>/
 *       camera/
 *           preview.jpg     – frame for Camera1 PreviewCallback
 *           photo.jpg       – JPEG returned by takePicture
 *           record.mp4      – video file for MediaRecorder
 *       mic/
 *           audio.wav       – PCM data looped by AudioRecord.read()
 *                             absent → silence (mute)
 *       sensors/
 *           scenario.json   – motion scenario descriptor
 *
 * A global fallback directory at virtual/default/<packageName>/ is
 * checked when no profile-specific file is found.
 */
public class VirtualResourceManager {
    private static final String TAG = "VirtualResourceManager";
    private static final String DIR_VIRTUAL = "virtual";
    private static final String DIR_DEFAULT = "default";

    /** Cache parsed scenarios to avoid disk I/O on every sensor event. */
    private static final Map<String, SensorScenario> sScenarioCache =
            new ConcurrentHashMap<>();

    /** Currently active profile id (may be null = use default). */
    private static volatile String sActiveProfileId = null;

    public static void setActiveProfile(String profileId) {
        sActiveProfileId = profileId;
        sScenarioCache.clear();
    }

    // ------------------------------------------------------------------
    // Public accessors
    // ------------------------------------------------------------------

    /** Returns the virtual camera file (or null if not found). */
    public static File getCameraFile(String pkg, String fileName) {
        return resolve(pkg, "camera", fileName);
    }

    /** Returns the virtual mic file (or null if not found). */
    public static File getMicFile(String pkg, String fileName) {
        return resolve(pkg, "mic", fileName);
    }

    /**
     * Returns a parsed SensorScenario for the given package, or null if no
     * scenario.json is found (real sensors used).
     */
    public static SensorScenario getSensorScenario(String pkg) {
        if (TextUtils.isEmpty(pkg)) return null;
        String cacheKey = profileKey() + "/" + pkg;
        SensorScenario cached = sScenarioCache.get(cacheKey);
        if (cached != null) return cached;

        File f = resolve(pkg, "sensors", "scenario.json");
        if (f == null) return null;

        try {
            SensorScenario s = SensorScenario.fromFile(f);
            sScenarioCache.put(cacheKey, s);
            return s;
        } catch (Exception e) {
            Slog.w(TAG, "Failed to parse scenario: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private static File resolve(String pkg, String subDir, String fileName) {
        if (TextUtils.isEmpty(pkg) || TextUtils.isEmpty(fileName)) return null;
        Context ctx = BlackBoxCore.get().getContext();
        if (ctx == null) return null;
        File base = ctx.getFilesDir();

        // 1. Profile-specific
        String pid = sActiveProfileId;
        if (!TextUtils.isEmpty(pid)) {
            File f = new File(base, DIR_VIRTUAL + "/" + pid + "/" + pkg + "/" + subDir + "/" + fileName);
            if (f.exists()) return f;
        }

        // 2. Default (profile-agnostic)
        File f = new File(base, DIR_VIRTUAL + "/" + DIR_DEFAULT + "/" + pkg + "/" + subDir + "/" + fileName);
        if (f.exists()) return f;

        return null;
    }

    private static String profileKey() {
        String p = sActiveProfileId;
        return TextUtils.isEmpty(p) ? DIR_DEFAULT : p;
    }

    /** Returns the root directory for a profile+package pair (creates if missing). */
    public static File ensureRoot(String profileId, String pkg, String subDir) {
        Context ctx = BlackBoxCore.get().getContext();
        if (ctx == null) return null;
        File dir = new File(ctx.getFilesDir(),
                DIR_VIRTUAL + "/" + profileId + "/" + pkg + "/" + subDir);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
}
