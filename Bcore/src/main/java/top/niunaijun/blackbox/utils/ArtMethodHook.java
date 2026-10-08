package top.niunaijun.blackbox.utils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import top.niunaijun.jnihook.jni.JniHook;

/**
 * Saves an original method and redirects its entry through an ART trampoline.
 * System methods keep their declaring class so boot-image GC remains valid.
 */
public final class ArtMethodHook {
    private ArtMethodHook() {
    }

    /**
     * Hooks a probe and checks that the replacement body runs on a direct call.
     */
    public static boolean selfTest() {
        try {
            Method target = Probe.class.getDeclaredMethod("value");
            Method replacement = ProbeHook.class.getDeclaredMethod("value");
            Method backup = ProbeBackup.class.getDeclaredMethod("value");
            if (!hook(target, replacement, backup)) return false;
            Probe receiver = new Probe();
            int got = receiver.value();
            Object original = call(backup, receiver);
            boolean ok = got == 41 && Integer.valueOf(1).equals(original);
            if (!ok) Slog.w("ArtMethodHook", "self-test returned " + got);
            return ok;
        } catch (Throwable t) {
            Slog.w("ArtMethodHook", "self-test failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Saves {@code target} into {@code backup}, then points {@code target}'s
     * entry at {@code replacement}. Both methods must have the same signature.
     * The target keeps its declaring class. Copying the replacement struct
     * onto a boot-image method makes the GC abort.
     */
    public static boolean hook(Method target, Method replacement, Method backup) {
        if (target == null || replacement == null || backup == null) return false;
        if (!JniHook.copyArtMethod(target, backup)) return false;
        if (!JniHook.redirectArtMethod(replacement, target)) return false;
        backup.setAccessible(true);
        target.setAccessible(true);
        return true;
    }

    public static Object call(Method backup, Object receiver, Object... args) throws Throwable {
        try {
            return backup.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }
    }

    public static final class Probe {
        public int value() {
            return 1;
        }
    }

    public static final class ProbeHook {
        public int value() {
            return hooked(this);
        }
    }

    /** Same shape as the camera hooks: the copied body calls a static bridge. */
    static int hooked(Object receiver) {
        return receiver instanceof Probe ? 41 : 0;
    }

    public static final class ProbeBackup {
        public int value() {
            return 0;
        }
    }
}
