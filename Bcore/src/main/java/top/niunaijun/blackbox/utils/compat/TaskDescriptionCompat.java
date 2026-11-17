package top.niunaijun.blackbox.utils.compat;

import android.app.Activity;
import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.os.Build;

import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Slog;

public class TaskDescriptionCompat {
    private static final String TAG = "TaskDescriptionCompat";
    private static final ThreadLocal<Boolean> sApplying = new ThreadLocal<>();

    public static boolean isApplying() {
        return Boolean.TRUE.equals(sApplying.get());
    }

    public static boolean isGuest() {
        return BActivityThread.isThreadInit() && BActivityThread.getAppConfig() != null;
    }

    /**
     * Instantiates an ActivityManager.TaskDescription safely across API levels.
     * Enforces opaque color to avoid AOSP RuntimeException("A TaskDescription's primary color should be opaque").
     */
    public static ActivityManager.TaskDescription create(String label, Bitmap icon, int primaryColor) {
        int opaqueColor = (primaryColor != 0) ? (primaryColor | 0xFF000000) : 0xFF1E1E1E;

        // API < 28 (Android 5.0 - 8.1): use public 3-argument constructor directly
        if (Build.VERSION.SDK_INT < 28) {
            try {
                return new ActivityManager.TaskDescription(label, icon, opaqueColor);
            } catch (Throwable ignored) {
            }
        }

        // API >= 28 (Android 9.0 - 17): Constructor is deprecated in SDK but functional in framework;
        // wrap in try-catch with multi-level fallbacks.
        try {
            return new ActivityManager.TaskDescription(label, icon, opaqueColor);
        } catch (Throwable t1) {
            Slog.w(TAG, "TaskDescription(label, icon, color) failed: " + t1.getMessage());
            try {
                return new ActivityManager.TaskDescription(label, icon);
            } catch (Throwable t2) {
                try {
                    return new ActivityManager.TaskDescription(label);
                } catch (Throwable ignored) {
                }
            }
        }
        return new ActivityManager.TaskDescription();
    }

    /**
     * Intercepts TaskDescription calls in IPC proxies.
     * Does NOT call td.getIcon() on modern API levels to avoid deprecated/null returns.
     */
    public static ActivityManager.TaskDescription fix(ActivityManager.TaskDescription td) {
        if (!isGuest() || isApplying()) {
            return td;
        }

        String label = null;
        int primaryColor = 0;
        if (td != null) {
            try {
                label = td.getLabel();
            } catch (Throwable ignored) {
            }
            try {
                primaryColor = td.getPrimaryColor();
            } catch (Throwable ignored) {
            }
        }

        int userId = BlackBoxCore.getUserId();
        String packageName = BlackBoxCore.getAppPackageName();
        if (label == null || !label.startsWith("[B")) {
            CharSequence appLabel = label != null ? label : GuestIconLoader.loadLabel(packageName, userId);
            label = getTaskDescriptionLabel(userId, appLabel);
        }

        Bitmap badgedIcon = GuestIconLoader.load(packageName, userId, null);
        return create(label, badgedIcon, primaryColor);
    }

    /** Apply after startup/splash transitions and guest task-description updates. */
    public static void apply(Activity activity) {
        if (!isGuest() || activity == null) {
            return;
        }
        try {
            int userId = BlackBoxCore.getUserId();
            String packageName = BlackBoxCore.getAppPackageName();

            CharSequence rawLabel = activity.getTitle();
            if (rawLabel == null || rawLabel.length() == 0) {
                rawLabel = GuestIconLoader.loadLabel(packageName, userId);
            }
            String label = getTaskDescriptionLabel(userId, rawLabel);

            int primaryColor = 0xFF1E1E1E;
            try {
                java.lang.reflect.Field field = Activity.class.getDeclaredField("mTaskDescription");
                field.setAccessible(true);
                ActivityManager.TaskDescription previous = (ActivityManager.TaskDescription) field.get(activity);
                if (previous != null) {
                    primaryColor = previous.getPrimaryColor();
                }
            } catch (Throwable ignored) {
            }

            Bitmap badgedIcon = GuestIconLoader.load(packageName, userId, activity);
            ActivityManager.TaskDescription td = create(label, badgedIcon, primaryColor);

            try {
                sApplying.set(Boolean.TRUE);
                activity.setTaskDescription(td);
            } finally {
                sApplying.remove();
            }

            Slog.d("GuestTaskIcon", "Applied badged TaskDescription for " + packageName
                    + " (task=" + activity.getTaskId() + ", hasIcon=" + (badgedIcon != null) + ")");
        } catch (Throwable error) {
            Slog.w("GuestTaskIcon", "Cannot update guest task icon: " + error.getMessage());
        }
    }

    public static String getTaskDescriptionLabel(int userId, CharSequence label) {
        String text = label == null ? "" : label.toString();
        if (text.startsWith("[B")) {
            return text;
        }
        return String.format(Locale.CHINA, "[B%d]%s", userId, text);
    }
}
