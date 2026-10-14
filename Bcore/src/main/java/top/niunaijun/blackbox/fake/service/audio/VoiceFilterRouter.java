package top.niunaijun.blackbox.fake.service.audio;

import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import top.niunaijun.blackbox.fake.service.NeuralVoiceBridge;
import top.niunaijun.blackbox.utils.AppSpoofConfig;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.VirtualResourceManager;

/**
 * Thread-safe router dispatching live microphone audio between Bypass and Legacy DSP.
 * Ensures zero allocations in audio loop and safe fallback to original audio on error.
 * Neural processing is intentionally gated behind feature flag in this stage.
 */
public final class VoiceFilterRouter {
    private static final String TAG = "VoiceFilterRouter";

    // Feature flag: enabled for live neural voice replacement
    public static final boolean FEATURE_NEURAL_ENABLED = true;

    private static final VoiceProcessor BYPASS = new BypassProcessor();
    private static final VoiceProcessor LEGACY_DSP = new LegacyDspProcessor();
    private static final NeuralVoiceProcessor NEURAL = new NeuralVoiceProcessor();
    private static final SilenceFallbackProcessor SILENCE_FALLBACK = new SilenceFallbackProcessor();

    private static final AtomicReference<VoiceProcessor> sActiveProcessor = new AtomicReference<>(BYPASS);
    private static volatile String sActiveMode = "bypass";
    private static volatile String sActiveProfile = "default";
    private static volatile long sLastConfigCheck = 0;

    // Monotonic sequence counter for paired SourceTap and ProcessedTap
    private static final AtomicLong sSequenceCounter = new AtomicLong(0);

    // Zero-allocation diagnostics counters
    public static final AtomicLong sTotalBytesProcessed = new AtomicLong(0);
    public static final AtomicLong sTotalShortsProcessed = new AtomicLong(0);
    public static final AtomicLong sTotalFloatsProcessed = new AtomicLong(0);
    public static final AtomicLong sTotalDirectProcessed = new AtomicLong(0);
    public static final AtomicLong sErrorCount = new AtomicLong(0);

    private VoiceFilterRouter() {}

    /**
     * Resolves the processor based on current package configuration.
     * Uses rate-limited polling to avoid overhead in the tight audio loop.
     */
    public static VoiceProcessor getProcessor() {
        long now = SystemClock.uptimeMillis();
        if (now - sLastConfigCheck > 500) {
            sLastConfigCheck = now;
            updateMode();
        }
        return sActiveProcessor.get();
    }

    public static synchronized void updateMode() {
        try {
            String pkg = VirtualResourceManager.currentPackage();
            AppSpoofConfig config = VirtualResourceManager.getSpoofConfig(pkg);
            if (config == null) {
                if (!"bypass".equals(sActiveMode)) {
                    String err = "No spoof config for " + pkg + ", muting audio for security.";
                    SilenceFallbackProcessor.setLastError(err);
                    setProcessor("silence", "error", SILENCE_FALLBACK);
                } else {
                    setProcessor("bypass", "default", BYPASS);
                }
                return;
            }

            if (config.grantMic) {
                setProcessor("bypass", "default", BYPASS);
                return;
            }

            String mode = config.micMode;
            String profile = config.micProfile;

            if ("neural".equals(mode)) {
                if (!NeuralVoiceBridge.isEngineReady()) {
                    top.niunaijun.blackbox.fake.service.SpoofHookInstaller.initNeuralEngine(pkg);
                }
                if (FEATURE_NEURAL_ENABLED && NeuralVoiceBridge.isEngineReady()) {
                    NEURAL.selectVoice(profile);
                    setProcessor("neural", profile, NEURAL);
                } else {
                    String err = "Neural filter requested (" + profile + ") but engine is not ready (featureEnabled="
                            + FEATURE_NEURAL_ENABLED + ", engineReady=" + NeuralVoiceBridge.isEngineReady()
                            + "). Audio muted for security.";
                    SilenceFallbackProcessor.setLastError(err);
                    DiagnosticAudioTap.logEvent("SECURITY_MUTE", err);
                    setProcessor("silence", profile, SILENCE_FALLBACK);
                }
            } else if ("dsp".equals(mode)) {
                setProcessor("dsp", profile, LEGACY_DSP);
            } else if ("silence".equals(mode)) {
                setProcessor("silence", profile, SILENCE_FALLBACK);
            } else if ("bypass".equals(mode)) {
                setProcessor("bypass", profile, BYPASS);
            } else {
                String err = "Filter mode '" + mode + "' active; live microphone muted.";
                SilenceFallbackProcessor.setLastError(err);
                DiagnosticAudioTap.logEvent("SECURITY_MUTE", err);
                setProcessor("silence", profile, SILENCE_FALLBACK);
            }
        } catch (Throwable t) {
            sErrorCount.incrementAndGet();
            String err = "Error resolving spoof config: " + t.getMessage() + ". Audio muted for security.";
            Slog.e(TAG, err, t);
            SilenceFallbackProcessor.setLastError(err);
            DiagnosticAudioTap.logEvent("SECURITY_MUTE", err);
            setProcessor("silence", "error", SILENCE_FALLBACK);
        }
    }

