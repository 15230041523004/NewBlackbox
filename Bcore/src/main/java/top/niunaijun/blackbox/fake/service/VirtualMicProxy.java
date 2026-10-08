package top.niunaijun.blackbox.fake.service;

import android.media.AudioRecord;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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
        try {
            String pkg = BlackBoxCore.get().getHostPkg();
            File f = VirtualResourceManager.getMicFile(pkg, "audio.wav");
            if (f != null && f.exists() && f.length() > 0) {
                FileInputStream fis = new FileInputStream(f);
                byte[] data = new byte[(int) f.length()];
                fis.read(data);
                fis.close();
                sCachedPcm = data;
                sPcmPos = 0;
                Slog.d(TAG, "Loaded virtual PCM: " + f.length() + " bytes");
                return;
            }
        } catch (Exception e) {
            Slog.w(TAG, "loadPcm error: " + e.getMessage());
        }
        sCachedPcm = null; // mute mode
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
}
