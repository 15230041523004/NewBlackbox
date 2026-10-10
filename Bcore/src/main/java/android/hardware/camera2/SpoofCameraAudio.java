package android.hardware.camera2;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import top.niunaijun.blackbox.fake.service.VirtualMicProxy;
import top.niunaijun.blackbox.utils.AppSpoofConfig;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.VirtualResourceManager;

/** Audio timing and source selection for virtual camera recordings and VoIP calls. */
public final class SpoofCameraAudio {
    private static final Map<Object, Stream> STREAMS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> CALLS = Collections.synchronizedMap(new WeakHashMap<>());

    public static boolean start(Object receiver) {
        if (android.os.Build.VERSION.SDK_INT < 28) return false;
        if (!(receiver instanceof AudioRecord)) return false;
        String pkg = VirtualResourceManager.currentPackage();
        AppSpoofConfig config = VirtualResourceManager.getSpoofConfig(pkg);
        return start((AudioRecord) receiver, pkg, config);
    }

    private static boolean start(AudioRecord record, String pkg, AppSpoofConfig config) {
        boolean call = record.getAudioSource() == MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        boolean camera = SpoofCamera2.hasActiveSession();
        // MAX starts VoIP capture before opening its camera. Waiting for an active
        // camera leaves the call on the unpaced voice-message PCM path.
        if (!call && !camera) return false;
        stop(record);
        // Track the call even when the real microphone is allowed, so camera
        // geometry does not depend on which audio source the user selected.
        if (call) CALLS.put(record, Boolean.TRUE);
        boolean video = config.cameraAudioFromVideo && !config.grantCamera;
        // Camera audio is allowed to replace AudioRecord only for an explicitly
        // selected file source. Live modes (bypass/DSP/silence) must start the
        // real recorder and continue through SpoofAudioHook; otherwise the old
        // microphone PCM loop masks the selected live voice profile in calls.
        boolean microphoneFile = !video && !config.grantMic && config.micUsesJavaFileSource();
        if (!video && !microphoneFile) return false;
        Stream stream = new Stream(record.getSampleRate(), record.getChannelCount());
        stream.call = call;
        STREAMS.put(record, stream);
        if (video) {
            File file = VirtualResourceManager.getCameraFile(pkg, "record.mp4");
            stream.startVideo(file, SpoofCamera2.videoPositionMs() * 1000L);
        } else {
            VirtualMicProxy.ensurePcm();
            byte[] pcm = VirtualMicProxy.pcmOrEmpty();
            if (pcm != null) {
                stream.loop = stream.resample(ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN),
                        VirtualMicProxy.pcmSampleRate(), VirtualMicProxy.pcmChannels());
            }
            stream.ended = true;
        }
        Slog.i("SpoofCamera2", (call ? "call" : "circle") + " audio source="
                + (video ? "video" : "microphone") + " " + stream.rate + "Hz x" + stream.channels
                + " encoding=" + record.getAudioFormat() + " cameraActive=" + camera);
        return true;
    }

    public static boolean active(Object receiver) { return STREAMS.containsKey(receiver); }
    public static boolean hasActiveCall() { return !CALLS.isEmpty(); }

    /** Position actually supplied to a call, also when its camera has not opened yet. */
    public static long callVideoPositionMs(File file) {
        if (file == null) return -1;
        String path = file.getAbsolutePath();
        Stream latest = null;
        synchronized (STREAMS) {
            for (Stream stream : STREAMS.values()) {
                if (stream.running && stream.call && path.equals(stream.videoPath)
                        && (latest == null || stream.start > latest.start)) latest = stream;
            }
        }
        if (latest == null) return -1;
        return latest.videoStartUs / 1000L + latest.bytesRead * 1000L / (2L * latest.channels * latest.rate);
    }

    public static int read(Object receiver, ByteBuffer output, int size) {
        Stream stream = STREAMS.get(receiver);
        if (stream == null) return -1;
        if (output == null) return AudioRecord.ERROR_BAD_VALUE;
        int count = Math.min(size, output.capacity());
        if (count <= 0) return 0;
        byte[] bytes = new byte[count];
        stream.read(bytes);
        ByteBuffer view = output.duplicate();
        view.clear();
        view.put(bytes);
        return count;
    }

    public static int read(Object receiver, byte[] output, int offset, int size) {
        Stream stream = STREAMS.get(receiver);
        if (stream == null) return -1;
        if (output == null || offset < 0 || size < 0 || offset > output.length - size)
            return AudioRecord.ERROR_BAD_VALUE;
        byte[] bytes = new byte[size];
        stream.read(bytes);
        System.arraycopy(bytes, 0, output, offset, size);
        return size;
    }

    public static int read(Object receiver, short[] output, int offset, int size) {
        Stream stream = STREAMS.get(receiver);
        if (stream == null) return -1;
        if (output == null || offset < 0 || size < 0 || offset > output.length - size)
            return AudioRecord.ERROR_BAD_VALUE;
        byte[] bytes = new byte[size * 2];
        stream.read(bytes);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(output, offset, size);
        return size;
    }

    public static int timestamp(Object receiver, AudioTimestamp timestamp, int timebase) {
        Stream stream = STREAMS.get(receiver);
        if (stream == null) return Integer.MIN_VALUE;
        timestamp.framePosition = stream.bytesRead / (2L * stream.channels);
        timestamp.nanoTime = (timebase == AudioTimestamp.TIMEBASE_BOOTTIME ? stream.bootStart : stream.start)
                + timestamp.framePosition * 1000000000L / stream.rate;
        return AudioRecord.SUCCESS;
    }

    public static void stop(Object receiver) {
        CALLS.remove(receiver);
        Stream stream = STREAMS.remove(receiver);
        if (stream != null) {
            stream.running = false;
            if (stream.worker != null) stream.worker.interrupt();
        }
    }

    private static final class Stream {
        final int rate;
        final int channels;
        final long start = System.nanoTime();
        final long bootStart = SystemClock.elapsedRealtimeNanos();
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(32);
        volatile boolean running = true;
        volatile boolean ended;
        volatile long bytesRead;
        boolean call;
        volatile String videoPath;
        volatile long videoStartUs;
        Thread worker;
        byte[] loop;
        byte[] chunk;
        int offset;
        double cursor;
        short[] previous;

        Stream(int rate, int channels) {
            this.rate = rate > 0 ? rate : 48000;
            this.channels = channels > 0 ? channels : 1;
        }

        void read(byte[] output) {
            int written = 0;
            while (running && written < output.length) {
                if (chunk == null || offset == chunk.length) {
                    offset = 0;
                    if (loop != null && loop.length > 0) chunk = loop;
                    else if (!ended) {
                        try { chunk = queue.poll(100, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                        if (chunk == null) {
                            if (ended) break;
                            continue;
                        }
                    } else {
                        chunk = queue.poll();
                        if (chunk == null) break;
                    }
                }
                int count = Math.min(output.length - written, chunk.length - offset);
                System.arraycopy(chunk, offset, output, written, count);
                offset += count;
                written += count;
            }
            bytesRead += output.length;
            long due = start + (bytesRead / (2L * channels)) * 1000000000L / rate;
            long remaining = due - System.nanoTime();
            if (remaining > 0 && running) {
                try { TimeUnit.NANOSECONDS.sleep(remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        void startVideo(File file, long positionUs) {
            if (file == null) { ended = true; return; }
            videoPath = file.getAbsolutePath();
            videoStartUs = positionUs;
            worker = new Thread(() -> decode(file, positionUs), "spoof-circle-audio");
            worker.start();
        }

        void decode(File file, long positionUs) {
            MediaExtractor extractor = new MediaExtractor();
            MediaCodec codec = null;
            try (FileInputStream input = new FileInputStream(file)) {
                extractor.setDataSource(input.getFD());
                MediaFormat format = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat candidate = extractor.getTrackFormat(i);
                    String mime = candidate.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        format = candidate;
                        extractor.selectTrack(i);
                        break;
                    }
                }
                if (format == null) return;
                int sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                codec.configure(format, null, null, 0);
                codec.start();
                extractor.seekTo(positionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                boolean inputDone = false;
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (running) {
                    if (!inputDone) {
                        int index = codec.dequeueInputBuffer(2000);
                        if (index >= 0) {
                            ByteBuffer buffer = codec.getInputBuffer(index);
                            int size = extractor.readSampleData(buffer, 0);
                            if (size < 0) {
                                inputDone = true;
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                    int index = codec.dequeueOutputBuffer(info, 2000);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat out = codec.getOutputFormat();
                        sourceRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        sourceChannels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    } else if (index >= 0) {
                        byte[] converted = null;
                        if (info.size > 0 && info.presentationTimeUs >= positionUs) {
                            ByteBuffer buffer = codec.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            converted = resample(buffer, sourceRate, sourceChannels);
                        }
                        codec.releaseOutputBuffer(index, false);
                        if (converted != null && converted.length > 0) queue.put(converted);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                            codec.flush();
                            positionUs = 0;
                            inputDone = false;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable error) {
                Slog.w("SpoofCamera2", "circle video audio: " + error);
            } finally {
                if (codec != null) codec.release();
                extractor.release();
                ended = true;
            }
        }

        byte[] resample(ByteBuffer input, int sourceRate, int sourceChannels) {
            int frames = input.remaining() / (2 * sourceChannels);
            if (frames == 0) return new byte[0];
            short[] source = new short[frames * sourceChannels];
            input.asShortBuffer().get(source);
            if (previous == null || previous.length != sourceChannels) previous = new short[sourceChannels];
            double step = (double) sourceRate / rate;
            ByteBuffer output = ByteBuffer.allocate(((int) Math.ceil((frames + 1) / step) + 1) * channels * 2)
                    .order(ByteOrder.LITTLE_ENDIAN);
            while (cursor < frames) {
                int frame = (int) Math.floor(cursor);
                double fraction = cursor - frame;
                for (int channel = 0; channel < channels; channel++) {
                    double value = 0;
                    int firstChannel = channels == 1 ? 0 : Math.min(channel, sourceChannels - 1);
                    int lastChannel = channels == 1 ? sourceChannels : firstChannel + 1;
                    for (int c = firstChannel; c < lastChannel; c++) {
                        int first = frame < 0 ? previous[c] : source[frame * sourceChannels + c];
                        int second = source[Math.min(frames - 1, frame + 1) * sourceChannels + c];
                        value += first + (second - first) * fraction;
                    }
                    output.putShort((short) Math.round(value / (lastChannel - firstChannel)));
                }
                cursor += step;
            }
            cursor -= frames;
            System.arraycopy(source, source.length - sourceChannels, previous, 0, sourceChannels);
            byte[] result = new byte[output.position()];
            output.flip();
            output.get(result);
            return result;
        }
    }
}
