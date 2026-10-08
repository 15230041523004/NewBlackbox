package top.niunaijun.blackbox.fake.service;

import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.hardware.camera2.SpoofCameraAudio;
import android.media.MediaRecorder;
import android.os.Handler;
import android.view.SurfaceHolder;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.utils.AppSpoofConfig;
import top.niunaijun.blackbox.utils.ArtMethodHook;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.VirtualResourceManager;

/**
 * Installs the camera, microphone, sensor and media-recorder hooks after the
 * guest process is bound. Each call reads {@code spoof_config.json}, so the
 * host UI can switch a device between real hardware and the emulator without
 * rebuilding the guest.
 *
 * <p>The native microphone tap is installed even when the ART self-test fails.
 * Java camera and recorder hooks still require a successful ART copy.
 */
public final class SpoofHookInstaller {
    private static final String TAG = "SpoofHookInstaller";
    private static volatile boolean sInstalled;

    private SpoofHookInstaller() {
    }

    public static void install() {
        if (sInstalled) return;
        VirtualResourceManager.bindGuestProfile();
        String pkg = VirtualResourceManager.currentPackage();
        AppSpoofConfig cfg = VirtualResourceManager.getSpoofConfig(pkg);
        Slog.i(TAG, "config " + pkg
                + " camera=" + (cfg.grantCamera ? "real" : "fake")
                + " mic=" + (cfg.grantMic ? "real" : "fake")
                + " sensors=" + (cfg.grantSensors ? "real" : "fake"));
        if (cfg.grantCamera && cfg.grantMic && cfg.grantSensors) {
            Slog.i(TAG, "real hardware for " + pkg + "; spoof hooks left inactive");
            return;
        }
        sInstalled = true;
        if (!cfg.grantCamera) installNativeCamera();
        if (!cfg.grantMic) installNativeMic(pkg);
        if (!ArtMethodHook.selfTest()) {
            Slog.w(TAG, "ART self-test failed; Java spoof hooks were not installed");
            return;
        }
        hookCamera();
        hookCamera2();
        hookMic();
        hookSensors();
        hookRecorder();
        CamSession.start();
    }

    private static void installNativeCamera() {
        try {
            boolean blocked = NativeCore.enableCameraBlock();
            Slog.i(TAG, "native camera preview " + (blocked ? "blocked" : "missed"));
        } catch (Throwable t) {
            Slog.w(TAG, "native camera block failed: " + t.getMessage());
        }
    }

    private static void installNativeMic(String pkg) {
        try {
            VirtualMicProxy.ensurePcm();
            File mic = VirtualResourceManager.getMicFile(pkg, "audio.wav");
            String path = mic == null ? "" : mic.getAbsolutePath();
            byte[] pcm = VirtualMicProxy.pcmOrEmpty();
            boolean hooked = NativeCore.enableMicTap(path, pcm,
                    VirtualMicProxy.pcmSampleRate(), VirtualMicProxy.pcmChannels());
            Slog.i(TAG, "native mic " + (hooked ? "hooked" : "missed")
                    + " bytes=" + (pcm == null ? 0 : pcm.length) + " " + path);
        } catch (Throwable t) {
            Slog.w(TAG, "native mic tap failed: " + t.getMessage());
        }
    }

    private static void hookCamera2() {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        try {
            android.hardware.camera2.SpoofCamera2.install();
        } catch (Throwable t) {
            Slog.w(TAG, "Camera2 hook skipped: " + t.getMessage());
        }
    }

    private static void hookCamera() {
        hookOpen();
        Class<?> camera = Camera.class;
        SpoofBridges.bPreview = wire(camera, CamHook.class, CamBackup.class,
                "setPreviewCallback", Camera.PreviewCallback.class);
        SpoofBridges.bPreviewBuffer = wire(camera, CamHook.class, CamBackup.class,
                "setPreviewCallbackWithBuffer", Camera.PreviewCallback.class);
        SpoofBridges.bDisplay = wire(camera, CamHook.class, CamBackup.class,
                "setPreviewDisplay", SurfaceHolder.class);
        SpoofBridges.bTexture = wire(camera, CamHook.class, CamBackup.class,
                "setPreviewTexture", android.graphics.SurfaceTexture.class);
        SpoofBridges.bStartPreview = wire(camera, CamHook.class, CamBackup.class, "startPreview");
        SpoofBridges.bStopPreview = wire(camera, CamHook.class, CamBackup.class, "stopPreview");
        SpoofBridges.bRelease = wire(camera, CamHook.class, CamBackup.class, "release");
        SpoofBridges.bTake3 = wire(camera, CamHook.class, CamBackup.class, "takePicture",
                Camera.ShutterCallback.class, Camera.PictureCallback.class, Camera.PictureCallback.class);
        SpoofBridges.bTake4 = wire(camera, CamHook.class, CamBackup.class, "takePicture",
                Camera.ShutterCallback.class, Camera.PictureCallback.class,
                Camera.PictureCallback.class, Camera.PictureCallback.class);
    }

