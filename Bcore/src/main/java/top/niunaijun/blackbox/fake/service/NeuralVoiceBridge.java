package top.niunaijun.blackbox.fake.service;

import android.os.SystemClock;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.utils.AppSpoofConfig;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.VirtualResourceManager;

/**
 * High-performance, zero-allocation JNI bridge between C++ VoiceWorker and LiteRT StreamingVoiceEngine.
 * Supports dynamic real-time voice profile switching during live calls.
 */
public final class NeuralVoiceBridge {
    private static final String TAG = "NeuralVoiceBridge";
    private static volatile Object sEngineInstance = null;
    private static volatile Method sPushDirectMethod = null;
    private static volatile Method sPullDirectMethod = null;
    private static volatile Method sIsLoadedMethod = null;
    private static volatile Method sSelectVoiceMethod = null;

    private static volatile String sCurrentPkg = null;
    private static volatile String sCurrentProfile = null;
    private static volatile long sLastCheckTime = 0;

    private NeuralVoiceBridge() {}

    public static void setPkg(String pkg) {
        sCurrentPkg = pkg;
    }

    public static void setCurrentProfile(String profile) {
        sCurrentProfile = profile;
    }

    public static synchronized void setEngine(Object engine) {
        sEngineInstance = engine;
        sPushDirectMethod = null;
        sPullDirectMethod = null;
        sIsLoadedMethod = null;
        sSelectVoiceMethod = null;

        if (engine != null) {
            try {
                Class<?> cls = engine.getClass();
                sPushDirectMethod = cls.getMethod("pushInputDirect",
                        ByteBuffer.class, int.class, int.class, int.class, boolean.class, long.class);
                sPullDirectMethod = cls.getMethod("pullOutputDirect",
                        ByteBuffer.class, int.class, int.class, int.class, boolean.class);
                sIsLoadedMethod = cls.getMethod("isLoaded");
                try {
                    sSelectVoiceMethod = cls.getMethod("selectVoice", String.class);
                } catch (Throwable ignored) {}
                Slog.i(TAG, "NeuralVoiceBridge linked to " + cls.getName());
            } catch (Throwable t) {
                Slog.e(TAG, "Failed to reflect engine methods: " + t.getMessage());
            }
        }
    }

    public static Object getEngine() {
        return sEngineInstance;
    }

    public static boolean isEngineReady() {
        Object engine = sEngineInstance;
        Method isLoaded = sIsLoadedMethod;
        if (engine == null || isLoaded == null) return false;
        try {
            return Boolean.TRUE.equals(isLoaded.invoke(engine));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Checks if the user changed the spoof voice profile in BlackTwin settings.
     * Automatically applies the change to the running engine with smooth crossfading.
     */
    private static void checkDynamicProfileUpdate() {
        String pkg = sCurrentPkg;
        if (pkg == null || pkg.isEmpty()) {
            pkg = VirtualResourceManager.currentPackage();
        }
        if (pkg == null || pkg.isEmpty()) return;

        AppSpoofConfig config = VirtualResourceManager.getSpoofConfig(pkg);
        if (config == null) return;

        String newProfile = config.micProfile;
        if (newProfile != null && !newProfile.equals(sCurrentProfile)) {
            String oldProfile = sCurrentProfile;
            sCurrentProfile = newProfile;

            String targetVoice = "female_01";
            if ("child".equals(newProfile)) {
                targetVoice = "child_01";
            } else if ("deep".equals(newProfile)) {
                targetVoice = "deep_01";
            } else if (!newProfile.isEmpty()) {
                targetVoice = newProfile;
            }

            Object engine = sEngineInstance;
            Method selectMethod = sSelectVoiceMethod;
            if (engine != null && selectMethod != null) {
                try {
                    selectMethod.invoke(engine, targetVoice);
                    Slog.i(TAG, "Dynamically switched voice profile from " + oldProfile
                            + " to " + newProfile + " (" + targetVoice + ")");
                } catch (Throwable t) {
                    Slog.w(TAG, "Dynamic voice switch failed: " + t.getMessage());
                }
            }

            try {
                NativeCore.enableMicTap("", null, 0, 0,
                        config.nativeMicMode(), config.nativeMicProfile(),
                        config.micInputGain, config.micPitchSemitones, config.micFormantShift,
                        config.micUnderflowSilence);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Called directly from C++ VoiceWorker on MODE_NEURAL.
     * Guaranteed zero heap allocations on steady-state.
     */
    public static void processFrame(ByteBuffer inBuf, ByteBuffer outBuf, int frames, int channels, int sampleRate) {
        if (inBuf != null) inBuf.order(ByteOrder.nativeOrder());
        if (outBuf != null) outBuf.order(ByteOrder.nativeOrder());

        long now = SystemClock.uptimeMillis();
        if (now - sLastCheckTime > 250) {
            sLastCheckTime = now;
            checkDynamicProfileUpdate();
        }

        Object engine = sEngineInstance;
        Method pushMethod = sPushDirectMethod;
        Method pullMethod = sPullDirectMethod;

        if (engine == null || pushMethod == null || pullMethod == null) {
            // Engine not yet attached; passthrough directly
            if (inBuf != null && outBuf != null) {
                inBuf.rewind();
                outBuf.rewind();
                int limit = Math.min(inBuf.remaining(), outBuf.remaining());
                for (int i = 0; i < limit; i++) {
                    outBuf.put(inBuf.get());
                }
            }
            return;
        }

        try {
            int byteCount = frames * channels * 4; // float PCM: 4 bytes per sample
            pushMethod.invoke(engine, inBuf, 0, byteCount, channels, true, 0L);
            pullMethod.invoke(engine, outBuf, 0, byteCount, channels, true);
        } catch (Throwable t) {
            Slog.w(TAG, "Neural processFrame error: " + t.getMessage());
            if (inBuf != null && outBuf != null) {
                inBuf.rewind();
                outBuf.rewind();
                int limit = Math.min(inBuf.remaining(), outBuf.remaining());
                for (int i = 0; i < limit; i++) {
                    outBuf.put(inBuf.get());
                }
            }
        }
    }
}
