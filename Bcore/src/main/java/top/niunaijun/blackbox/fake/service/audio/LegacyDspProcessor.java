package top.niunaijun.blackbox.fake.service.audio;

import java.nio.ByteBuffer;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Wraps existing native DSP processor through NativeCore.processMic* bindings.
 * Preserves exact existing DSP algorithms and bounds.
 */
public class LegacyDspProcessor implements VoiceProcessor {
    private static final String TAG = "LegacyDspProcessor";

    private final SilenceFallbackProcessor mFallbackSilence = new SilenceFallbackProcessor();
    private volatile int mSampleRate = 48000;
    private volatile int mChannels = 1;

    @Override
    public void configure(int sampleRate, int channels) {
        if (sampleRate > 0) mSampleRate = sampleRate;
        if (channels > 0) mChannels = channels;
    }

    @Override
    public void prepare() {
        // Ready
    }

    @Override
    public void warmUp() {
        // Native DSP state initializes on first stream block
    }

    @Override
    public void start() {
        // Started
    }

    @Override
    public void processBytes(byte[] buffer, int offsetInBytes, int byteCount, int sampleRate, int channels) {
        if (buffer == null || byteCount <= 1) return;
        int rate = sampleRate > 0 ? sampleRate : mSampleRate;
        int ch = channels > 0 ? channels : mChannels;
        try {
            NativeCore.processMicBytes(buffer, offsetInBytes, byteCount, rate, ch);
        } catch (Throwable t) {
            String err = "Legacy DSP processBytes failed: " + t.getMessage() + ". Audio muted.";
            SilenceFallbackProcessor.setLastError(err);
            DiagnosticAudioTap.logEvent("SECURITY_MUTE", err);
            mFallbackSilence.processBytes(buffer, offsetInBytes, byteCount, rate, ch);
        }
    }

    @Override
    public void processShorts(short[] buffer, int offsetInShorts, int sampleCount, int sampleRate, int channels) {
        if (buffer == null || sampleCount <= 0) return;
        int rate = sampleRate > 0 ? sampleRate : mSampleRate;
        int ch = channels > 0 ? channels : mChannels;
        try {
            NativeCore.processMicShorts(buffer, offsetInShorts, sampleCount, rate, ch);
        } catch (Throwable t) {
            String err = "Legacy DSP processShorts failed: " + t.getMessage() + ". Audio muted.";
            SilenceFallbackProcessor.setLastError(err);
            DiagnosticAudioTap.logEvent("SECURITY_MUTE", err);
            mFallbackSilence.processShorts(buffer, offsetInShorts, sampleCount, rate, ch);
        }
    }

    @Override
    public void processFloats(float[] buffer, int offsetInFloats, int sampleCount, int sampleRate, int channels) {
        if (buffer == null || sampleCount <= 0) return;
        int rate = sampleRate > 0 ? sampleRate : mSampleRate;
        int ch = channels > 0 ? channels : mChannels;
        try {
            NativeCore.processMicFloats(buffer, offsetInFloats, sampleCount, rate, ch);
        } catch (Throwable t) {
            String err = "Legacy DSP processFloats failed: " + t.getMessage() + ". Audio muted.";
            SilenceFallbackProcessor.setLastError(err);
            DiagnosticAudioTap.logEvent("SECURITY_MUTE", err);
            mFallbackSilence.processFloats(buffer, offsetInFloats, sampleCount, rate, ch);
        }
    }

    @Override
    public void processDirect(ByteBuffer buffer, int byteCount, int sampleRate, int channels, boolean floatPcm) {
        if (buffer == null || byteCount <= 0) return;
        int rate = sampleRate > 0 ? sampleRate : mSampleRate;
        int ch = channels > 0 ? channels : mChannels;
        try {
            NativeCore.processMicDirect(buffer, byteCount, rate, ch, floatPcm);
        } catch (Throwable t) {
            String err = "Legacy DSP processDirect failed: " + t.getMessage() + ". Audio muted.";
            SilenceFallbackProcessor.setLastError(err);
            DiagnosticAudioTap.logEvent("SECURITY_MUTE", err);
            mFallbackSilence.processDirect(buffer, byteCount, rate, ch, floatPcm);
        }
    }

    @Override
    public void reset() {
        // Native C++ DSP worker ring buffers handle reset across AudioRecord start/stop
    }

    @Override
    public void flush() {
        // No-op
    }

    @Override
    public void close() {
        // No-op
    }
}