    private static void setProcessor(String mode, String profile, VoiceProcessor processor) {
        if (!mode.equals(sActiveMode) || !profile.equals(sActiveProfile) || processor != sActiveProcessor.get()) {
            sActiveMode = mode;
            sActiveProfile = profile;
            VoiceProcessor old = sActiveProcessor.getAndSet(processor);
            if (old != null && old != processor) {
                old.reset();
            }
            processor.prepare();
            processor.warmUp();
            processor.start();
            DiagnosticAudioTap.logEvent("BACKEND_SWITCH", "Switched to mode=" + mode + ", profile=" + profile);
            Slog.i(TAG, "VoiceFilterRouter switched to mode=" + mode + ", profile=" + profile
                    + ", processor=" + processor.getClass().getSimpleName());
        }
    }

    public static String getActiveMode() {
        return sActiveMode;
    }

    public static String getActiveProfile() {
        return sActiveProfile;
    }

    public static void onStartRecording(Object audioRecord) {
        DiagnosticAudioTap.logEvent("START_RECORDING", "AudioRecord started recording");
        VoiceProcessor p = getProcessor();
        if (p != null) {
            p.reset();
            p.start();
        }
    }

    public static void onStopRecording(Object audioRecord) {
        DiagnosticAudioTap.logEvent("STOP_RECORDING", "AudioRecord stopped recording");
        VoiceProcessor p = sActiveProcessor.get();
        if (p != null) {
            p.flush();
            p.reset();
        }
    }

    public static void onRelease(Object audioRecord) {
        DiagnosticAudioTap.logEvent("RELEASE", "AudioRecord released");
        VoiceProcessor p = sActiveProcessor.get();
        if (p != null) {
            p.reset();
        }
    }

    public static void processBytes(Object self, byte[] buffer, int offsetInBytes, int byteCount, int sampleRate, int channels) {
        if (buffer == null || byteCount <= 1 || offsetInBytes < 0) return;
        if (offsetInBytes + byteCount > buffer.length) return;
        long seq = sSequenceCounter.incrementAndGet();
        long timeNs = System.nanoTime();
        try {
            // SourceTap: capture pristine raw audio prior to modification
            DiagnosticAudioTap.recordBytesChunk(DiagnosticAudioTap.Stage.SOURCE, seq, timeNs,
                    buffer, offsetInBytes, byteCount, sampleRate, channels);

            VoiceProcessor processor = getProcessor();
            if (processor == null) {
                if (!"bypass".equals(sActiveMode)) {
                    SILENCE_FALLBACK.processBytes(buffer, offsetInBytes, byteCount, sampleRate, channels);
                }
            } else {
                processor.processBytes(buffer, offsetInBytes, byteCount, sampleRate, channels);
            }
            sTotalBytesProcessed.addAndGet(byteCount);

            // ProcessedTap: capture audio actually returned to the guest app with matching seq and time
            DiagnosticAudioTap.recordBytesChunk(DiagnosticAudioTap.Stage.PROCESSED, seq, timeNs,
                    buffer, offsetInBytes, byteCount, sampleRate, channels);
        } catch (Throwable t) {
            sErrorCount.incrementAndGet();
            String err = "VoiceFilterRouter processBytes error: " + t.getMessage() + ". Audio muted.";
            Slog.e(TAG, err, t);
            SilenceFallbackProcessor.setLastError(err);
            DiagnosticAudioTap.logEvent("SECURITY_MUTE", err);
            if (!"bypass".equals(sActiveMode)) {
                SILENCE_FALLBACK.processBytes(buffer, offsetInBytes, byteCount, sampleRate, channels);
            }
        }
    }

