package top.niunaijun.blackbox.fake.service;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.lang.reflect.Method;
import java.util.Arrays;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.VirtualResourceManager;

/**
 * VirtualMicProxy – hooks android.media.AudioRecord.
 *
 * Three operating modes (determined at hook time by files present in the
 * virtual resource directory for the current package):
 *
 *   1. mute   – mic directory contains NO audio file → all read() calls return
 *               silence (zero-filled PCM).
 *   2. file   – mic/audio.wav (or audio.pcm) exists → raw PCM bytes are looped
 *               back on every read() call.
 *   3. stream – (future) mic/audio.wav may also be pulled from the camera video
 *               track; implementation placeholder here.
 *
 * Resource path: <filesDir>/virtual/<profile>/<package>/mic/audio.wav
 */
public class VirtualMicProxy extends ClassInvocationStub {
    public static final String TAG = "VirtualMicProxy";

    /** Cached PCM bytes per invocation (reset on new AudioRecord). */
    private static volatile byte[] sCachedPcm = null;
    private static volatile int sPcmPos = 0;
    private static volatile long sPcmStamp = Long.MIN_VALUE;
    private static volatile int sSampleRate = 44100;
    private static volatile int sChannels = 1;

    public VirtualMicProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        // ClassInvocationStub – hooks are method-level.
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    @ProxyMethod("<init>")
    public static class Constructor extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioRecord constructor – loading virtual PCM");
            loadPcm();
            return method.invoke(who, args);
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @ProxyMethod("startRecording")
    public static class StartRecording extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioRecord.startRecording");
            sPcmPos = 0; // rewind
            loadPcm();   // reload in case file changed
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("stop")
    public static class Stop extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioRecord.stop");
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("release")
    public static class Release extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioRecord.release");
            sCachedPcm = null;
            sPcmPos = 0;
            return method.invoke(who, args);
        }
    }

    // -------------------------------------------------------------------------
    // read() overloads – all deliver virtual PCM or silence
    // -------------------------------------------------------------------------

    /** read(byte[] audioData, int offsetInBytes, int sizeInBytes) */
    @ProxyMethod("read")
    public static class Read extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String pkg = VirtualResourceManager.currentPackage();
            if (!VirtualResourceManager.spoofMic(pkg)) {
                return method.invoke(who, args);
            }

            if (args != null && args.length >= 3
                    && args[0] instanceof byte[]
                    && args[1] instanceof Integer
                    && args[2] instanceof Integer) {
                byte[] buffer = (byte[]) args[0];
                int offset = (Integer) args[1];
                int size   = (Integer) args[2];
                return fillBuffer(buffer, offset, size);
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getState")
    public static class GetState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return AudioRecord.STATE_INITIALIZED;
        }
    }

    @ProxyMethod("getRecordingState")
    public static class GetRecordingState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return AudioRecord.RECORDSTATE_RECORDING;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void loadPcm() {
        sPcmStamp = Long.MIN_VALUE;
        ensurePcm();
    }

    /** Reloads mic/audio.wav when the host UI replaces the file. A WAV header is skipped. */
    public static void ensurePcm() {
        try {
            String pkg = VirtualResourceManager.currentPackage();
            File f = VirtualResourceManager.getMicFile(pkg, "audio.wav");
            long stamp = (f == null || !f.exists()) ? 0L : (f.lastModified() ^ f.length());
            if (stamp == sPcmStamp) return;
            sPcmStamp = stamp;
            if (f == null || !f.exists() || f.length() <= 0) {
                sCachedPcm = null;
                sPcmPos = 0;
                return;
            }
            FileInputStream fis = new FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = fis.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            fis.close();
            sSampleRate = 44100;
            sChannels = 1;
            int header = 0;
            boolean wav = data.length > 44 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F';
            if (wav) header = wavDataOffset(data);
            if (wav && header > 0 && header < data.length) {
                byte[] pcm = new byte[data.length - header];
                System.arraycopy(data, header, pcm, 0, pcm.length);
                sCachedPcm = asVoicePcm(pcm);
            } else {
                sCachedPcm = asVoicePcm(decodeAudio(f));
            }
            sPcmPos = 0;
            if (sCachedPcm == null) {
                Slog.w(TAG, "virtual audio was not decoded: " + f.getAbsolutePath());
            } else {
                Slog.i(TAG, "virtual PCM " + sCachedPcm.length + " bytes " + sSampleRate + "Hz x" + sChannels);
            }
        } catch (Exception e) {
            Slog.w(TAG, "loadPcm error: " + e.getMessage());
            sCachedPcm = null;
            sPcmPos = 0;
        }
    }

    public static int pcmSampleRate() {
        ensurePcm();
        return sSampleRate > 0 ? sSampleRate : 44100;
    }

    public static int pcmChannels() {
        ensurePcm();
        int channels = sChannels;
        return channels == 2 ? 2 : 1;
    }

    public static byte[] pcmOrEmpty() {
        ensurePcm();
        return sCachedPcm;
    }

    public static int fillFloats(float[] buffer, int offset, int count) {
        if (buffer == null || count <= 0) return 0;
        int actual = Math.min(count, buffer.length - offset);
        if (actual <= 0) return 0;
        short[] shorts = new short[actual];
        int filled = fillShorts(shorts, 0, actual);
        for (int i = 0; i < filled; i++) {
            buffer[offset + i] = shorts[i] / 32768f;
        }
        return filled;
    }

    public static int fillShorts(short[] buffer, int offset, int count) {
        if (buffer == null || count <= 0) return 0;
        int actual = Math.min(count, buffer.length - offset);
        if (actual <= 0) return 0;
        byte[] pcm = sCachedPcm;
        if (pcm == null || pcm.length < 2) {
            java.util.Arrays.fill(buffer, offset, offset + actual, (short) 0);
            return actual;
        }
        for (int i = 0; i < actual; i++) {
            if (sPcmPos + 1 >= pcm.length) sPcmPos = 0;
            int lo = pcm[sPcmPos] & 0xff;
            int hi = pcm[sPcmPos + 1];
            buffer[offset + i] = (short) ((hi << 8) | lo);
            sPcmPos += 2;
            if (sPcmPos >= pcm.length) sPcmPos = 0;
        }
        return actual;
    }

    public static int fillByteBuffer(ByteBuffer buffer, int size) {
        if (buffer == null || size <= 0) return 0;
        // AudioRecord.read(ByteBuffer) does not move position. Callers such as
        // WebRTC read the direct address from the start on every call.
        int actual = Math.min(size, buffer.capacity());
        if (actual <= 0) return 0;
        byte[] tmp = new byte[actual];
        int written = fillBuffer(tmp, 0, actual);
        ByteBuffer view = buffer.duplicate();
        view.clear();
        view.put(tmp, 0, written);
        return written;
    }

    /** Decodes mp3, m4a, ogg and other clips to 16-bit PCM. WAV is handled earlier. */
    private static byte[] decodeAudio(File file) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0 || format == null) return null;
            extractor.selectTrack(track);
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (rate >= 8000 && rate <= 192000) sSampleRate = rate;
            }
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                if (channels >= 1 && channels <= 8) sChannels = channels;
            }
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) return null;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean floatSamples = false;
            for (int spin = 0; spin < 8000 && pcm.size() < 8 * 1024 * 1024; spin++) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(4000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int n = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 4000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = codec.getOutputFormat();
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        int rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (rate >= 8000 && rate <= 192000) sSampleRate = rate;
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        int channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (channels >= 1 && channels <= 8) sChannels = channels;
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        floatSamples = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                    }
                } else if (out >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(out);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        if (floatSamples) {
                            while (buf.remaining() >= 4 && pcm.size() < 8 * 1024 * 1024) {
                                short sample = (short) Math.max(-32768, Math.min(32767, (int) (buf.getFloat() * 32767f)));
                                pcm.write(sample & 0xff);
                                pcm.write((sample >> 8) & 0xff);
                            }
                        } else {
                            byte[] chunk = new byte[Math.min(info.size, buf.remaining())];
                            buf.get(chunk);
                            int room = 8 * 1024 * 1024 - pcm.size();
                            pcm.write(chunk, 0, Math.min(chunk.length, Math.max(0, room)));
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(out, false);
                    if (eos) break;
                } else if (inputDone && out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                }
            }
            byte[] data = pcm.toByteArray();
            if ((data.length & 1) == 1) {
                byte[] even = new byte[data.length - 1];
                System.arraycopy(data, 0, even, 0, even.length);
                data = even;
            }
            return data.length > 0 ? data : null;
        } catch (Throwable t) {
            Slog.w(TAG, "decode audio: " + t.getMessage());
            return null;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                try { codec.release(); } catch (Throwable ignored) {}
            }
            try { extractor.release(); } catch (Throwable ignored) {}
        }
    }

    private static int fillBuffer(byte[] buffer, int offset, int size) {
        if (buffer == null || size <= 0) return 0;
        int actual = Math.min(size, buffer.length - offset);
        if (actual <= 0) return 0;
        byte[] pcm = sCachedPcm;
        if (pcm == null || pcm.length == 0) {
            // mute – fill with silence
            Arrays.fill(buffer, offset, offset + actual, (byte) 0);
            return actual;
        }
        // loop the file
        int written = 0;
        while (written < actual) {
            int remaining = actual - written;
            int available  = pcm.length - sPcmPos;
            int chunk      = Math.min(remaining, available);
            System.arraycopy(pcm, sPcmPos, buffer, offset + written, chunk);
            written  += chunk;
            sPcmPos  += chunk;
            if (sPcmPos >= pcm.length) sPcmPos = 0;
        }
        return written;
    }

    // -------------------------------------------------------------------------
    // Static facade for AudioRecordProxy delegation
    // -------------------------------------------------------------------------

    public static void loadPcmStatic() {
        loadPcm();
    }

    public static int fillBufferStatic(byte[] buffer, int offset, int size) {
        return fillBuffer(buffer, offset, size);
    }

    public static void resetStatic() {
        sCachedPcm = null;
        sPcmPos = 0;
    }

    /**
     * One channel, 16-bit, 48000 Hz. WebRTC voice reads are this shape, so the
     * native AudioRecord hook can copy the bytes straight through.
     */
    private static byte[] asVoicePcm(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return null;
        int channels = sChannels >= 1 && sChannels <= 8 ? sChannels : 1;
        int rate = sSampleRate >= 8000 && sSampleRate <= 192000 ? sSampleRate : 44100;
        int frames = (pcm.length / 2) / channels;
        if (frames <= 0) return null;
        int dstFrames = rate == 48000 ? frames
                : (int) Math.min(4_000_000L, Math.max(1L, (long) frames * 48000L / rate));
        byte[] out = new byte[dstFrames * 2];
        for (int i = 0; i < dstFrames; i++) {
            int srcFrame = rate == 48000 ? Math.min(frames - 1, i)
                    : (int) Math.min(frames - 1L, (long) i * rate / 48000L);
            int base = srcFrame * channels;
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int off = (base + c) * 2;
                if (off + 1 >= pcm.length) break;
                int lo = pcm[off] & 0xff;
                int hi = pcm[off + 1];
                sum += (short) ((hi << 8) | lo);
            }
            short sample = (short) (sum / channels);
            out[i * 2] = (byte) (sample & 0xff);
            out[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
        }
        sSampleRate = 48000;
        sChannels = 1;
        return out;
    }

    /** Byte offset of the PCM payload. Also records fmt sample rate and channels. */
    private static int wavDataOffset(byte[] data) {
        int pos = 12;
        int dataOff = 44;
        while (pos + 8 <= data.length) {
            int size = le32(data, pos + 4);
            if (size < 0) break;
            boolean fmt = data[pos] == 'f' && data[pos + 1] == 'm' && data[pos + 2] == 't' && data[pos + 3] == ' ';
            boolean chunk = data[pos] == 'd' && data[pos + 1] == 'a' && data[pos + 2] == 't' && data[pos + 3] == 'a';
            if (fmt && size >= 16 && pos + 8 + 16 <= data.length) {
                sChannels = Math.max(1, le16(data, pos + 10));
                int rate = le32(data, pos + 12);
                if (rate >= 8000 && rate <= 192000) sSampleRate = rate;
            } else if (chunk) {
                dataOff = pos + 8;
                break;
            }
            int step = 8 + size + (size & 1);
            if (step <= 0) break;
            pos += step;
        }
        return dataOff;
    }

    private static int le16(byte[] data, int off) {
        return (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8);
    }

    private static int le32(byte[] data, int off) {
        return (data[off] & 0xff)
                | ((data[off + 1] & 0xff) << 8)
                | ((data[off + 2] & 0xff) << 16)
                | ((data[off + 3] & 0xff) << 24);
    }
}
