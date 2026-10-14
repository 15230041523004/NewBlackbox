package top.niunaijun.blackbox.fake.service.audio;

import java.nio.ByteBuffer;

/**
 * Pure bypass voice processor.
 * Performs strictly zero writes, zero memory touches outside bounds, and zero allocations.
 * Preserves the exact PCM read from hardware.
 */
public class BypassProcessor implements VoiceProcessor {

    @Override
    public void configure(int sampleRate, int channels) {
        // No-op
    }

    @Override
    public void prepare() {
        // No-op
    }

    @Override
    public void warmUp() {
        // No-op
    }

    @Override
    public void start() {
        // No-op
    }

    @Override
    public void processBytes(byte[] buffer, int offsetInBytes, int byteCount, int sampleRate, int channels) {
        // No-op: preserves buffer untouched
    }

    @Override
    public void processShorts(short[] buffer, int offsetInShorts, int sampleCount, int sampleRate, int channels) {
        // No-op: preserves buffer untouched
    }

    @Override
    public void processFloats(float[] buffer, int offsetInFloats, int sampleCount, int sampleRate, int channels) {
        // No-op: preserves buffer untouched
    }

    @Override
    public void processDirect(ByteBuffer buffer, int byteCount, int sampleRate, int channels, boolean floatPcm) {
        // No-op: preserves buffer untouched without mutating position or limit
    }

    @Override
    public void reset() {
        // No-op
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

