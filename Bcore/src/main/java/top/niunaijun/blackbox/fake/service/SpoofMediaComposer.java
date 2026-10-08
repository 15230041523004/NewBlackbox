package top.niunaijun.blackbox.fake.service;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.WeakHashMap;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.VirtualResourceManager;

/**
 * Replaces a MediaRecorder capture with the user's file when the guest is not
 * allowed the real camera or microphone. Audio-only recordings become AAC in
 * an mp4. Video recordings become the imported mp4, or a clip of the imported
 * photo plus the imported audio.
 */
public final class SpoofMediaComposer {
    private static final String TAG = "SpoofMediaComposer";
    private static final Map<Object, Track> TRACKS = new WeakHashMap<>();

    private SpoofMediaComposer() {
    }

    public static void noteAudio(Object recorder) {
        track(recorder).audio = true;
    }

    public static void noteVideo(Object recorder) {
        track(recorder).video = true;
    }

    public static void noteFormat(Object recorder, int format) {
        track(recorder).format = format;
    }

    public static void notePath(Object recorder, String path) {
        if (path != null) track(recorder).path = path;
    }

    public static void noteFd(Object recorder, java.io.FileDescriptor fd) {
        if (fd == null) return;
        Track track = track(recorder);
        try {
            if (track.dup != null) track.dup.close();
            track.dup = ParcelFileDescriptor.dup(fd);
        } catch (Exception e) {
            Slog.w(TAG, "dup output: " + e.getMessage());
        }
    }

    /** @return true when the real recorder must not be started */
    public static boolean skipStart(Object recorder) {
        Track track = track(recorder);
        String pkg = VirtualResourceManager.currentPackage();
        boolean fakeCamera = track.video && VirtualResourceManager.spoofCamera(pkg);
        boolean fakeMic = track.audio && !track.video && VirtualResourceManager.spoofMic(pkg);
        track.startedAt = SystemClock.elapsedRealtime();
        track.virtual = fakeCamera || fakeMic;
        if (track.virtual) {
            Slog.i(TAG, "virtual MediaRecorder for " + pkg + " video=" + track.video + " audio=" + track.audio);
        }
        return track.virtual;
    }

    /** @return true when the real stop must not run because this take was virtual */
    public static boolean consumeVirtual(Object recorder) {
        Track track;
        synchronized (TRACKS) {
            track = TRACKS.get(recorder);
        }
        if (track == null || !track.virtual) return false;
        long duration = track.startedAt > 0 ? SystemClock.elapsedRealtime() - track.startedAt : 1000L;
        if (!write(track, duration)) {
            Slog.w(TAG, "virtual recording was not written");
        }
        closeDup(track);
        return true;
    }

    private static boolean write(Track track, long durationMs) {
        String pkg = VirtualResourceManager.currentPackage();
        if (track.video) {
            File video = VirtualResourceManager.getCameraFile(pkg, "record.mp4");
            if (video != null) return deliver(track, video);
        }
        if (!track.video) {
            File audio = VirtualResourceManager.getMicFile(pkg, "audio.wav");
            if (audio != null && !isWav(audio)) return deliver(track, audio);
        }
        File tmp = tempFile(track.video ? ".mp4" : ".m4a");
        if (tmp == null) return false;
        try {
            boolean encoded = track.video ? encodeVideo(tmp, track, durationMs) : encodeAudio(tmp, durationMs);
            return encoded && deliver(track, tmp);
        } finally {
            if (!tmp.delete()) tmp.deleteOnExit();
        }
    }

    private static boolean encodeAudio(File dest, long durationMs) {
        return encode(dest, false, durationMs, muxerFormat(MediaRecorder.OutputFormat.MPEG_4));
    }

    private static boolean encodeVideo(File dest, Track track, long durationMs) {
        return encode(dest, true, durationMs, muxerFormat(track.format));
    }

    private static int muxerFormat(int recorderFormat) {
        if (recorderFormat == MediaRecorder.OutputFormat.THREE_GPP) {
            return MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP;
        }
        return MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
    }