    public static void processShorts(Object self, short[] buffer, int offsetInShorts, int sampleCount, int sampleRate, int channels) {
        if (buffer == null || sampleCount <= 0 || offsetInShorts < 0) return;
        if (offsetInShorts + sampleCount > buffer.length) return;
        long seq = sSequenceCounter.incrementAndGet();
        long timeNs = System.nanoTime();
        try {
            // SourceTap
            DiagnosticAudioTap.recordChunk(DiagnosticAudioTap.Stage.SOURCE, seq, timeNs,
                    buffer, offsetInShorts, sampleCount, sampleRate, channels);

            VoiceProcessor processor = getProcessor();
            if (processor == null) {
                if (!"bypass".equals(sActiveMode)) {
                    SILENCE_FALLBACK.processShorts(buffer, offsetInShorts, sampleCount, sampleRate, channels);
                }
            } else {
                processor.processShorts(buffer, offsetInShorts, sampleCount, sampleRate, channels);
            }
            sTotalShortsProcessed.addAndGet(sampleCount);

            // ProcessedTap
            DiagnosticAudioTap.recordChunk(DiagnosticAudioTap.Stage.PROCESSED, seq, timeNs,
                    buffer, offsetInShorts, sampleCount, sampleRate, channels);
        } catch (Throwable t) {
            sErrorCount.incrementAndGet();
            String err = "VoiceFilterRouter processShorts error: " + t.getMessage() + ". Audio muted.";
            Slog.e(TAG, err, t);
            SilenceFallbackProcessor.setLastError(err);
            DiagnosticAudioTap.logEvent("SECURITY_MUTE", err);
            if (!"bypass".equals(sActiveMode)) {
                SILENCE_FALLBACK.processShorts(buffer, offsetInShorts, sampleCount, sampleRate, channels);
            }
        }
    }

    public static void processFloats(Object self, float[] buffer, int offsetInFloats, int sampleCount, int sampleRate, int channels) {
        if (buffer == null || sampleCount <= 0 || offsetInFloats < 0) return;
        if (offsetInFloats + sampleCount > buffer.length) return;
        long seq = sSequenceCounter.incrementAndGet();
        long timeNs = System.nanoTime();
        try {
            // SourceTap
            DiagnosticAudioTap.recordFloatsChunk(DiagnosticAudioTap.Stage.SOURCE, seq, timeNs,
                    buffer, offsetInFloats, sampleCount, sampleRate, channels);

            VoiceProcessor processor = getProcessor();
            if (processor == null) {
                if (!"bypass".equals(sActiveMode)) {
                    SILENCE_FALLBACK.processFloats(buffer, offsetInFloats, sampleCount, sampleRate, channels);
                }
            } else {
                processor.processFloats(buffer, offsetInFloats, sampleCount, sampleRate, channels);
            }
            sTotalFloatsProcessed.addAndGet(sampleCount);

            // ProcessedTap
            DiagnosticAudioTap.recordFloatsChunk(DiagnosticAudioTap.Stage.PROCESSED, seq, timeNs,
                    buffer, offsetInFloats, sampleCount, sampleRate, channels);
        } catch (Throwable t) {
            sErrorCount.incrementAndGet();
            String err = "VoiceFilterRouter processFloats error: " + t.getMessage() + ". Audio muted.";
            Slog.e(TAG, err, t);
            SilenceFallbackProcessor.setLastError(err);
            DiagnosticAudioTap.logEvent("SECURITY_MUTE", err);
            if (!"bypass".equals(sActiveMode)) {
                SILENCE_FALLBACK.processFloats(buffer, offsetInFloats, sampleCount, sampleRate, channels);
            }
        }
    }

    public static void processDirect(Object self, ByteBuffer buffer, int byteCount, int sampleRate, int channels, boolean floatPcm) {
        if (buffer == null || byteCount <= 0) return;
        long seq = sSequenceCounter.incrementAndGet();
        long timeNs = System.nanoTime();
        try {
            // SourceTap
            DiagnosticAudioTap.recordDirectChunk(DiagnosticAudioTap.Stage.SOURCE, seq, timeNs,
                    buffer, byteCount, sampleRate, channels, floatPcm);

            VoiceProcessor processor = getProcessor();
            if (processor == null) {
                if (!"bypass".equals(sActiveMode)) {
                    SILENCE_FALLBACK.processDirect(buffer, byteCount, sampleRate, channels, floatPcm);
                }
            } else {
                processor.processDirect(buffer, byteCount, sampleRate, channels, floatPcm);
            }
            sTotalDirectProcessed.addAndGet(byteCount);

            // ProcessedTap
            DiagnosticAudioTap.recordDirectChunk(DiagnosticAudioTap.Stage.PROCESSED, seq, timeNs,
                    buffer, byteCount, sampleRate, channels, floatPcm);
        } catch (Throwable t) {
            sErrorCount.incrementAndGet();
            String err = "VoiceFilterRouter processDirect error: " + t.getMessage() + ". Audio muted.";
            Slog.e(TAG, err, t);
            SilenceFallbackProcessor.setLastError(err);
            DiagnosticAudioTap.logEvent("SECURITY_MUTE", err);
            if (!"bypass".equals(sActiveMode)) {
                SILENCE_FALLBACK.processDirect(buffer, byteCount, sampleRate, channels, floatPcm);
            }
        }
    }

    public static String getLastError() {
        return SilenceFallbackProcessor.getLastError();
    }

    public static boolean isSecurityMuteActive() {
        return sActiveProcessor.get() instanceof SilenceFallbackProcessor;
    }
}