    private static void hookOpen() {
        SpoofBridges.bOpenId = hookStatic(Camera.class, CamOpenHook.class, CamOpenBackup.class, "open", int.class);
        SpoofBridges.bOpen = hookStatic(Camera.class, CamOpenHook.class, CamOpenBackup.class, "open");
    }

    private static Method hookStatic(Class<?> target, Class<?> hookClass, Class<?> backupClass,
                                     String name, Class<?>... params) {
        try {
            Method original = target.getMethod(name, params);
            Method replacement = hookClass.getDeclaredMethod(name, params);
            Method backup = backupClass.getDeclaredMethod(name, params);
            original.setAccessible(true);
            replacement.setAccessible(true);
            backup.setAccessible(true);
            if (!ArtMethodHook.hook(original, replacement, backup)) {
                Slog.w(TAG, "copy failed Camera." + name);
                return null;
            }
            Slog.i(TAG, "hooked Camera." + name);
            return backup;
        } catch (Throwable t) {
            Slog.w(TAG, "skip Camera." + name + ": " + t.getMessage());
            return null;
        }
    }

    private static void hookMic() {
        Class<?> mic = AudioRecord.class;
        SpoofBridges.bReadBytes = wire(mic, MicHook.class, MicBackup.class,
                "read", byte[].class, int.class, int.class);
        SpoofBridges.bReadBytesMode = wire(mic, MicHook.class, MicBackup.class,
                "read", byte[].class, int.class, int.class, int.class);
        SpoofBridges.bReadShorts = wire(mic, MicHook.class, MicBackup.class,
                "read", short[].class, int.class, int.class);
        SpoofBridges.bReadShortsMode = wire(mic, MicHook.class, MicBackup.class,
                "read", short[].class, int.class, int.class, int.class);
        SpoofBridges.bReadBuffer = wire(mic, MicHook.class, MicBackup.class,
                "read", ByteBuffer.class, int.class);
        SpoofBridges.bReadBufferMode = wire(mic, MicHook.class, MicBackup.class,
                "read", ByteBuffer.class, int.class, int.class);
        SpoofBridges.bReadFloats = wire(mic, MicHook.class, MicBackup.class,
                "read", float[].class, int.class, int.class, int.class);
        SpoofBridges.bStartRec = wire(mic, MicHook.class, MicBackup.class, "startRecording");
        SpoofBridges.bStopRec = wire(mic, MicHook.class, MicBackup.class, "stop");
        SpoofBridges.bGetState = wire(mic, MicHook.class, MicBackup.class, "getState");
        SpoofBridges.bGetRecState = wire(mic, MicHook.class, MicBackup.class, "getRecordingState");
        SpoofBridges.bTimestamp = wire(mic, MicHook.class, MicBackup.class,
                "getTimestamp", AudioTimestamp.class, int.class);
    }

    private static void hookSensors() {
        try {
            Class<?> system = Class.forName("android.hardware.SystemSensorManager");
            SpoofBridges.bRegister = wire(system, SensorHook.class, SensorBackup.class,
                    "registerListenerImpl", SensorEventListener.class, Sensor.class,
                    int.class, Handler.class, int.class, int.class);
            SpoofBridges.bUnregister = wire(system, SensorHook.class, SensorBackup.class,
                    "unregisterListenerImpl", SensorEventListener.class, Sensor.class);
        } catch (Throwable t) {
            Slog.w(TAG, "SystemSensorManager hook skipped: " + t.getMessage());
        }
    }

