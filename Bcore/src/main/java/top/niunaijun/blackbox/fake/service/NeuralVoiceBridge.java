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
    private static volatile Method sProcessDirectMethod = null;
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
        sProcessDirectMethod = null;
        sPushDirectMethod = null;
        sPullDirectMethod = null;
        sIsLoadedMethod = null;
        sSelectVoiceMethod = null;

        if (engine != null) {
            try {
                Class<?> cls = engine.getClass();
                // 1. Try MeanVc2Plugin direct entry point: processDirect(ByteBuffer, ByteBuffer, int, int, int, boolean)
                try {
                    sProcessDirectMethod = cls.getMethod("processDirect",
                            ByteBuffer.class, ByteBuffer.class, int.class, int.class, int.class, boolean.class);
                } catch (NoSuchMethodException ignored) {}

                // 2. Fallback to StreamingVoiceEngine direct methods if engine passed directly
                try {
                    sPushDirectMethod = cls.getMethod("pushInputDirect",
                            ByteBuffer.class, int.class, int.class, int.class, boolean.class, long.class);
                    sPullDirectMethod = cls.getMethod("pullOutputDirect",
                            ByteBuffer.class, int.class, int.class, int.class, boolean.class);
                } catch (NoSuchMethodException ignored) {}

                // 3. Ready check
                try {
                    sIsLoadedMethod = cls.getMethod("isReady");
                } catch (Throwable e) {
                    try {
                        sIsLoadedMethod = cls.getMethod("isLoaded");
                    } catch (Throwable ignored) {}
                }

                // 4. Voice selection
                try {
                    sSelectVoiceMethod = cls.getMethod("selectVoice", String.class);
                } catch (Throwable ignored) {}
                Slog.i(TAG, "NeuralVoiceBridge linked to " + cls.getName() + " (processDirect=" + (sProcessDirectMethod != null) + ")");
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
        if (engine == null) return false;
        Method isLoaded = sIsLoadedMethod;
        if (isLoaded != null) {
            try {
                return Boolean.TRUE.equals(isLoaded.invoke(engine));
            } catch (Throwable ignored) {}
        }
        return sProcessDirectMethod != null || sPushDirectMethod != null;
    }

    private static volatile String sCurrentMode = null;
    private static volatile float sCurrentGain = -1.0f;
    private static volatile float sCurrentPitch = 999.0f;
    private static volatile float sCurrentFormant = 999.0f;
    private static volatile boolean sCurrentUnderflowSilence = false;

    private static void checkDynamicProfileUpdate() {
        // Loading this bridge in the host (Local Voice Test/diagnostics) must not
        // install guest mic substitution over the real microphone source.
        // Guest processes retain the existing capture -> neural -> guest path.
        boolean guestProcess = top.niunaijun.blackbox.BlackBoxCore.get().isBlackProcess();
        String pkg = guestProcess ? top.niunaijun.blackbox.app.BActivityThread.getAppPackageName() : null;
        if (!GuestMicScope.allows(guestProcess, pkg, sCurrentPkg)) return;

        AppSpoofConfig config = VirtualResourceManager.getSpoofConfig(pkg);
        if (config == null) return;

        if (sEngineInstance == null && ("neural".equals(config.micMode) || "dsp".equals(config.micMode))) {
            SpoofHookInstaller.initNeuralEngine(pkg);
        }

        String newProfile = config.micProfile;
        String newMode = config.micMode;
        float newGain = config.micInputGain;
        float newPitch = config.micPitchSemitones;
        float newFormant = config.micFormantShift;
        boolean newUnderflowSilence = config.micUnderflowSilence;

        boolean profileChanged = newProfile != null && !newProfile.equals(sCurrentProfile);
        boolean modeChanged = newMode != null && !newMode.equals(sCurrentMode);
        boolean audioParamsChanged = newGain != sCurrentGain || newPitch != sCurrentPitch
                || newFormant != sCurrentFormant || newUnderflowSilence != sCurrentUnderflowSilence;

        if (profileChanged || modeChanged || audioParamsChanged) {
            String oldProfile = sCurrentProfile;
            sCurrentProfile = newProfile;
            sCurrentMode = newMode;
            sCurrentGain = newGain;
            sCurrentPitch = newPitch;
            sCurrentFormant = newFormant;
            sCurrentUnderflowSilence = newUnderflowSilence;

            if (profileChanged && newProfile != null) {
                String targetVoice = "female_01";
                if ("female".equals(newProfile) || "female_01".equals(newProfile)) {
                    targetVoice = "female_01";
                } else if ("child".equals(newProfile) || "child_01".equals(newProfile)) {
                    targetVoice = "child_01";
                } else if ("deep".equals(newProfile) || "deep_01".equals(newProfile)) {
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
            }

            try {
                NativeCore.enableMicTap("", null, 0, 0,
                        config.nativeMicMode(), config.nativeMicProfile(),
                        config.micInputGain, config.micPitchSemitones, config.micFormantShift,
                        config.micUnderflowSilence);
                Slog.i(TAG, "Dynamically updated native mic settings: mode=" + newMode
                        + " profile=" + newProfile + " gain=" + newGain + " pitch=" + newPitch);
            } catch (Throwable ignored) {}
        }
    }

    private static final java.util.concurrent.ScheduledExecutorService sBackgroundExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NeuralBridge-ConfigWatcher");
                t.setDaemon(true);
                return t;
            });

    static {
        sBackgroundExecutor.scheduleWithFixedDelay(() -> {
            try {
                checkDynamicProfileUpdate();
            } catch (Throwable ignored) {}
        }, 500, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Called from the C++ voice worker on MODE_NEURAL.
     * The guest read thread does not enter this method.
     */
    public static void processFrame(ByteBuffer inBuf, ByteBuffer outBuf, int frames, int channels, int sampleRate) {
        if (inBuf != null) inBuf.order(ByteOrder.nativeOrder());
        if (outBuf != null) outBuf.order(ByteOrder.nativeOrder());

        boolean written = false;
        try {
            written = top.niunaijun.blackbox.fake.service.audio.VoiceFilterRouter.dispatchNeural(
                    inBuf, outBuf, frames, channels, sampleRate);
        } catch (Throwable ignored) {
            written = false;
        }
        if (!written) {
            writeSilence(outBuf);
        }
    }

    private static void writeSilence(ByteBuffer outBuf) {
        if (outBuf == null) return;
        outBuf.rewind();
        while (outBuf.hasRemaining()) {
            outBuf.put((byte) 0);
        }
        outBuf.rewind();
    }
}
