package top.niunaijun.blackbox.utils.compat;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.LruCache;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import black.android.content.pm.BRApplicationInfoL;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Loads the original guest package icon and badges it with the BlackTwin indicator.
 * Protects against hidden API restrictions and double-badging.
 */
public final class GuestIconLoader {
    private static final String TAG = "GuestIconLoader";
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(32);
    private static final Set<Bitmap> sBadgedBitmaps = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Bitmap, Boolean>()));

    private GuestIconLoader() {
    }

    public static Bitmap load(String packageName, int userId) {
        return load(packageName, userId, null);
    }

    public static Bitmap load(String packageName, int userId, Activity activity) {
        if (packageName == null || packageName.length() == 0) {
            return null;
        }

        String activityName = (activity != null && activity.getComponentName() != null)
                ? activity.getComponentName().getClassName() : "";
        String baseKey = userId + ":" + packageName + ":" + activityName;

        synchronized (CACHE) {
            Bitmap cached = CACHE.get(baseKey);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
        }

        int size = iconSize(activity != null ? activity : BlackBoxCore.getContext());
        Drawable rawDrawable = loadDrawable(packageName, userId, activity);
        if (rawDrawable == null) {
            return null;
        }

        Bitmap rawBitmap = toSoftwareBitmap(rawDrawable, size);
        if (rawBitmap == null) {
            return null;
        }

        Bitmap badgedBitmap = applyBadge(rawBitmap, size);
        if (badgedBitmap == null) {
            badgedBitmap = rawBitmap;
        }

        synchronized (CACHE) {
            CACHE.put(baseKey, badgedBitmap);
        }
        return badgedBitmap;
    }

    public static boolean isAlreadyBadged(Bitmap bitmap) {
        return bitmap != null && sBadgedBitmaps.contains(bitmap);
    }

    public static void markBadged(Bitmap bitmap) {
        if (bitmap != null) {
            sBadgedBitmaps.add(bitmap);
        }
    }

    public static Bitmap applyBadge(Bitmap source, int size) {
        if (source == null || size <= 0) {
            return source;
        }
        if (isAlreadyBadged(source)) {
            return source;
        }

        try {
            Bitmap badged = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(badged);

            // Draw original icon scaled to size x size
            if (source.getWidth() == size && source.getHeight() == size) {
                canvas.drawBitmap(source, 0f, 0f, null);
            } else {
                Rect srcRect = new Rect(0, 0, source.getWidth(), source.getHeight());
                Rect dstRect = new Rect(0, 0, size, size);
                Paint filterPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(source, srcRect, dstRect, filterPaint);
            }

            // Blue indicator badge in the bottom-right corner
            float dotRadius = size * 0.14f;
            float margin = size * 0.05f;
            float cx = size - dotRadius - margin;
            float cy = size - dotRadius - margin;

            // 1. Dark contrast ring (separates against white/light backgrounds)
            Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaint.setStyle(Paint.Style.FILL);
            ringPaint.setColor(0xFF0B1220);
            canvas.drawCircle(cx, cy, dotRadius + (size * 0.025f), ringPaint);

            // 2. White outer border (separates against dark backgrounds)
            ringPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, dotRadius + (size * 0.012f), ringPaint);

            // 3. Vibrant BlackTwin Neon Blue fill
            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(0xFF3875FF);
            canvas.drawCircle(cx, cy, dotRadius, dotPaint);

            // 4. Glowing inner core (specular LED highlight)
            Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            glowPaint.setStyle(Paint.Style.FILL);
            glowPaint.setColor(0xFFB8C4FF);
            canvas.drawCircle(cx - (dotRadius * 0.25f), cy - (dotRadius * 0.25f), dotRadius * 0.35f, glowPaint);

            markBadged(badged);
            return badged;
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to apply badge: " + t.getMessage());
            return source;
        }
    }

    public static CharSequence loadLabel(String packageName, int userId) {
        if (packageName == null || packageName.length() == 0) {
            return "";
        }
        try {
            ApplicationInfo ai = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, 0, userId);
            if (ai != null && ai.sourceDir != null) {
                PackageManager pm = BlackBoxCore.getPackageManager();
                PackageInfo archive = archiveInfo(pm, ai.sourceDir);
                if (archive != null && archive.applicationInfo != null) {
                    pointAtGuestApk(archive.applicationInfo, ai.sourceDir);
                    CharSequence label = archive.applicationInfo.loadLabel(pm);
                    if (label != null && label.length() > 0) {
                        return label;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            PackageManager pm = BlackBoxCore.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
            if (label != null && label.length() > 0) {
                return label;
            }
        } catch (Throwable ignored) {
        }
        return packageName;
    }

    private static Drawable loadDrawable(String packageName, int userId, Activity activity) {
        // Attempt 1: from live activity context
        if (activity != null) {
            try {
                if (activity.getComponentName() != null) {
                    Drawable d = activity.getPackageManager().getActivityIcon(activity.getComponentName());
                    if (d != null && !isHostOrFallbackIcon(d)) {
                        return d;
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Drawable d = activity.getApplicationInfo().loadIcon(activity.getPackageManager());
                if (d != null && !isHostOrFallbackIcon(d)) {
                    return d;
                }
            } catch (Throwable ignored) {
            }
        }

        // Attempt 2: from guest APK via Resources (safe on Android 5.0 - 17)
        Drawable fromApk = loadFromVirtualApk(packageName, userId);
        if (fromApk != null && !isHostOrFallbackIcon(fromApk)) {
            return fromApk;
        }

        // Attempt 3: check host PackageManager (if app also installed on host)
        try {
            Drawable installed = BlackBoxCore.getPackageManager().getApplicationIcon(packageName);
            if (installed != null && !isHostOrFallbackIcon(installed)) {
                return installed;
            }
        } catch (Throwable ignored) {
        }

        return fromApk;
    }

    private static boolean isHostOrFallbackIcon(Drawable drawable) {
        if (drawable == null) {
            return true;
        }
        try {
            PackageManager pm = BlackBoxCore.getPackageManager();
            Drawable fallback = pm.getDefaultActivityIcon();
            if (fallback != null
                    && fallback.getConstantState() != null
                    && fallback.getConstantState().equals(drawable.getConstantState())) {
                return true;
            }
            Drawable hostIcon = pm.getApplicationIcon(BlackBoxCore.getHostPkg());
            if (hostIcon != null
                    && hostIcon.getConstantState() != null
                    && hostIcon.getConstantState().equals(drawable.getConstantState())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Drawable loadFromVirtualApk(String packageName, int userId) {
        try {
            ApplicationInfo ai = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, 0, userId);
            if (ai == null || ai.sourceDir == null) {
                return null;
            }
            PackageManager pm = BlackBoxCore.getPackageManager();
            PackageInfo archive = archiveInfo(pm, ai.sourceDir);
            if (archive == null || archive.applicationInfo == null) {
                return null;
            }
            ApplicationInfo info = archive.applicationInfo;
            pointAtGuestApk(info, ai.sourceDir);
            if (info.icon == 0 && archive.activities != null) {
                for (ActivityInfo activityInfo : archive.activities) {
                    if (activityInfo != null && activityInfo.icon != 0) {
                        info.icon = activityInfo.icon;
                        break;
                    }
                }
            }
            if (info.icon == 0) {
                return null;
            }

            // Safe official public API without AssetManager reflection
            Resources guest = pm.getResourcesForApplication(info);
            int density = BlackBoxCore.getContext().getResources().getDisplayMetrics().densityDpi;
            Drawable d = null;
            try {
                d = guest.getDrawableForDensity(info.icon, density, null);
            } catch (Throwable t1) {
                try {
                    d = guest.getDrawable(info.icon, null);
                } catch (Throwable ignored) {
                }
            }
            return d;
        } catch (Throwable error) {
            Slog.w(TAG, "loadFromVirtualApk failed for " + packageName + ": " + error.getMessage());
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo archiveInfo(PackageManager pm, String sourceDir) {
        return pm.getPackageArchiveInfo(sourceDir, PackageManager.GET_ACTIVITIES);
    }

    private static void pointAtGuestApk(ApplicationInfo info, String sourceDir) {
        info.sourceDir = sourceDir;
        info.publicSourceDir = sourceDir;
        try {
            BRApplicationInfoL.get(info)._set_scanSourceDir(sourceDir);
            BRApplicationInfoL.get(info)._set_scanPublicSourceDir(sourceDir);
        } catch (Throwable ignored) {
        }
    }

    public static int iconSize(Context context) {
        if (context == null) {
            context = BlackBoxCore.getContext();
        }
        if (context != null) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    int size = am.getLauncherLargeIconSize();
                    if (size > 0) {
                        return size;
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                int density = context.getResources().getDisplayMetrics().densityDpi;
                int calc = (48 * density) / 160;
                if (calc > 0) {
                    return calc;
                }
            } catch (Throwable ignored) {
            }
        }
        return 192;
    }

    public static Bitmap toSoftwareBitmap(Drawable drawable, int size) {
        if (drawable == null || size <= 0) {
            return null;
        }
        try {
            Drawable copy = drawable.mutate();
            // CRUCIAL: setBounds required for AdaptiveIconDrawable and VectorDrawable
            copy.setBounds(0, 0, size, size);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            copy.draw(canvas);
            return bitmap;
        } catch (Throwable error) {
            Slog.w(TAG, "toSoftwareBitmap failed: " + error.getMessage());
            return null;
        }
    }
}