    private static void hookRecorder() {
        Class<?> recorder = MediaRecorder.class;
        SpoofBridges.bOutString = wire(recorder, RecorderHook.class, RecorderBackup.class,
                "setOutputFile", String.class);
        SpoofBridges.bOutFile = wire(recorder, RecorderHook.class, RecorderBackup.class,
                "setOutputFile", java.io.File.class);
        SpoofBridges.bOutFd = wire(recorder, RecorderHook.class, RecorderBackup.class,
                "setOutputFile", java.io.FileDescriptor.class);
        SpoofBridges.bAudioSource = wire(recorder, RecorderHook.class, RecorderBackup.class,
                "setAudioSource", int.class);
        SpoofBridges.bVideoSource = wire(recorder, RecorderHook.class, RecorderBackup.class,
                "setVideoSource", int.class);
        SpoofBridges.bOutFormat = wire(recorder, RecorderHook.class, RecorderBackup.class,
                "setOutputFormat", int.class);
        SpoofBridges.bRecStart = wire(recorder, RecorderHook.class, RecorderBackup.class, "start");
        SpoofBridges.bRecStop = wire(recorder, RecorderHook.class, RecorderBackup.class, "stop");
    }

    private static Method wire(Class<?> target, Class<?> hookClass, Class<?> backupClass,
                               String name, Class<?>... params) {
        Method original = find(target, name, params);
        Method replacement = find(hookClass, name, params);
        Method backup = find(backupClass, name, params);
        if (original == null || replacement == null || backup == null) {
            Slog.w(TAG, "skip " + target.getSimpleName() + "." + name);
            return null;
        }
        if (!ArtMethodHook.hook(original, replacement, backup)) {
            Slog.w(TAG, "copy failed " + target.getSimpleName() + "." + name);
            return null;
        }
        Slog.d(TAG, "hooked " + target.getSimpleName() + "." + name);
        return backup;
    }

