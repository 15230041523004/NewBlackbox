package top.niunaijun.blackbox.fake.service;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Choreographer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import top.niunaijun.blackbox.utils.ArtMethodHook;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Samsung's Choreographer asks SemGameManager whether the resumed package is a
 * game. The resumed record is the host, so the guest is marked background and
 * the frame clock waits 4s. While a guest activity is resumed, stay foreground.
 * After pause the surface is destroyed; forcing frames then null-dereferences.
 */
public final class GuestFrameClock {
    private static final String TAG = "GuestFrameClock";
    private static final AtomicInteger RESUMED = new AtomicInteger();
    private static Method sBackup;
    private static boolean sLogged;
    private static boolean sWatching;

    private GuestFrameClock() {}

    public static void install() {
        if (sBackup != null) return;
        try {
            Method original = Choreographer.class.getDeclaredMethod("setIsFg", boolean.class);
            Method replacement = Hook.class.getDeclaredMethod("setIsFg", boolean.class);
            Method backup = Backup.class.getDeclaredMethod("setIsFg", boolean.class);
            if (!ArtMethodHook.hook(original, replacement, backup)) {
                Slog.w(TAG, "setIsFg hook failed");
                return;
            }
            sBackup = backup;
            Slog.i(TAG, "setIsFg hook installed");
        } catch (Throwable t) {
            Slog.w(TAG, "setIsFg not hooked: " + t.getMessage());
        }
    }

    public static void watch(Application app) {
        if (app == null || sWatching) return;
        sWatching = true;
        app.registerActivityLifecycleCallbacks(new Started());
    }

    static void onSetIsFg(Object choreographer, boolean isFg) {
        boolean keep = !isFg && GuestNetworkView.isGuest() && RESUMED.get() > 0;
        if (keep) {
            isFg = true;
            if (!sLogged) {
                sLogged = true;
                Slog.i(TAG, "guest activity is resumed; keeping the frame clock in the foreground");
            }
        }
        Method backup = sBackup;
        if (backup == null) return;
        try {
            ArtMethodHook.call(backup, choreographer, isFg);
        } catch (Throwable t) {
            Slog.w(TAG, "setIsFg: " + t.getMessage());
        }
    }

    public static final class Hook {
        public void setIsFg(boolean isFg) {
            onSetIsFg(this, isFg);
        }
    }

    public static final class Backup {
        public void setIsFg(boolean isFg) {}
    }

    private static final class Started implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity activity) { RESUMED.incrementAndGet(); }
        @Override public void onActivityPaused(Activity activity) { RESUMED.decrementAndGet(); }
        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }
}
