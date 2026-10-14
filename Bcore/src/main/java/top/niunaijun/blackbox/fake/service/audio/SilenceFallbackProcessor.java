package top.niunaijun.blackbox.fake.service.audio;

import java.nio.ByteBuffer;
import java.util.Arrays;
import top.niunaijun.blackbox.utils.Slog;

/**
 * SilenceFallbackProcessor - security-first processor.
 * When a filter (DSP or Neural) fails or encounters buffer starvation/error,
 * unmutated voice MUST NEVER leak through (no bypass fallback).
 * Fills the requested buffer area with absolute silence and logs an explicit security error.
 */
public class SilenceFallbackProcessor implements VoiceProcessor {
    private static final String TAG = "SilenceFallbackProcessor";
    private static volatile String sLastError = "Filter unavailable";

    private static final byte[] ZEROS = new byte[4096];

    public static void setLastError(String error) {
        sLastError = error;
        Slog.e(TAG, "SECURITY AUDIO MUTE ACTIVATED: " + error);
        DiagnosticAudioTap.logEvent("SECURITY_MUTE_ACTIVATED", error);
    }

    public static String getLastError() {
        return sLastError;
    }

    @Override
    public void configure(int sampleRate, int channels) {}

    @Override
    public void prepare() {}

    @Override
    public void warmUp() {}

    @Override
    public void start() {}

    @Override
    public void processBytes(byte[] buffer, int offsetInBytes, int byteCount, int sampleRate, int channels) {
        if (buffer == null || byteCount <= 0 || offsetInBytes < 0) return;
        int count = Math.min(byteCount, buffer.length - offsetInBytes);
        Arrays.fill(buffer, offsetInBytes, offsetInBytes + count, (byte) 0);
    }

    @Override
    public void processShorts(short[] buffer, int offsetInShorts, int sampleCount, int sampleRate, int channels) {
        if (buffer == null || sampleCount <= 0 || offsetInShorts < 0) return;
        int count = Math.min(sampleCount, buffer.length - offsetInShorts);
        Arrays.fill(buffer, offsetInShorts, offsetInShorts + count, (short) 0);
    }

    @Override
    public void processFloats(float[] buffer, int offsetInFloats, int sampleCount, int sampleRate, int channels) {
        if (buffer == null || sampleCount <= 0 || offsetInFloats < 0) return;
        int count = Math.min(sampleCount, buffer.length - offsetInFloats);
        Arrays.fill(buffer, offsetInFloats, offsetInFloats + count, 0.0f);
    }

    @Override
    public void processDirect(ByteBuffer buffer, int byteCount, int sampleRate, int channels, boolean floatPcm) {
        if (buffer == null || byteCount <= 0) return;
        int limit = Math.min(byteCount, buffer.capacity());
        ByteBuffer view = buffer.duplicate();
        view.position(0);
        int written = 0;
        while (written < limit) {
            int chunk = Math.min(ZEROS.length, limit - written);
            view.put(ZEROS, 0, chunk);
            written += chunk;
        }
    }

    @Override
    public void reset() {}

    @Override
    public void flush() {}

    @Override
    public void close() {}
}
