package top.niunaijun.blackbox.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.text.TextUtils;
import android.view.SurfaceHolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Per-profile, per-package virtual camera, microphone and motion files.
 *
 * Layout under the host app filesDir:
 *   virtual/&lt;profileId&gt;/&lt;packageName&gt;/spoof_config.json
 *   virtual/&lt;profileId&gt;/&lt;packageName&gt;/camera/photo.jpg|preview.jpg|record.mp4
 *   virtual/&lt;profileId&gt;/&lt;packageName&gt;/mic/audio.wav
 *   virtual/&lt;profileId&gt;/&lt;packageName&gt;/sensors/scenario.json
 *
 * A profile-agnostic fallback lives at virtual/default/&lt;packageName&gt;/.
 * Config and scenario reads follow the file mtime, so a change made by the
 * host UI is visible on the next guest call.
 */
public class VirtualResourceManager {
    private static final String TAG = "VirtualResourceManager";
    private static final String DIR_VIRTUAL = "virtual";
    private static final String DIR_DEFAULT = "default";

    private static final class CachedConfig {
        final long stamp;
        final AppSpoofConfig config;

        CachedConfig(long stamp, AppSpoofConfig config) {
            this.stamp = stamp;
            this.config = config;
        }
    }

    private static final class CachedScenario {
        final long stamp;
        final SensorScenario scenario;

        CachedScenario(long stamp, SensorScenario scenario) {
            this.stamp = stamp;
            this.scenario = scenario;
        }
    }

    private static final Map<String, CachedScenario> sScenarioCache = new ConcurrentHashMap<>();
    private static final Map<String, CachedConfig> sConfigCache = new ConcurrentHashMap<>();

    /** Optional override. Empty means "read the guest user id". */
    private static volatile String sActiveProfileId = null;

    private static Bitmap sBitmap;
    private static long sBitmapStamp;
    private static String sBitmapKey = "";
    private static Bitmap sVideoStill;
    private static long sVideoStillStamp;
    private static String sVideoStillKey = "";
    private static Bitmap[] sVideoFrames;
    private static long sVideoFramesStamp;
    private static String sVideoFramesKey = "";
    private static int sVideoFrameIndex;
    private static byte[] sNv21;
    private static int sNv21W;
    private static int sNv21H;
    private static int sNv21Frame = -2;
    private static long sNv21Stamp = Long.MIN_VALUE;
    private static byte[] sBlackJpeg;
    private static byte[] sCameraJpeg;
    private static long sCameraJpegStamp;
    private static String sCameraJpegKey = "";

    public static void setActiveProfile(String profileId) {
        sActiveProfileId = profileId;
        sScenarioCache.clear();
        sConfigCache.clear();
    }

    public static void invalidateCache(String pkg) {
        String key = profileKey() + "/" + pkg;
        sScenarioCache.remove(key);
        sConfigCache.remove(key);
        sBitmap = null;
        sNv21 = null;
        sVideoStill = null;
        sVideoFrames = null;
        sCameraJpeg = null;
    }

