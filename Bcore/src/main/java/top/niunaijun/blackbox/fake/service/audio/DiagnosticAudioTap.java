package top.niunaijun.blackbox.fake.service.audio;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Diagnostic tap engine capturing synchronized Source and Processed microphone streams.
 * Uses bounded, preallocated lock-free / non-blocking queue.
 * Live audio thread drops diagnostic copy on buffer full, never blocking live audio.
 * Writer thread runs strictly in background with minimal I/O impact.
 */
public final class DiagnosticAudioTap {
    private static final String TAG = "DiagnosticAudioTap";

    // Diagnostic tap is developer-only and off by default.
    private static volatile boolean sEnabled = false;

    private static final int QUEUE_CAPACITY = 64;
    private static final int MAX_CHUNK_SAMPLES = 2048;

    public enum Stage {
        SOURCE,
        PROCESSED
    }

    public static final class Chunk {
        public Stage stage;
        public long sequenceNumber;
        public long captureTimeNs;
        public int sampleRate;
        public int channels;
        public int frames;
        public final short[] samples = new short[MAX_CHUNK_SAMPLES];
    }

    private static final BlockingQueue<Chunk> sQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final Chunk[] sPool = new Chunk[QUEUE_CAPACITY];
    private static final BlockingQueue<Chunk> sFreePool = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private static final AtomicLong sSequenceCounter = new AtomicLong(0);
    private static final AtomicLong sDroppedChunks = new AtomicLong(0);

    private static final AtomicBoolean sWorkerRunning = new AtomicBoolean(false);
    private static Thread sWriterThread = null;

    private static volatile String sCurrentSessionId = null;
    private static volatile File sSessionDir = null;
    private static volatile FileOutputStream sSourcePcmStream = null;
    private static volatile FileOutputStream sProcessedPcmStream = null;
    private static volatile FileOutputStream sEventsStream = null;
    private static volatile int sSourceBytesWritten = 0;
    private static volatile int sProcessedBytesWritten = 0;

    static {
        for (int i = 0; i < QUEUE_CAPACITY; i++) {
            sPool[i] = new Chunk();
            sFreePool.offer(sPool[i]);
        }
    }

    private DiagnosticAudioTap() {}

