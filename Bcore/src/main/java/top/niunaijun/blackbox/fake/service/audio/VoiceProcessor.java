package top.niunaijun.blackbox.fake.service.audio;

import java.io.Closeable;
import java.nio.ByteBuffer;

/**
 * Universal contract for voice processors in BlackTwin (Bypass, Legacy DSP, and future Neural).
 * Guarantees bounded processing over actually read slices without heap allocations or buffer corruption.
 */
public interface VoiceProcessor extends Closeable {
    /**
     * Configures the stream parameters.
     */
    void configure(int sampleRate, int channels);

    /**
     * Prepares internal state/buffers.
     */
    void prepare();

    /**
     * Warms up inference or audio filters before live audio starts.
     */
    void warmUp();

    /**
     * Signals start of active audio recording.
     */
    void start();

    /**
     * Processes in-place byte[] PCM data (typically 16-bit PCM little-endian).
     *
     * @param buffer source/destination buffer
     * @param offsetInBytes starting offset in bytes
     * @param byteCount number of bytes actually read by AudioRecord.read
     * @param sampleRate sample rate in Hz
     * @param channels channel count
     */
    void processBytes(byte[] buffer, int offsetInBytes, int byteCount, int sampleRate, int channels);

    /**
     * Processes in-place short[] PCM data (16-bit samples).
     *
     * @param buffer source/destination buffer
     * @param offsetInShorts starting offset in shorts
     * @param sampleCount number of shorts actually read by AudioRecord.read
     * @param sampleRate sample rate in Hz
     * @param channels channel count
     */
    void processShorts(short[] buffer, int offsetInShorts, int sampleCount, int sampleRate, int channels);

    /**
     * Processes in-place float[] PCM data (normalized float samples).
     *
     * @param buffer source/destination buffer
     * @param offsetInFloats starting offset in floats
     * @param sampleCount number of floats actually read by AudioRecord.read
     * @param sampleRate sample rate in Hz
     * @param channels channel count
     */
    void processFloats(float[] buffer, int offsetInFloats, int sampleCount, int sampleRate, int channels);

    /**
     * Processes in-place direct ByteBuffer PCM data.
     * Note: Buffer position and limit must NOT be altered to preserve native engine expectations.
     *
     * @param buffer direct ByteBuffer
     * @param byteCount number of bytes actually read by AudioRecord.read
     * @param sampleRate sample rate in Hz
     * @param channels channel count
     * @param floatPcm true if buffer contains 32-bit float samples, false if 16-bit PCM
     */
    void processDirect(ByteBuffer buffer, int byteCount, int sampleRate, int channels, boolean floatPcm);

    /**
     * Resets filter and delay lines across start/stop boundaries.
     */
    void reset();

    /**
     * Flushes remaining frames at end-of-stream.
     */
    void flush();

    /**
     * Releases any held native or system resources.
     */
    @Override
    void close();
}