    private static boolean encode(File dest, boolean video, long durationMs, int muxerFormat) {
        if (durationMs < 500L) durationMs = 1000L;
        if (durationMs > 12000L) durationMs = 12000L;
        int sampleRate = VirtualMicProxy.pcmSampleRate();
        int channels = VirtualMicProxy.pcmChannels();
        byte[] pcm = VirtualMicProxy.pcmOrEmpty();
        if (pcm == null || pcm.length < 2) {
            pcm = new byte[sampleRate * channels * 2];
        }
        byte[] nv12 = null;
        int width = 640;
        int height = 480;
        if (video) {
            Bitmap bitmap = VirtualResourceManager.cameraPreviewBitmap(VirtualResourceManager.currentPackage());
            boolean owned = false;
            if (bitmap == null) {
                bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(android.graphics.Color.BLACK);
                owned = true;
            }
            width = Math.max(2, bitmap.getWidth()) & ~1;
            height = Math.max(2, bitmap.getHeight()) & ~1;
            if (width > 640) {
                height = Math.max(2, (height * 640 / width)) & ~1;
                width = 640;
            }
            nv12 = toNv12(bitmap, width, height);
            if (owned) bitmap.recycle();
            if (nv12 == null) return false;
        }
        MediaCodec videoCodec = null;
        MediaCodec audioCodec = null;
        MediaMuxer muxer = null;
        try {
            muxer = new MediaMuxer(dest.getAbsolutePath(), muxerFormat);
            if (video) {
                videoCodec = videoEncoder(width, height);
                if (videoCodec == null) return false;
                videoCodec.start();
            }
            audioCodec = audioEncoder(sampleRate, channels);
            audioCodec.start();
            int videoTrack = -1;
            int audioTrack = -1;
            boolean started = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int frames = video ? (int) Math.max(5, Math.min(120, durationMs / 100L)) : 0;
            int frame = 0;
            int pcmPos = 0;
            long audioUs = 0L;
            int chunk = 2048 * channels;
            if ((chunk & 1) == 1) chunk++;
            boolean videoEos = !video;
            boolean audioEos = false;
            long audioLimitUs = durationMs * 1000L;
            int spins = 0;
            while ((!videoEos || !audioEos || !started) && spins < 8000) {
                spins++;
                if (video && frame < frames) {
                    int in = videoCodec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buf = videoCodec.getInputBuffer(in);
                        buf.clear();
                        buf.put(nv12);
                        videoCodec.queueInputBuffer(in, 0, nv12.length, frame * 100000L, 0);
                        frame++;
                    }
                } else if (video && frame == frames) {
                    int in = videoCodec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        videoCodec.queueInputBuffer(in, 0, 0, frame * 100000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        frame++;
                    }
                }
                if (!audioEos && audioUs < audioLimitUs) {
                    int in = audioCodec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buf = audioCodec.getInputBuffer(in);
                        buf.clear();
                        int size = Math.min(chunk, buf.capacity());
                        if ((size & 1) == 1) size--;
                        for (int i = 0; i < size; i++) {
                            buf.put(pcm[pcmPos]);
                            pcmPos++;
                            if (pcmPos >= pcm.length) pcmPos = 0;
                        }
                        int samples = size / (2 * channels);
                        audioCodec.queueInputBuffer(in, 0, size, audioUs, 0);
                        audioUs += samples * 1000000L / sampleRate;
                    }
                } else if (!audioEos) {
                    int in = audioCodec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        audioCodec.queueInputBuffer(in, 0, 0, audioUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        audioEos = true;
                    }
                }
                if (video) {
                    videoTrack = drain(videoCodec, muxer, videoTrack, started, info);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 && frame > frames) videoEos = true;
                }
                audioTrack = drain(audioCodec, muxer, audioTrack, started, info);
                if (!started && audioTrack >= 0 && (!video || videoTrack >= 0)) {
                    muxer.start();
                    started = true;
                }
                if (frame > frames + 2 && audioEos && started) break;
            }
            drain(audioCodec, muxer, audioTrack, started, info);
            if (video) drain(videoCodec, muxer, videoTrack, started, info);
            if (started) muxer.stop();
            return started && dest.length() > 0;
        } catch (Throwable t) {
            Slog.w(TAG, "encode: " + t.getMessage());
            return false;
        } finally {
            try {
                if (videoCodec != null) videoCodec.release();
            } catch (Throwable ignored) {
            }
            try {
                if (audioCodec != null) audioCodec.release();
            } catch (Throwable ignored) {
            }
            try {
                if (muxer != null) muxer.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int drain(MediaCodec codec, MediaMuxer muxer, int track, boolean started, MediaCodec.BufferInfo info) {
        while (true) {
            int out = codec.dequeueOutputBuffer(info, 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) return track;
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (track < 0) track = muxer.addTrack(codec.getOutputFormat());
                continue;
            }
            if (out < 0) continue;
            ByteBuffer buf = codec.getOutputBuffer(out);
            if (started && buf != null && info.size > 0
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && track >= 0) {
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                muxer.writeSampleData(track, buf, info);
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(out, false);
            if (eos) return track;
        }
    }

    private static MediaCodec videoEncoder(int width, int height) {
        int[] formats = new int[] {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        };
        for (int format : formats) {
            MediaCodec codec = null;
            try {
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaFormat media = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                media.setInteger(MediaFormat.KEY_COLOR_FORMAT, format);
                media.setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000);
                media.setInteger(MediaFormat.KEY_FRAME_RATE, 10);
                media.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                codec.configure(media, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                return codec;
            } catch (Throwable t) {
                Slog.w(TAG, "video encoder " + format + ": " + t.getMessage());
                if (codec != null) codec.release();
            }
        }
        return null;
    }

    private static MediaCodec audioEncoder(int sampleRate, int channels) throws java.io.IOException {
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return codec;
    }

    private static byte[] toNv12(Bitmap src, int width, int height) {
        Bitmap scaled = src;
        if (src.getWidth() != width || src.getHeight() != height) {
            scaled = Bitmap.createScaledBitmap(src, width, height, true);
        }
        int[] pixels = new int[width * height];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
        if (scaled != src) scaled.recycle();
        byte[] out = new byte[width * height * 3 / 2];
        int yIndex = 0;
        int uv = width * height;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int color = pixels[j * width + i];
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                out[yIndex++] = (byte) clamp(y);
                if ((j & 1) == 0 && (i & 1) == 0 && uv + 1 < out.length) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    out[uv++] = (byte) clamp(u);
                    out[uv++] = (byte) clamp(v);
                }
            }
        }
        return out;
    }

    private static int clamp(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    private static boolean isWav(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            return in.read(magic) == 4 && magic[0] == 'R' && magic[1] == 'I' && magic[2] == 'F' && magic[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean deliver(Track track, File src) {
        try {
            if (track.path != null) {
                return VirtualResourceManager.copyFile(src, new File(track.path));
            }
            if (track.dup == null) return false;
            java.io.FileDescriptor raw = track.dup.getFileDescriptor();
            try {
                Os.lseek(raw, 0, OsConstants.SEEK_SET);
                Os.ftruncate(raw, 0);
            } catch (ErrnoException ignored) {
            }
            FileInputStream in = new FileInputStream(src);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                int off = 0;
                while (off < n) {
                    int wrote = Os.write(raw, buf, off, n - off);
                    if (wrote <= 0) break;
                    off += wrote;
                }
            }
            in.close();
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "deliver: " + e.getMessage());
            return false;
        }
    }

    private static File tempFile(String suffix) {
        try {
            File dir = BlackBoxCore.getContext().getCacheDir();
            return File.createTempFile("spoof-", suffix, dir);
        } catch (Exception e) {
            Slog.w(TAG, "temp: " + e.getMessage());
            return null;
        }
    }

    private static void closeDup(Track track) {
        if (track.dup == null) return;
        try {
            track.dup.close();
        } catch (Exception ignored) {
        }
        track.dup = null;
    }

    private static Track track(Object recorder) {
        synchronized (TRACKS) {
            Track track = TRACKS.get(recorder);
            if (track == null) {
                track = new Track();
                TRACKS.put(recorder, track);
            }
            return track;
        }
    }

    private static final class Track {
        boolean audio;
        boolean video;
        boolean virtual;
        int format = MediaRecorder.OutputFormat.MPEG_4;
        String path;
        ParcelFileDescriptor dup;
        long startedAt;
    }
}