    /** Guest package running in this process, or the host package outside a guest. */
    public static String currentPackage() {
        try {
            String pkg = BlackBoxCore.getAppPackageName();
            if (!TextUtils.isEmpty(pkg)) return pkg;
        } catch (Throwable ignored) {
        }
        try {
            String host = BlackBoxCore.getHostPkg();
            return host == null ? "" : host;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Aligns the file root with the guest user id once the process is bound. */
    public static void bindGuestProfile() {
        try {
            int userId = BlackBoxCore.getUserId();
            if (userId < 0) return;
            String pid = String.valueOf(userId);
            if (!pid.equals(sActiveProfileId)) {
                sActiveProfileId = pid;
                sScenarioCache.clear();
                sConfigCache.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean spoofCamera(String pkg) {
        return !getSpoofConfig(pkg).grantCamera;
    }

    public static boolean spoofMic(String pkg) {
        return !getSpoofConfig(pkg).grantMic;
    }

    public static boolean spoofSensors(String pkg) {
        return !getSpoofConfig(pkg).grantSensors;
    }

    public static AppSpoofConfig getSpoofConfig(String pkg) {
        bindGuestProfile();
        if (TextUtils.isEmpty(pkg)) return AppSpoofConfig.fromFile(null);
        String profile = profileKey();
        String cacheKey = profile + "/" + pkg;
        File file = existingConfig(profile, pkg);
        long stamp = stampOf(file);
        CachedConfig cached = sConfigCache.get(cacheKey);
        if (cached != null && cached.stamp == stamp) return cached.config;
        AppSpoofConfig cfg = AppSpoofConfig.fromFile(file);
        sConfigCache.put(cacheKey, new CachedConfig(stamp, cfg));
        return cfg;
    }

    public static File getCameraFile(String pkg, String fileName) {
        return resolve(pkg, "camera", fileName);
    }

    public static File getMicFile(String pkg, String fileName) {
        return resolve(pkg, "mic", fileName);
    }

    /**
     * Parsed motion scenario, or null when the guest should keep real sensors.
     * Callers that already decided to spoof use {@link #scenarioOrStationary}.
     */
    public static SensorScenario getSensorScenario(String pkg) {
        bindGuestProfile();
        if (TextUtils.isEmpty(pkg)) return null;
        String profile = profileKey();
        String cacheKey = profile + "/" + pkg;
        File file = existingScenario(profile, pkg);
        long stamp = stampOf(file);
        CachedScenario cached = sScenarioCache.get(cacheKey);
        if (cached != null && cached.stamp == stamp) return cached.scenario;
        SensorScenario scenario = null;
        if (file != null) {
            try {
                scenario = SensorScenario.fromFile(file);
            } catch (Exception e) {
                Slog.w(TAG, "Failed to parse scenario: " + e.getMessage());
            }
        }
        sScenarioCache.put(cacheKey, new CachedScenario(stamp, scenario));
        return scenario;
    }

    public static SensorScenario scenarioOrStationary(String pkg) {
        SensorScenario scenario = getSensorScenario(pkg);
        return scenario != null ? scenario : SensorScenario.stationary();
    }

    /**
     * Still shown as the virtual camera. A chosen video contributes its first
     * frame; otherwise the imported photo is used.
     */
    public static Bitmap cameraPreviewBitmap(String pkg) {
        File video = getCameraFile(pkg, "record.mp4");
        if (video != null) {
            Bitmap frame = videoFrame(video);
            if (frame != null) return frame;
        }
        return cameraBitmap(pkg);
    }

    public static synchronized byte[] cameraJpeg(String pkg) {
        File file = getCameraFile(pkg, "photo.jpg");
        if (file == null) file = getCameraFile(pkg, "preview.jpg");
        if (file == null) return blackJpeg();
        String key = file.getAbsolutePath();
        long stamp = stampOf(file);
        if (sCameraJpeg != null && stamp == sCameraJpegStamp && key.equals(sCameraJpegKey)) return sCameraJpeg;
        byte[] data = readBytes(file);
        if (data != null && data.length > 3 && (data[0] & 0xff) == 0xff
                && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff) {
            sCameraJpeg = data;
        } else {
            // The picker preserves the imported format, even though the stored
            // filename is photo.jpg (the device's selected image is a PNG).
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(key, options);
                int sample = 1;
                while (options.outWidth / sample > 1920 || options.outHeight / sample > 1920) sample *= 2;
                options.inJustDecodeBounds = false;
                options.inSampleSize = sample;
                bitmap = BitmapFactory.decodeFile(key, options);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                sCameraJpeg = bitmap != null && bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                        ? output.toByteArray() : blackJpeg();
            } catch (Throwable error) {
                Slog.w(TAG, "camera JPEG: " + error);
                sCameraJpeg = blackJpeg();
            } finally {
                if (bitmap != null) bitmap.recycle();
            }
        }
        sCameraJpegKey = key;
        sCameraJpegStamp = stamp;
        return sCameraJpeg;
    }

    public static byte[] cameraNv21(String pkg, int width, int height) {
        if (width <= 0 || height <= 0) {
            width = 640;
            height = 480;
        }
        File file = getCameraFile(pkg, "preview.jpg");
        if (file == null) file = getCameraFile(pkg, "photo.jpg");
        long stamp = stampOf(file);
        Bitmap bitmap = cameraPreviewBitmap(pkg);
        int frame = sVideoFrameIndex;
        if (sNv21 != null && sNv21W == width && sNv21H == height && sNv21Stamp == stamp && sNv21Frame == frame) {
            return sNv21;
        }
        byte[] out = sNv21 != null && sNv21.length == width * height * 3 / 2 ? sNv21 : blackNv21(width, height);
        sNv21Frame = frame;
        if (bitmap != null) {
            Bitmap scaled = bitmap;
            if (bitmap.getWidth() != width || bitmap.getHeight() != height) {
                scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            }
            int[] pixels = new int[width * height];
            scaled.getPixels(pixels, 0, width, 0, 0, width, height);
            encodeNv21(pixels, width, height, out);
            if (scaled != bitmap) scaled.recycle();
        }
        sNv21 = out;
        sNv21W = width;
        sNv21H = height;
        sNv21Stamp = stamp;
        return out;
    }

    public static void drawPreview(SurfaceHolder holder, String pkg) {
        if (holder == null) return;
        Canvas canvas = null;
        try {
            canvas = holder.lockCanvas();
            if (canvas == null) return;
            canvas.drawColor(Color.BLACK);
            Bitmap bitmap = cameraPreviewBitmap(pkg);
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null,
                        new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "drawPreview: " + t.getMessage());
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static boolean copyFile(File src, File dst) {
        if (src == null || dst == null || !src.exists()) return false;
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "copyFile: " + e.getMessage());
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static File ensureRoot(String profileId, String pkg, String subDir) {
        Context ctx = BlackBoxCore.getContext();
        if (ctx == null) return null;
        File dir = new File(ctx.getFilesDir(),
                DIR_VIRTUAL + "/" + profileId + "/" + pkg + "/" + subDir);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static Bitmap cameraBitmap(String pkg) {
        File file = getCameraFile(pkg, "preview.jpg");
        if (file == null) file = getCameraFile(pkg, "photo.jpg");
        if (file == null) return null;
        long stamp = stampOf(file);
        String key = file.getAbsolutePath();
        if (sBitmap != null && stamp == sBitmapStamp && key.equals(sBitmapKey)) return sBitmap;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(key, bounds);
        int sample = 1;
        while ((bounds.outWidth / sample) > 1280 || (bounds.outHeight / sample) > 1280) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(key, opts);
        sBitmap = bitmap;
        sBitmapStamp = stamp;
        sBitmapKey = key;
        return bitmap;
    }

    /** Cycles frames of the imported clip. A photo is used when this returns null. */
    private static Bitmap videoFrame(File video) {
        long stamp = stampOf(video);
        String key = video.getAbsolutePath();
        if (sVideoFrames == null || stamp != sVideoFramesStamp || !key.equals(sVideoFramesKey)) {
            sVideoFrames = loadVideoFrames(video);
            sVideoFramesStamp = stamp;
            sVideoFramesKey = key;
            sVideoFrameIndex = 0;
        }
        Bitmap[] frames = sVideoFrames;
        if (frames == null || frames.length == 0) return videoStill(video);
        int index = (int) ((android.os.SystemClock.uptimeMillis() / 100L) % frames.length);
        sVideoFrameIndex = index;
        Bitmap frame = frames[index];
        return frame != null ? frame : videoStill(video);
    }

    private static Bitmap[] loadVideoFrames(File video) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            long durationMs = 1000L;
            try {
                String text = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (text != null) durationMs = Math.max(200L, Long.parseLong(text));
            } catch (Throwable ignored) {
            }
            int count = (int) Math.min(12, Math.max(1, durationMs / 250L));
            Bitmap[] frames = new Bitmap[count];
            int filled = 0;
            for (int i = 0; i < count; i++) {
                long timeUs = durationMs * 1000L * i / count;
                Bitmap raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                Bitmap frame = scaleDown(raw, 640);
                if (frame != null) frames[filled++] = frame;
            }
            if (filled == 0) return null;
            if (filled == frames.length) return frames;
            Bitmap[] trimmed = new Bitmap[filled];
            System.arraycopy(frames, 0, trimmed, 0, filled);
            return trimmed;
        } catch (Throwable t) {
            Slog.w(TAG, "video frames: " + t.getMessage());
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Bitmap scaleDown(Bitmap frame, int maxEdge) {
        if (frame == null) return null;
        int w = frame.getWidth();
        int h = frame.getHeight();
        int max = Math.max(w, h);
        if (max <= maxEdge || max <= 0) return frame;
        float scale = maxEdge / (float) max;
        int nw = Math.max(2, Math.round(w * scale));
        int nh = Math.max(2, Math.round(h * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(frame, nw, nh, true);
        if (scaled != frame) frame.recycle();
        return scaled;
    }

    private static Bitmap videoStill(File video) {
        long stamp = stampOf(video);
        String key = video.getAbsolutePath();
        if (sVideoStill != null && stamp == sVideoStillStamp && key.equals(sVideoStillKey)) {
            return sVideoStill;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(key);
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null) return sVideoStill;
            sVideoStill = frame;
            sVideoStillStamp = stamp;
            sVideoStillKey = key;
            return frame;
        } catch (Throwable t) {
            Slog.w(TAG, "video frame: " + t.getMessage());
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] blackJpeg() {
        if (sBlackJpeg != null) return sBlackJpeg;
        try {
            Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLACK);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
            bitmap.recycle();
            sBlackJpeg = out.toByteArray();
            return sBlackJpeg;
        } catch (Throwable t) {
            return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        }
    }

    private static byte[] blackNv21(int width, int height) {
        int size = width * height * 3 / 2;
        byte[] out = new byte[size];
        Arrays.fill(out, width * height, size, (byte) 128);
        return out;
    }

    private static void encodeNv21(int[] argb, int width, int height, byte[] out) {
        int frameSize = width * height;
        int yIndex = 0;
        int uvIndex = frameSize;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int color = argb[j * width + i];
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                out[yIndex++] = (byte) clamp(y);
                if ((j & 1) == 0 && (i & 1) == 0 && uvIndex + 1 < out.length) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    out[uvIndex++] = (byte) clamp(v);
                    out[uvIndex++] = (byte) clamp(u);
                }
            }
        }
    }

    private static int clamp(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    private static byte[] readBytes(File file) {
        if (file == null || !file.exists() || file.length() <= 0) return null;
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
            return data;
        } catch (Exception e) {
            Slog.w(TAG, "readBytes: " + e.getMessage());
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static File existingConfig(String profile, String pkg) {
        File file = path(profile, pkg, "", "spoof_config.json");
        if (file != null && file.exists()) return file;
        if (!DIR_DEFAULT.equals(profile)) {
            File fallback = path(DIR_DEFAULT, pkg, "", "spoof_config.json");
            if (fallback != null && fallback.exists()) return fallback;
        }
        return null;
    }

    private static File existingScenario(String profile, String pkg) {
        File file = path(profile, pkg, "sensors", "scenario.json");
        if (file != null && file.exists()) return file;
        if (!DIR_DEFAULT.equals(profile)) {
            File fallback = path(DIR_DEFAULT, pkg, "sensors", "scenario.json");
            if (fallback != null && fallback.exists()) return fallback;
        }
        return null;
    }

    private static File resolve(String pkg, String subDir, String fileName) {
        if (TextUtils.isEmpty(pkg) || TextUtils.isEmpty(fileName)) return null;
        bindGuestProfile();
        String profile = profileKey();
        File file = path(profile, pkg, subDir, fileName);
        if (file != null && file.exists()) return file;
        if (!DIR_DEFAULT.equals(profile)) {
            File fallback = path(DIR_DEFAULT, pkg, subDir, fileName);
            if (fallback != null && fallback.exists()) return fallback;
        }
        return null;
    }

    private static File path(String profileId, String pkg, String subDir, String fileName) {
        Context ctx = BlackBoxCore.getContext();
        if (ctx == null || TextUtils.isEmpty(profileId) || TextUtils.isEmpty(pkg)) return null;
        String middle = TextUtils.isEmpty(subDir) ? "" : subDir + "/";
        return new File(ctx.getFilesDir(),
                DIR_VIRTUAL + "/" + profileId + "/" + pkg + "/" + middle + fileName);
    }

    private static long stampOf(File file) {
        if (file == null || !file.exists()) return 0L;
        return file.lastModified() ^ file.length();
    }

    private static String profileKey() {
        String profile = sActiveProfileId;
        return TextUtils.isEmpty(profile) ? DIR_DEFAULT : profile;
    }
}
