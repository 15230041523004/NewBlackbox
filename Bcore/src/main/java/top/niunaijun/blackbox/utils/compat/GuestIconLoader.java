package top.niunaijun.blackbox.utils.compat;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import black.android.content.pm.BRApplicationInfoL;
import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Loads the guest package icon from its own APK.
 * Virtual {@link ApplicationInfo} copies the host {@code scanSourceDir}, so
 * {@code loadIcon} on that object resolves Black Twin resources.
 */
public final class GuestIconLoader {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(32);

    private GuestIconLoader() {
    }

    public static Bitmap load(String packageName, int userId) {
        if (packageName == null || packageName.length() == 0) {
            return null;
        }
        String key = userId + ":" + packageName;
        synchronized (CACHE) {
            Bitmap cached = CACHE.get(key);
            if (cached != null && !cached.isRecycled()) {
                return cached.copy(Bitmap.Config.ARGB_8888, false);
            }
        }
        Drawable drawable = loadDrawable(packageName, userId);
        if (drawable == null) {
            return null;
        }
        Bitmap bitmap = toSoftwareBitmap(drawable, iconSize());
        if (bitmap == null) {
            return null;
        }
        synchronized (CACHE) {
            CACHE.put(key, bitmap);
        }
        return bitmap.copy(Bitmap.Config.ARGB_8888, false);
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

    private static Drawable loadDrawable(String packageName, int userId) {
        Drawable fromApk = loadFromVirtualApk(packageName, userId);
        if (fromApk != null && !isDefaultIcon(fromApk)) {
            return fromApk;
        }
        try {
            Drawable installed = BlackBoxCore.getPackageManager().getApplicationIcon(packageName);
            if (installed != null) {
                return installed;
            }
        } catch (Throwable ignored) {
        }
        return fromApk;
    }

    private static boolean isDefaultIcon(Drawable drawable) {
        try {
            Drawable fallback = BlackBoxCore.getPackageManager().getDefaultActivityIcon();
            return fallback != null
                    && fallback.getConstantState() != null
                    && fallback.getConstantState().equals(drawable.getConstantState());
        } catch (Throwable ignored) {
            return false;
        }
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
            // PackageManager caches icons by package/id and can retain a host drawable
            // loaded with virtual scanSourceDir. Read guest assets directly instead.
            java.lang.reflect.Constructor<AssetManager> constructor = AssetManager.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            AssetManager assets = constructor.newInstance();
            try {
                java.lang.reflect.Method add = AssetManager.class.getMethod("addAssetPath", String.class);
                if (((Integer) add.invoke(assets, ai.sourceDir)) == 0) return null;
                if (ai.splitSourceDirs != null) {
                    for (String split : ai.splitSourceDirs) add.invoke(assets, split);
                }
                Resources host = BlackBoxCore.getContext().getResources();
                Resources guest = new Resources(assets, host.getDisplayMetrics(), host.getConfiguration());
                // Rasterize before closing the asset table; preserve adaptive and vector icons.
                Bitmap icon = toSoftwareBitmap(guest.getDrawable(info.icon, null), iconSize());
                return icon == null ? null : new android.graphics.drawable.BitmapDrawable(host, icon);
            } finally {
                assets.close();
            }
        } catch (Throwable ignored) {
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

    private static int iconSize() {
        try {
            ActivityManager am = (ActivityManager) BlackBoxCore.getContext()
                    .getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                int size = am.getLauncherLargeIconSize();
                if (size > 0) {
                    return size;
                }
            }
        } catch (Throwable ignored) {
        }
        return 192;
    }

    private static Bitmap toSoftwareBitmap(Drawable drawable, int size) {
        try {
            Drawable copy = drawable.mutate();
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            copy.setBounds(0, 0, size, size);
            copy.draw(canvas);
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
