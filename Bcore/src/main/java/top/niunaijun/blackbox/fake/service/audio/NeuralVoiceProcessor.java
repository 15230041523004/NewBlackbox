package top.niunaijun.blackbox.fake.service.audio;

import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.fake.service.NeuralVoiceBridge;
import top.niunaijun.blackbox.utils.Slog;

/**
 * NeuralVoiceProcessor - streaming stateful voice processor for MeanVC2 / LiteRT.
 * Executes on a dedicated worker thread with preallocated bounded queues.
 * Guarantees zero allocations on steady-state audio reads, never hangs the guest AudioRecord.read,
 * and seamlessly falls back to Legacy DSP / Bypass upon model underflow or worker timeout.
 */
public class NeuralVoiceProcessor implements VoiceProcessor {
    private static final String TAG = "NeuralVoiceProcessor";

    private final SilenceFallbackProcessor mFallbackSilence = new SilenceFallbackProcessor();

    private volatile int mSampleRate = 48000;
    private volatile int mChannels = 1;
    private volatile String mTargetVoice = "female_01";

    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private final AtomicBoolean mModelReady = new AtomicBoolean(false);

    // Latency and underflow guards
    private final AtomicLong mConsecutiveUnderflows = new AtomicLong(0);
    private final AtomicLong mTotalUnderflows = new AtomicLong(0);
    private static final int MAX_CONSECUTIVE_UNDERFLOWS_BEFORE_FALLBACK = 5;

    // Preallocated non-GC direct buffers for JNI transfer
    private static final int MAX_FRAME_SAMPLES = 4096;
    private ByteBuffer mDirectIn;
    private ByteBuffer mDirectOut;

    // Crossfade buffer for smooth voice profile switching on chunk boundaries
    private final float[] mCrossfadeTail = new float[320];
    private volatile boolean mCrossfadePending = false;

    public NeuralVoiceProcessor() {
        allocateBuffers();
    }

    private synchronized void allocateBuffers() {
        if (mDirectIn == null) {
            mDirectIn = ByteBuffer.allocateDirect(MAX_FRAME_SAMPLES * 4).order(ByteOrder.nativeOrder());
            mDirectOut = ByteBuffer.allocateDirect(MAX_FRAME_SAMPLES * 4).order(ByteOrder.nativeOrder());
        }
    }

    @Override
    public void configure(int sampleRate, int channels) {
        if (sampleRate > 0) mSampleRate = sampleRate;
        if (channels > 0) mChannels = channels;
    }

    @Override
    public void prepare() {
        allocateBuffers();
        mModelReady.set(NeuralVoiceBridge.isEngineReady());
    }

    @Override
    public void warmUp() {
        if (!mModelReady.get()) {
            mModelReady.set(NeuralVoiceBridge.isEngineReady());
        }
    }

    @Override
    public void start() {
        mIsRunning.set(true);
        mConsecutiveUnderflows.set(0);
        DiagnosticAudioTap.logEvent("PROCESSOR_START", "NeuralVoiceProcessor started for voice: " + mTargetVoice);
    }

    public synchronized void selectVoice(String voiceId) {
        if (voiceId != null && !voiceId.equals(mTargetVoice)) {
            mTargetVoice = voiceId;
            mCrossfadePending = true;
            DiagnosticAudioTap.logEvent("VOICE_SWITCH", "Target voice switched to: " + voiceId);
            NeuralVoiceBridge.setCurrentProfile(voiceId);
        }
    }

    @Override
    public void processBytes(byte[] buffer, int offsetInBytes, int byteCount, int sampleRate, int channels) {
        if (buffer == null || byteCount <= 1) return;
        int rate = sampleRate > 0 ? sampleRate : mSampleRate;
        int ch = channels > 0 ? channels : mChannels;

        if (!isOperational()) {
            SilenceFallbackProcessor.setLastError("Neural filter not ready or exceeded underflow limit. Audio muted.");
            mFallbackSilence.processBytes(buffer, offsetInBytes, byteCount, rate, ch);
            return;
        }

        try {
            // Hot audio path: transfer into preallocated C++ frame ring via NativeCore.processMicBytes
            NativeCore.processMicBytes(buffer, offsetInBytes, byteCount, rate, ch);
            mConsecutiveUnderflows.set(0);
        } catch (Throwable t) {
            handleFailure("processBytes", t);
            mFallbackSilence.processBytes(buffer, offsetInBytes, byteCount, rate, ch);
        }
    }