    public static synchronized void setEnabled(boolean enabled, File baseDir) {
        if (sEnabled == enabled) return;
        sEnabled = enabled;
        if (enabled) {
            startSession(baseDir);
        } else {
            stopSession();
        }
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static String getSessionId() {
        return sCurrentSessionId;
    }

    private static synchronized void startSession(File baseDir) {
        try {
            sCurrentSessionId = UUID.randomUUID().toString().substring(0, 8);
            File root = baseDir != null ? new File(baseDir, "diagnostics/" + sCurrentSessionId)
                    : new File("/sdcard/Download/BlackTwin/diagnostics/" + sCurrentSessionId);
            if (!root.exists()) root.mkdirs();
            sSessionDir = root;

            File sourceRaw = new File(root, "source.raw");
            File processedRaw = new File(root, "processed.raw");
            File eventsFile = new File(root, "events.jsonl");

            sSourcePcmStream = new FileOutputStream(sourceRaw);
            sProcessedPcmStream = new FileOutputStream(processedRaw);
            sEventsStream = new FileOutputStream(eventsFile);

            sSourceBytesWritten = 0;
            sProcessedBytesWritten = 0;
            sDroppedChunks.set(0);

            logEvent("SESSION_START", "Diagnostic recording session started: " + sCurrentSessionId);

            writeSessionJson(root, sCurrentSessionId);
            writeDeviceJson(root);

            sWorkerRunning.set(true);
            sWriterThread = new Thread(new WriterRunnable(), "BT-DiagWriter");
            sWriterThread.setPriority(Thread.MIN_PRIORITY);
            sWriterThread.start();

            Slog.i(TAG, "DiagnosticAudioTap session started: " + root.getAbsolutePath());
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to start DiagnosticAudioTap session: " + t.getMessage());
            sEnabled = false;
        }
    }

    public static synchronized void stopSession() {
        if (!sWorkerRunning.get()) return;
        logEvent("SESSION_STOP", "Diagnostic recording session stopping: " + sCurrentSessionId);
        sWorkerRunning.set(false);
        if (sWriterThread != null) {
            sWriterThread.interrupt();
            try {
                sWriterThread.join(1000);
            } catch (InterruptedException ignored) {}
            sWriterThread = null;
        }

        // Close streams and convert raw PCM to standard WAV
        try {
            if (sSourcePcmStream != null) {
                sSourcePcmStream.close();
                sSourcePcmStream = null;
            }
            if (sProcessedPcmStream != null) {
                sProcessedPcmStream.close();
                sProcessedPcmStream = null;
            }
            if (sEventsStream != null) {
                sEventsStream.close();
                sEventsStream = null;
            }

            if (sSessionDir != null && sSessionDir.exists()) {
                File srcRaw = new File(sSessionDir, "source.raw");
                File procRaw = new File(sSessionDir, "processed.raw");
                File srcWav = new File(sSessionDir, "source.wav");
                File procWav = new File(sSessionDir, "processed.wav");

                rawToWav(srcRaw, srcWav, 48000, 1);
                rawToWav(procRaw, procWav, 48000, 1);
                writeMetricsJson(sSessionDir);
            }
            Slog.i(TAG, "DiagnosticAudioTap session finalized: " + sCurrentSessionId);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to finalize DiagnosticAudioTap session: " + t.getMessage());
        } finally {
            sCurrentSessionId = null;
            sSessionDir = null;
        }
    }

    public static void logEvent(String eventType, String detail) {
        if (!sEnabled || sEventsStream == null) return;
        try {
            long nowNs = System.nanoTime();
            String line = "{\"timestampNs\":" + nowNs + ",\"event\":\"" + eventType
                    + "\",\"detail\":\"" + detail.replace("\"", "\\\"") + "\"}\n";
            synchronized (sEventsStream) {
                sEventsStream.write(line.getBytes("UTF-8"));
                sEventsStream.flush();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Records audio chunk non-blockingly from hot audio path with paired sequence and timestamp.
     */
    public static void recordChunk(Stage stage, long seq, long timeNs,
                                   short[] buffer, int offset, int count, int sampleRate, int channels) {
        if (!sEnabled || buffer == null || count <= 0) return;

        Chunk chunk = sFreePool.poll();
        if (chunk == null) {
            sDroppedChunks.incrementAndGet();
            return; // Drop diagnostic copy immediately, never stall audio thread
        }

        chunk.stage = stage;
        chunk.sequenceNumber = seq;
        chunk.captureTimeNs = timeNs;
        chunk.sampleRate = sampleRate;
        chunk.channels = channels;
        int frames = Math.min(count, MAX_CHUNK_SAMPLES);
        chunk.frames = frames;
        System.arraycopy(buffer, offset, chunk.samples, 0, frames);

        if (!sQueue.offer(chunk)) {
            sDroppedChunks.incrementAndGet();
            sFreePool.offer(chunk);
        }
    }

    public static void recordBytesChunk(Stage stage, long seq, long timeNs,
                                        byte[] buffer, int offset, int byteCount, int sampleRate, int channels) {
        if (!sEnabled || buffer == null || byteCount < 2) return;
        Chunk chunk = sFreePool.poll();
        if (chunk == null) {
            sDroppedChunks.incrementAndGet();
            return;
        }
        chunk.stage = stage;
        chunk.sequenceNumber = seq;
        chunk.captureTimeNs = timeNs;
        chunk.sampleRate = sampleRate;
        chunk.channels = channels;
        int samples = Math.min(byteCount / 2, MAX_CHUNK_SAMPLES);
        chunk.frames = samples;
        for (int i = 0; i < samples; i++) {
            int off = offset + i * 2;
            int lo = buffer[off] & 0xFF;
            int hi = buffer[off + 1];
            chunk.samples[i] = (short) ((hi << 8) | lo);
        }

        if (!sQueue.offer(chunk)) {
            sDroppedChunks.incrementAndGet();
            sFreePool.offer(chunk);
        }
    }

    public static void recordFloatsChunk(Stage stage, long seq, long timeNs,
                                         float[] buffer, int offset, int count, int sampleRate, int channels) {
        if (!sEnabled || buffer == null || count <= 0) return;
        Chunk chunk = sFreePool.poll();
        if (chunk == null) {
            sDroppedChunks.incrementAndGet();
            return;
        }
        chunk.stage = stage;
        chunk.sequenceNumber = seq;
        chunk.captureTimeNs = timeNs;
        chunk.sampleRate = sampleRate;
        chunk.channels = channels;
        int samples = Math.min(count, MAX_CHUNK_SAMPLES);
        chunk.frames = samples;
        for (int i = 0; i < samples; i++) {
            float val = buffer[offset + i];
            float clamped = val < -1.0f ? -1.0f : (val > 1.0f ? 1.0f : val);
            chunk.samples[i] = (short) (clamped * 32767.0f);
        }

        if (!sQueue.offer(chunk)) {
            sDroppedChunks.incrementAndGet();
            sFreePool.offer(chunk);
        }
    }

    public static void recordDirectChunk(Stage stage, long seq, long timeNs,
                                         ByteBuffer buffer, int byteCount, int sampleRate, int channels, boolean floatPcm) {
        if (!sEnabled || buffer == null || byteCount <= 0) return;
        Chunk chunk = sFreePool.poll();
        if (chunk == null) {
            sDroppedChunks.incrementAndGet();
            return;
        }
        chunk.stage = stage;
        chunk.sequenceNumber = seq;
        chunk.captureTimeNs = timeNs;
        chunk.sampleRate = sampleRate;
        chunk.channels = channels;

        // Duplicate read-only view to never mutate position/limit
        ByteBuffer view = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        view.position(0);

        if (floatPcm) {
            int floatCount = Math.min(byteCount / 4, MAX_CHUNK_SAMPLES);
            chunk.frames = floatCount;
            for (int i = 0; i < floatCount; i++) {
                float val = view.getFloat();
                float clamped = val < -1.0f ? -1.0f : (val > 1.0f ? 1.0f : val);
                chunk.samples[i] = (short) (clamped * 32767.0f);
            }
        } else {
            int shortCount = Math.min(byteCount / 2, MAX_CHUNK_SAMPLES);
            chunk.frames = shortCount;
            for (int i = 0; i < shortCount; i++) {
                chunk.samples[i] = view.getShort();
            }
        }

        if (!sQueue.offer(chunk)) {
            sDroppedChunks.incrementAndGet();
            sFreePool.offer(chunk);
        }
    }

    private static class WriterRunnable implements Runnable {
        private final byte[] byteBuffer = new byte[MAX_CHUNK_SAMPLES * 2];

        @Override
        public void run() {
            while (sWorkerRunning.get() || !sQueue.isEmpty()) {
                try {
                    Chunk chunk = sQueue.poll();
                    if (chunk == null) {
                        Thread.sleep(20);
                        continue;
                    }

                    int bytes = chunk.frames * 2;
                    for (int i = 0; i < chunk.frames; i++) {
                        short s = chunk.samples[i];
                        byteBuffer[i * 2] = (byte) (s & 0xFF);
                        byteBuffer[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                    }

                    if (chunk.stage == Stage.SOURCE && sSourcePcmStream != null) {
                        sSourcePcmStream.write(byteBuffer, 0, bytes);
                        sSourceBytesWritten += bytes;
                    } else if (chunk.stage == Stage.PROCESSED && sProcessedPcmStream != null) {
                        sProcessedPcmStream.write(byteBuffer, 0, bytes);
                        sProcessedBytesWritten += bytes;
                    }

                    sFreePool.offer(chunk);
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable t) {
                    Slog.w(TAG, "WriterRunnable error: " + t.getMessage());
                }
            }
        }
    }

    private static void writeSessionJson(File dir, String sessionId) {
        try {
            File f = new File(dir, "session.json");
            String json = "{\n"
                    + "  \"sessionId\": \"" + sessionId + "\",\n"
                    + "  \"startTimeNs\": " + System.nanoTime() + ",\n"
                    + "  \"sampleRate\": 48000,\n"
                    + "  \"channels\": 1,\n"
                    + "  \"encoding\": \"PCM_16BIT\",\n"
                    + "  \"targetBackend\": \"LITERT_CPU\",\n"
                    + "  \"developerMode\": true\n"
                    + "}\n";
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(json.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }

    private static void writeDeviceJson(File dir) {
        try {
            File f = new File(dir, "device.json");
            String json = "{\n"
                    + "  \"manufacturer\": \"" + android.os.Build.MANUFACTURER + "\",\n"
                    + "  \"model\": \"" + android.os.Build.MODEL + "\",\n"
                    + "  \"sdkInt\": " + android.os.Build.VERSION.SDK_INT + ",\n"
                    + "  \"cpuAbi\": \"" + android.os.Build.SUPPORTED_ABIS[0] + "\"\n"
                    + "}\n";
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(json.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }

    private static void writeMetricsJson(File dir) {
        try {
            File f = new File(dir, "metrics.json");
            String json = "{\n"
                    + "  \"sourceBytes\": " + sSourceBytesWritten + ",\n"
                    + "  \"processedBytes\": " + sProcessedBytesWritten + ",\n"
                    + "  \"droppedChunks\": " + sDroppedChunks.get() + ",\n"
                    + "  \"totalChunks\": " + sSequenceCounter.get() + "\n"
                    + "}\n";
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(json.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }

    private static void rawToWav(File raw, File wav, int sampleRate, int channels) {
        if (!raw.exists() || raw.length() == 0) return;
        try {
            long pcmSize = raw.length();
            long totalDataLen = pcmSize + 36;
            long byteRate = sampleRate * channels * 2L;

            RandomAccessFile wavRaf = new RandomAccessFile(wav, "rw");
            wavRaf.setLength(0);

            // Write 44-byte WAV header
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put(new byte[]{'R', 'I', 'F', 'F'});
            header.putInt((int) totalDataLen);
            header.put(new byte[]{'W', 'A', 'V', 'E'});
            header.put(new byte[]{'f', 'm', 't', ' '});
            header.putInt(16); // subchunk1size (16 for PCM)
            header.putShort((short) 1); // audio format (1 = PCM)
            header.putShort((short) channels);
            header.putInt(sampleRate);
            header.putInt((int) byteRate);
            header.putShort((short) (channels * 2)); // block align
            header.putShort((short) 16); // bits per sample
            header.put(new byte[]{'d', 'a', 't', 'a'});
            header.putInt((int) pcmSize);

            wavRaf.write(header.array());

            RandomAccessFile rawRaf = new RandomAccessFile(raw, "r");
            byte[] buf = new byte[8192];
            int n;
            while ((n = rawRaf.read(buf)) > 0) {
                wavRaf.write(buf, 0, n);
            }
            rawRaf.close();
            wavRaf.close();
            raw.delete(); // cleanup raw file
        } catch (Throwable t) {
            Slog.w(TAG, "rawToWav conversion error: " + t.getMessage());
        }
    }
}