    private static Method find(Class<?> type, String name, Class<?>... params) {
        try {
            Method method = type.getDeclaredMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static <E extends Throwable> void sneaky(Throwable throwable) throws E {
        throw (E) throwable;
    }

    public static final class CamHook {
        public void setPreviewCallback(Camera.PreviewCallback cb) {
            try { SpoofBridges.onPreview(this, cb); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void setPreviewCallbackWithBuffer(Camera.PreviewCallback cb) {
            try { SpoofBridges.onPreviewBuffer(this, cb); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void setPreviewDisplay(SurfaceHolder holder) {
            try { SpoofBridges.onDisplay(this, holder); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void setPreviewTexture(android.graphics.SurfaceTexture texture) {
            try { SpoofBridges.onTexture(this, texture); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void startPreview() {
            try { SpoofBridges.onStartPreview(this); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void stopPreview() {
            try { SpoofBridges.onStopPreview(this); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void release() {
            try { SpoofBridges.onRelease(this); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void takePicture(Camera.ShutterCallback shutter, Camera.PictureCallback raw, Camera.PictureCallback jpeg) {
            try { SpoofBridges.onTake3(this, shutter, raw, jpeg); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void takePicture(Camera.ShutterCallback shutter, Camera.PictureCallback raw,
                                Camera.PictureCallback postview, Camera.PictureCallback jpeg) {
            try { SpoofBridges.onTake4(this, shutter, raw, postview, jpeg); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
    }

    public static final class CamOpenHook {
        public static Camera open(int cameraId) {
            try {
                return SpoofBridges.onOpen(cameraId);
            } catch (Throwable t) {
                SpoofHookInstaller.<RuntimeException>sneaky(t);
                return null;
            }
        }

        public static Camera open() {
            try {
                return SpoofBridges.onOpen();
            } catch (Throwable t) {
                SpoofHookInstaller.<RuntimeException>sneaky(t);
                return null;
            }
        }
    }

    public static final class CamOpenBackup {
        public static Camera open(int cameraId) { return null; }
        public static Camera open() { return null; }
    }

    public static final class CamBackup {
        public void setPreviewCallback(Camera.PreviewCallback cb) {}
        public void setPreviewCallbackWithBuffer(Camera.PreviewCallback cb) {}
        public void setPreviewDisplay(SurfaceHolder holder) {}
        public void setPreviewTexture(android.graphics.SurfaceTexture texture) {}
        public void startPreview() {}
        public void stopPreview() {}
        public void release() {}
        public void takePicture(Camera.ShutterCallback shutter, Camera.PictureCallback raw, Camera.PictureCallback jpeg) {}
        public void takePicture(Camera.ShutterCallback shutter, Camera.PictureCallback raw,
                                Camera.PictureCallback postview, Camera.PictureCallback jpeg) {}
    }

    public static final class MicHook {
        public int read(byte[] audioData, int offsetInBytes, int sizeInBytes) {
            try { return SpoofBridges.onReadBytes(this, audioData, offsetInBytes, sizeInBytes); }
            catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); return -1; }
        }
        public int read(byte[] audioData, int offsetInBytes, int sizeInBytes, int readMode) {
            try { return SpoofBridges.onReadBytesMode(this, audioData, offsetInBytes, sizeInBytes, readMode); }
            catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); return -1; }
        }
        public int read(short[] audioData, int offsetInShorts, int sizeInShorts) {
            try { return SpoofBridges.onReadShorts(this, audioData, offsetInShorts, sizeInShorts); }
            catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); return -1; }
        }
        public int read(short[] audioData, int offsetInShorts, int sizeInShorts, int readMode) {
            try { return SpoofBridges.onReadShortsMode(this, audioData, offsetInShorts, sizeInShorts, readMode); }
            catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); return -1; }
        }
        public int read(ByteBuffer audioBuffer, int sizeInBytes) {
            try { return SpoofBridges.onReadBuffer(this, audioBuffer, sizeInBytes); }
            catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); return -1; }
        }
        public int read(ByteBuffer audioBuffer, int sizeInBytes, int readMode) {
            try { return SpoofBridges.onReadBufferMode(this, audioBuffer, sizeInBytes, readMode); }
            catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); return -1; }
        }
        public int read(float[] audioData, int offsetInFloats, int sizeInFloats, int readMode) {
            try { return SpoofBridges.onReadFloats(this, audioData, offsetInFloats, sizeInFloats, readMode); }
            catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); return -1; }
        }
        public void startRecording() {
            try { SpoofBridges.onStartRec(this); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void stop() {
            try { SpoofBridges.onStopRec(this); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public int getState() {
            try { return SpoofBridges.onGetState(this); }
            catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); return AudioRecord.STATE_UNINITIALIZED; }
        }
        public int getRecordingState() {
            try { return SpoofBridges.onGetRecState(this); }
            catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); return AudioRecord.RECORDSTATE_STOPPED; }
        }
        public int getTimestamp(AudioTimestamp timestamp, int timebase) {
            try { return SpoofBridges.onTimestamp(this, timestamp, timebase); }
            catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); return AudioRecord.ERROR; }
        }
    }

    public static final class MicBackup {
        public int getTimestamp(AudioTimestamp timestamp, int timebase) { return 0; }
        public int read(byte[] audioData, int offsetInBytes, int sizeInBytes) { return 0; }
        public int read(byte[] audioData, int offsetInBytes, int sizeInBytes, int readMode) { return 0; }
        public int read(short[] audioData, int offsetInShorts, int sizeInShorts) { return 0; }
        public int read(short[] audioData, int offsetInShorts, int sizeInShorts, int readMode) { return 0; }
        public int read(ByteBuffer audioBuffer, int sizeInBytes) { return 0; }
        public int read(ByteBuffer audioBuffer, int sizeInBytes, int readMode) { return 0; }
        public int read(float[] audioData, int offsetInFloats, int sizeInFloats, int readMode) { return 0; }
        public void startRecording() {}
        public void stop() {}
        public int getState() { return 0; }
        public int getRecordingState() { return 0; }
    }

    public static final class SensorHook {
        public boolean registerListenerImpl(SensorEventListener listener, Sensor sensor, int delayUs,
                                            Handler handler, int maxBatchReportLatencyUs, int reservedFlags) {
            try {
                return SpoofBridges.onRegister(this, listener, sensor, delayUs, handler,
                        maxBatchReportLatencyUs, reservedFlags);
            } catch (Throwable t) {
                SpoofHookInstaller.<RuntimeException>sneaky(t);
                return false;
            }
        }
        public void unregisterListenerImpl(SensorEventListener listener, Sensor sensor) {
            try { SpoofBridges.onUnregister(this, listener, sensor); }
            catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
    }

    public static final class SensorBackup {
        public boolean registerListenerImpl(SensorEventListener listener, Sensor sensor, int delayUs,
                                            Handler handler, int maxBatchReportLatencyUs, int reservedFlags) {
            return false;
        }
        public void unregisterListenerImpl(SensorEventListener listener, Sensor sensor) {}
    }

    public static final class RecorderHook {
        public void setOutputFile(String path) {
            try { SpoofBridges.onOutString(this, path); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void setOutputFile(java.io.File file) {
            try { SpoofBridges.onOutFile(this, file); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void setOutputFile(java.io.FileDescriptor fd) {
            try { SpoofBridges.onOutFd(this, fd); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void setAudioSource(int audioSource) {
            try { SpoofBridges.onAudioSource(this, audioSource); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void setVideoSource(int videoSource) {
            try { SpoofBridges.onVideoSource(this, videoSource); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void setOutputFormat(int format) {
            try { SpoofBridges.onOutFormat(this, format); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void start() {
            try { SpoofBridges.onRecStart(this); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
        public void stop() {
            try { SpoofBridges.onRecStop(this); } catch (Throwable t) { SpoofHookInstaller.<RuntimeException>sneaky(t); }
        }
    }

    public static final class RecorderBackup {
        public void setOutputFile(String path) {}
        public void setOutputFile(java.io.File file) {}
        public void setOutputFile(java.io.FileDescriptor fd) {}
        public void setAudioSource(int audioSource) {}
        public void setVideoSource(int videoSource) {}
        public void setOutputFormat(int format) {}
        public void start() {}
        public void stop() {}
    }
}

final class CamSession {
    static final Map<Object, Camera.PreviewCallback> callbacks =
            Collections.synchronizedMap(new WeakHashMap<Object, Camera.PreviewCallback>());
    static final Map<Object, SurfaceHolder> holders =
            Collections.synchronizedMap(new WeakHashMap<Object, SurfaceHolder>());
    static final Map<Object, android.view.Surface> textures =
            Collections.synchronizedMap(new WeakHashMap<Object, android.view.Surface>());
    private static final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private static boolean pumping;

    static void track(Object camera, Camera.PreviewCallback callback) {
        if (callback == null) callbacks.remove(camera);
        else callbacks.put(camera, callback);
        start();
    }

    static void track(Object camera, SurfaceHolder holder) {
        if (holder == null) holders.remove(camera);
        else holders.put(camera, holder);
        start();
    }

    static void track(Object camera, android.graphics.SurfaceTexture texture) {
        android.view.Surface previous = textures.remove(camera);
        if (previous != null) previous.release();
        if (texture == null) return;
        try {
            textures.put(camera, new android.view.Surface(texture));
        } catch (Throwable t) {
            Slog.w("CamSession", "preview surface: " + t.getMessage());
        }
        start();
    }

    static void clear(Object camera) {
        callbacks.remove(camera);
        holders.remove(camera);
        android.view.Surface surface = textures.remove(camera);
        if (surface != null) surface.release();
    }

    static void start() {
        if (pumping) return;
        pumping = true;
        handler.post(PUMP);
    }

    private static final Runnable PUMP = new Runnable() {
        @Override
        public void run() {
            String pkg = VirtualResourceManager.currentPackage();
            boolean spoof = VirtualResourceManager.spoofCamera(pkg);
            List<Map.Entry<Object, Camera.PreviewCallback>> cbs;
            synchronized (callbacks) {
                cbs = new ArrayList<>(callbacks.entrySet());
            }
            for (Map.Entry<Object, Camera.PreviewCallback> entry : cbs) {
                if (!spoof || !(entry.getKey() instanceof Camera) || entry.getValue() == null) continue;
                Camera camera = (Camera) entry.getKey();
                int width = 640;
                int height = 480;
                try {
                    Camera.Size size = camera.getParameters().getPreviewSize();
                    if (size != null && size.width > 0 && size.height > 0) {
                        width = size.width;
                        height = size.height;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    entry.getValue().onPreviewFrame(VirtualResourceManager.cameraNv21(pkg, width, height), camera);
                } catch (Throwable ignored) {
                }
            }
            List<Map.Entry<Object, SurfaceHolder>> surfaces;
            synchronized (holders) {
                surfaces = new ArrayList<>(holders.entrySet());
            }
            if (spoof) {
                for (Map.Entry<Object, SurfaceHolder> entry : surfaces) {
                    VirtualResourceManager.drawPreview(entry.getValue(), pkg);
                }
                List<android.view.Surface> previewTextures;
                synchronized (textures) {
                    previewTextures = new ArrayList<>(textures.values());
                }
                for (android.view.Surface surface : previewTextures) {
                    android.hardware.camera2.SpoofCamera2.submitDraw(surface);
                }
            }
            if (callbacks.isEmpty() && holders.isEmpty() && textures.isEmpty()) {
                pumping = false;
                return;
            }
            handler.postDelayed(this, 200);
        }
    };
}

final class SpoofBridges {
    static Method bPreview;
    static Method bPreviewBuffer;
    static Method bDisplay;
    static Method bTexture;
    static Method bStartPreview;
    static Method bStopPreview;
    static Method bRelease;
    static Method bTake3;
    static Method bTake4;
    static Method bOpen;
    static Method bOpenId;

    static Method bReadBytes;
    static Method bReadBytesMode;
    static Method bReadShorts;
    static Method bReadShortsMode;
    static Method bReadBuffer;
    static Method bReadBufferMode;
    static Method bReadFloats;
    static Method bStartRec;
    static Method bStopRec;
    static Method bGetState;
    static Method bGetRecState;
    static Method bTimestamp;

    static Method bRegister;
    static Method bUnregister;

    static Method bOutString;
    static Method bOutFile;
    static Method bOutFd;
    static Method bAudioSource;
    static Method bVideoSource;
    static Method bOutFormat;
    static Method bRecStart;
    static Method bRecStop;

    private static final Map<Object, Boolean> micStarted =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static final Map<Object, String> recorderPaths =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());

    static void onPreview(Object self, Camera.PreviewCallback cb) throws Throwable {
        if (!cameraSpoof() || bPreview == null) {
            if (bPreview != null) ArtMethodHook.call(bPreview, self, cb);
            return;
        }
        CamSession.track(self, cb);
    }

    static void onPreviewBuffer(Object self, Camera.PreviewCallback cb) throws Throwable {
        if (!cameraSpoof() || bPreviewBuffer == null) {
            if (bPreviewBuffer != null) ArtMethodHook.call(bPreviewBuffer, self, cb);
            return;
        }
        CamSession.track(self, cb);
    }

    static void onDisplay(Object self, SurfaceHolder holder) throws Throwable {
        if (!cameraSpoof() || bDisplay == null) {
            if (bDisplay != null) ArtMethodHook.call(bDisplay, self, holder);
            return;
        }
        CamSession.track(self, holder);
        VirtualResourceManager.drawPreview(holder, VirtualResourceManager.currentPackage());
    }

    static void onTexture(Object self, android.graphics.SurfaceTexture texture) throws Throwable {
        if (!cameraSpoof() || bTexture == null) {
            if (bTexture != null) ArtMethodHook.call(bTexture, self, texture);
            return;
        }
        CamSession.track(self, texture);
        android.view.Surface surface = CamSession.textures.get(self);
        if (surface != null) android.hardware.camera2.SpoofCamera2.submitDraw(surface);
    }

    static Camera onOpen(int cameraId) throws Throwable {
        Camera camera = bOpenId == null ? null : (Camera) ArtMethodHook.call(bOpenId, null, cameraId);
        quiet(camera);
        return camera;
    }

    static Camera onOpen() throws Throwable {
        Camera camera = bOpen == null ? null : (Camera) ArtMethodHook.call(bOpen, null);
        quiet(camera);
        return camera;
    }

    private static void quiet(Camera camera) {
        if (camera == null || !cameraSpoof()) return;
        try {
            // The hooked stopPreview is a no-op while spoofing. Call the saved
            // original so Camera.open cannot leave the sensor streaming.
            if (bStopPreview != null) ArtMethodHook.call(bStopPreview, camera);
        } catch (Throwable ignored) {
        }
        try {
            if (bTexture != null) ArtMethodHook.call(bTexture, camera, (android.graphics.SurfaceTexture) null);
        } catch (Throwable ignored) {
        }
    }

    static void onStartPreview(Object self) throws Throwable {
        if (!cameraSpoof() || bStartPreview == null) {
            if (bStartPreview != null) ArtMethodHook.call(bStartPreview, self);
            return;
        }
        CamSession.start();
        SurfaceHolder holder = CamSession.holders.get(self);
        if (holder != null) VirtualResourceManager.drawPreview(holder, VirtualResourceManager.currentPackage());
    }

    static void onStopPreview(Object self) throws Throwable {
        if (!cameraSpoof() || bStopPreview == null) {
            if (bStopPreview != null) ArtMethodHook.call(bStopPreview, self);
        }
    }

    static void onRelease(Object self) throws Throwable {
        CamSession.clear(self);
        if (bRelease != null) ArtMethodHook.call(bRelease, self);
    }

    static void onTake3(Object self, Camera.ShutterCallback shutter, Camera.PictureCallback raw,
                        Camera.PictureCallback jpeg) throws Throwable {
        if (!cameraSpoof() || bTake3 == null) {
            if (bTake3 != null) ArtMethodHook.call(bTake3, self, shutter, raw, jpeg);
            return;
        }
        deliverJpeg(self, shutter, jpeg);
    }

    static void onTake4(Object self, Camera.ShutterCallback shutter, Camera.PictureCallback raw,
                        Camera.PictureCallback postview, Camera.PictureCallback jpeg) throws Throwable {
        if (!cameraSpoof() || bTake4 == null) {
            if (bTake4 != null) ArtMethodHook.call(bTake4, self, shutter, raw, postview, jpeg);
            return;
        }
        deliverJpeg(self, shutter, jpeg);
    }

    private static void deliverJpeg(Object self, Camera.ShutterCallback shutter, Camera.PictureCallback jpeg) {
        if (!(self instanceof Camera)) return;
        if (shutter != null) shutter.onShutter();
        if (jpeg != null) {
            jpeg.onPictureTaken(VirtualResourceManager.cameraJpeg(VirtualResourceManager.currentPackage()), (Camera) self);
        }
    }

    static int onReadBytes(Object self, byte[] buf, int off, int size) throws Throwable {
        if (!micSpoof() || bReadBytes == null) return asInt(bReadBytes, self, buf, off, size);
        VirtualMicProxy.ensurePcm();
        return VirtualMicProxy.fillBufferStatic(buf, off, size);
    }

    static int onReadBytesMode(Object self, byte[] buf, int off, int size, int mode) throws Throwable {
        if (!micSpoof() || bReadBytesMode == null) return asInt(bReadBytesMode, self, buf, off, size, mode);
        return onReadBytes(self, buf, off, size);
    }

    static int onReadShorts(Object self, short[] buf, int off, int size) throws Throwable {
        if (!micSpoof() || bReadShorts == null) return asInt(bReadShorts, self, buf, off, size);
        VirtualMicProxy.ensurePcm();
        return VirtualMicProxy.fillShorts(buf, off, size);
    }

    static int onReadShortsMode(Object self, short[] buf, int off, int size, int mode) throws Throwable {
        if (!micSpoof() || bReadShortsMode == null) return asInt(bReadShortsMode, self, buf, off, size, mode);
        return onReadShorts(self, buf, off, size);
    }

    static int onReadBuffer(Object self, ByteBuffer buf, int size) throws Throwable {
        if (SpoofCameraAudio.active(self)) return SpoofCameraAudio.read(self, buf, size);
        if (!micSpoof() || bReadBuffer == null) return asInt(bReadBuffer, self, buf, size);
        VirtualMicProxy.ensurePcm();
        return VirtualMicProxy.fillByteBuffer(buf, size);
    }

    static int onReadBufferMode(Object self, ByteBuffer buf, int size, int mode) throws Throwable {
        if (SpoofCameraAudio.active(self)) return SpoofCameraAudio.read(self, buf, size);
        if (!micSpoof() || bReadBufferMode == null) return asInt(bReadBufferMode, self, buf, size, mode);
        return onReadBuffer(self, buf, size);
    }

    static int onReadFloats(Object self, float[] buf, int off, int size, int mode) throws Throwable {
        if (!micSpoof() || bReadFloats == null) return asInt(bReadFloats, self, buf, off, size, mode);
        VirtualMicProxy.ensurePcm();
        return VirtualMicProxy.fillFloats(buf, off, size);
    }

    static void onStartRec(Object self) throws Throwable {
        if (SpoofCameraAudio.start(self)) {
            micStarted.put(self, Boolean.TRUE);
            return;
        }
        if (micSpoof()) {
            micStarted.put(self, Boolean.TRUE);
            VirtualMicProxy.ensurePcm();
            return;
        }
        if (bStartRec != null) ArtMethodHook.call(bStartRec, self);
    }

    static void onStopRec(Object self) throws Throwable {
        SpoofCameraAudio.stop(self);
        micStarted.remove(self);
        if (bStopRec != null) ArtMethodHook.call(bStopRec, self);
    }

    static int onGetState(Object self) throws Throwable {
        if (micSpoof()) return AudioRecord.STATE_INITIALIZED;
        return asInt(bGetState, self);
    }

    static int onGetRecState(Object self) throws Throwable {
        if (SpoofCameraAudio.active(self)) return AudioRecord.RECORDSTATE_RECORDING;
        if (micSpoof() && Boolean.TRUE.equals(micStarted.get(self))) {
            return AudioRecord.RECORDSTATE_RECORDING;
        }
        return asInt(bGetRecState, self);
    }

    static int onTimestamp(Object self, AudioTimestamp timestamp, int timebase) throws Throwable {
        int result = SpoofCameraAudio.timestamp(self, timestamp, timebase);
        if (result != Integer.MIN_VALUE) return result;
        return asInt(bTimestamp, self, timestamp, timebase);
    }

    static boolean onRegister(Object self, SensorEventListener listener, Sensor sensor, int delayUs,
                              Handler handler, int maxLatency, int reserved) throws Throwable {
        if (VirtualSensorProxy.handleRegister(listener, sensor, handler)) return true;
        if (bRegister == null) return false;
        Object result = ArtMethodHook.call(bRegister, self, listener, sensor, delayUs, handler, maxLatency, reserved);
        return result instanceof Boolean && (Boolean) result;
    }

    static void onUnregister(Object self, SensorEventListener listener, Sensor sensor) throws Throwable {
        VirtualSensorProxy.stopVirtual(listener, sensor);
        if (bUnregister != null) ArtMethodHook.call(bUnregister, self, listener, sensor);
    }

    static void onOutString(Object self, String path) throws Throwable {
        if (path != null) recorderPaths.put(self, path);
        SpoofMediaComposer.notePath(self, path);
        if (bOutString != null) ArtMethodHook.call(bOutString, self, path);
    }

    static void onOutFile(Object self, java.io.File file) throws Throwable {
        if (file != null) recorderPaths.put(self, file.getAbsolutePath());
        if (file != null) SpoofMediaComposer.notePath(self, file.getAbsolutePath());
        if (bOutFile != null) ArtMethodHook.call(bOutFile, self, file);
    }

    static void onOutFd(Object self, java.io.FileDescriptor fd) throws Throwable {
        SpoofMediaComposer.noteFd(self, fd);
        if (bOutFd != null) ArtMethodHook.call(bOutFd, self, fd);
    }

    static void onAudioSource(Object self, int source) throws Throwable {
        SpoofMediaComposer.noteAudio(self);
        if (bAudioSource != null) ArtMethodHook.call(bAudioSource, self, source);
    }

    static void onVideoSource(Object self, int source) throws Throwable {
        SpoofMediaComposer.noteVideo(self);
        if (bVideoSource != null) ArtMethodHook.call(bVideoSource, self, source);
    }

    static void onOutFormat(Object self, int format) throws Throwable {
        SpoofMediaComposer.noteFormat(self, format);
        if (bOutFormat != null) ArtMethodHook.call(bOutFormat, self, format);
    }

    static void onRecStart(Object self) throws Throwable {
        if (SpoofMediaComposer.skipStart(self)) return;
        if (bRecStart != null) ArtMethodHook.call(bRecStart, self);
    }

    static void onRecStop(Object self) throws Throwable {
        if (SpoofMediaComposer.consumeVirtual(self)) return;
        if (bRecStop != null) ArtMethodHook.call(bRecStop, self);
        if (!cameraSpoof()) return;
        String dest = recorderPaths.get(self);
        java.io.File src = VirtualResourceManager.getCameraFile(VirtualResourceManager.currentPackage(), "record.mp4");
        if (dest != null && src != null) VirtualResourceManager.copyFile(src, new java.io.File(dest));
    }

    private static boolean cameraSpoof() {
        return VirtualResourceManager.spoofCamera(VirtualResourceManager.currentPackage());
    }

    private static boolean micSpoof() {
        return VirtualResourceManager.spoofMic(VirtualResourceManager.currentPackage());
    }

    private static int asInt(Method backup, Object self, Object... args) throws Throwable {
        if (backup == null) return -1;
        Object result = ArtMethodHook.call(backup, self, args);
        return result instanceof Integer ? (Integer) result : -1;
    }
}