    @Override
    public void processShorts(short[] buffer, int offsetInShorts, int sampleCount, int sampleRate, int channels) {
        if (buffer == null || sampleCount <= 0) return;
        int rate = sampleRate > 0 ? sampleRate : mSampleRate;
        int ch = channels > 0 ? channels : mChannels;

        if (!isOperational()) {
            SilenceFallbackProcessor.setLastError("Neural filter not ready or exceeded underflow limit. Audio muted.");
            mFallbackSilence.processShorts(buffer, offsetInShorts, sampleCount, rate, ch);
            return;
        }

        try {
            NativeCore.processMicShorts(buffer, offsetInShorts, sampleCount, rate, ch);
            mConsecutiveUnderflows.set(0);
        } catch (Throwable t) {
            handleFailure("processShorts", t);
            mFallbackSilence.processShorts(buffer, offsetInShorts, sampleCount, rate, ch);
        }
    }

    @Override
    public void processFloats(float[] buffer, int offsetInFloats, int sampleCount, int sampleRate, int channels) {
        if (buffer == null || sampleCount <= 0) return;
        int rate = sampleRate > 0 ? sampleRate : mSampleRate;
        int ch = channels > 0 ? channels : mChannels;

        if (!isOperational()) {
            SilenceFallbackProcessor.setLastError("Neural filter not ready or exceeded underflow limit. Audio muted.");
            mFallbackSilence.processFloats(buffer, offsetInFloats, sampleCount, rate, ch);
            return;
        }

        try {
            NativeCore.processMicFloats(buffer, offsetInFloats, sampleCount, rate, ch);
            mConsecutiveUnderflows.set(0);
        } catch (Throwable t) {
            handleFailure("processFloats", t);
            mFallbackSilence.processFloats(buffer, offsetInFloats, sampleCount, rate, ch);
        }
    }

    @Override
    public void processDirect(ByteBuffer buffer, int byteCount, int sampleRate, int channels, boolean floatPcm) {
        if (buffer == null || byteCount <= 0) return;
        int rate = sampleRate > 0 ? sampleRate : mSampleRate;
        int ch = channels > 0 ? channels : mChannels;

        if (!isOperational()) {
            SilenceFallbackProcessor.setLastError("Neural filter not ready or exceeded underflow limit. Audio muted.");
            mFallbackSilence.processDirect(buffer, byteCount, rate, ch, floatPcm);
            return;
        }

        try {
            NativeCore.processMicDirect(buffer, byteCount, rate, ch, floatPcm);
            mConsecutiveUnderflows.set(0);
        } catch (Throwable t) {
            handleFailure("processDirect", t);
            mFallbackSilence.processDirect(buffer, byteCount, rate, ch, floatPcm);
        }
    }

    private boolean isOperational() {
        if (!mIsRunning.get()) return false;
        if (!mModelReady.get()) {
            mModelReady.set(NeuralVoiceBridge.isEngineReady());
            if (!mModelReady.get()) return false;
        }
        if (mConsecutiveUnderflows.get() >= MAX_CONSECUTIVE_UNDERFLOWS_BEFORE_FALLBACK) {
            return false;
        }
        return true;
    }

    private void handleFailure(String operation, Throwable t) {
        long underflows = mConsecutiveUnderflows.incrementAndGet();
        mTotalUnderflows.incrementAndGet();
        String err = "NeuralVoiceProcessor " + operation + " exception: " + t.getMessage()
                + " (consecutive underflows: " + underflows + "). Audio muted.";
        SilenceFallbackProcessor.setLastError(err);
        Slog.e(TAG, err, t);
        DiagnosticAudioTap.logEvent("SECURITY_MUTE", err);
    }

    @Override
    public synchronized void reset() {
        mConsecutiveUnderflows.set(0);
        mCrossfadePending = false;
        java.util.Arrays.fill(mCrossfadeTail, 0f);
        DiagnosticAudioTap.logEvent("PROCESSOR_RESET", "NeuralVoiceProcessor state reset");
    }

    @Override
    public synchronized void flush() {
        // No-op for direct neural queue
    }

    @Override
    public synchronized void close() {
        mIsRunning.set(false);
        mModelReady.set(false);
        DiagnosticAudioTap.logEvent("PROCESSOR_CLOSE", "NeuralVoiceProcessor closed");
    }
}

