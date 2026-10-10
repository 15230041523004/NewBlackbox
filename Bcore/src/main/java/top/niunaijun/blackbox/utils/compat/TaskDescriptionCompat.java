package top.niunaijun.blackbox.utils.compat;

import android.app.ActivityManager;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;

import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Slog;

public class TaskDescriptionCompat {
    @SuppressWarnings("deprecation")
    public static ActivityManager.TaskDescription fix(ActivityManager.TaskDescription td) {
        if (!isGuest()) return td;
        String label = null;
        Bitmap icon = null;
        int primaryColor = 0;
        if (td != null) {
            try {
                label = td.getLabel();
            } catch (Throwable ignored) {
            }
            try {
                icon = td.getIcon();
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
        Bitmap guestIcon = GuestIconLoader.load(packageName, userId);
        if (guestIcon != null) icon = guestIcon;
        try {
            ActivityManager.TaskDescription copy = td == null ? new ActivityManager.TaskDescription()
                    : ActivityManager.TaskDescription.class.getConstructor(ActivityManager.TaskDescription.class).newInstance(td);
            copy.getClass().getMethod("setLabel", String.class).invoke(copy, label);
            if (icon != null) {
                copy.getClass().getMethod("setIcon", Icon.class).invoke(copy, Icon.createWithBitmap(icon));
                copy.getClass().getMethod("setIconFilename", String.class).invoke(copy, new Object[]{null});
            }
            return copy;
        } catch (ReflectiveOperationException ignored) {
            return new ActivityManager.TaskDescription(label, icon, primaryColor);
        }
    }

    public static boolean isGuest() {
        return BActivityThread.isThreadInit() && BActivityThread.getAppConfig() != null;
    }

    /** Apply again after startup/splash transitions and guest task-description updates. */
    public static void apply(Activity activity) {
        if (!isGuest()) return;
        try {
            ActivityManager.TaskDescription previous = null;
            try {
                java.lang.reflect.Field field = Activity.class.getDeclaredField("mTaskDescription");
                field.setAccessible(true); previous = (ActivityManager.TaskDescription) field.get(activity);
            } catch (ReflectiveOperationException ignored) { }
            activity.setTaskDescription(fix(previous));
            android.content.pm.ActivityInfo info = black.android.app.BRActivity.get(activity).mActivityInfo();
            if (info != null && BlackBoxCore.getAppPackageName().equals(info.packageName)
                    && info.getIconResource() != 0) activity.getWindow().setIcon(info.getIconResource());
            Slog.d("GuestTaskIcon", BlackBoxCore.getAppPackageName() + " task=" + activity.getTaskId());
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
