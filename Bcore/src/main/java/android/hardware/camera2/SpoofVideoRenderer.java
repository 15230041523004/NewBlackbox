package android.hardware.camera2;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.VirtualResourceManager;

/** All GL and player operations run on the spoof-camera handler. */
final class SpoofVideoRenderer {
    private static final String TAG = "SpoofCamera2";
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface pbuffer;
    private final Map<Surface, EGLSurface> windows = new HashMap<>();
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] transform = new float[16];
    private MediaPlayer player;
    private SurfaceTexture decoderTexture;
    private Surface decoderSurface;
    private int videoTexture;
    private int photoTexture;
    private int videoProgram;
    private int photoProgram;
    private int videoWidth;
    private int videoHeight;
    private String key = "";
    private boolean pendingFrame;
    private boolean haveFrame;
    private boolean failed;
    private boolean loggedFrame;
    private boolean ready;
    private boolean seeking;
    private long lastSyncCheck;
    private File videoFile;
    private String geometryKey = "";
    private Bitmap uploadedPhoto;

    int positionMs() {
        try { return player == null ? 0 : player.getCurrentPosition(); }
        catch (IllegalStateException e) { return 0; }
    }

    boolean beginFrame(File file, Handler handler) {
        if (file == null) {
            if (player != null || !key.isEmpty()) stop();
            return false;
        }
        String nextKey = file.getAbsolutePath() + ":" + file.lastModified() + ":" + file.length();
        if (!nextKey.equals(key)) {
            stop();
            key = nextKey;
            videoFile = file;
            try {
                initGl();
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture);
                decoderTexture = new SurfaceTexture(videoTexture);
                decoderTexture.setOnFrameAvailableListener(texture -> pendingFrame = true, handler);
                decoderSurface = new Surface(decoderTexture);
                player = new MediaPlayer();
                // A descriptor works for private app files that mediaserver
                // cannot open by pathname. Only video is rendered here.
                try (FileInputStream input = new FileInputStream(file)) {
                    player.setDataSource(input.getFD());
                }
                player.setSurface(decoderSurface);
                player.setVolume(0f, 0f);
                player.setLooping(true);
                player.setOnPreparedListener(prepared -> {
                    if (prepared != player) return;
                    videoWidth = prepared.getVideoWidth();
                    videoHeight = prepared.getVideoHeight();
                    ready = true;
                    if (!syncCall(true)) startPlayer();
                });
                player.setOnSeekCompleteListener(prepared -> {
                    if (prepared != player) return;
                    seeking = false;
                    startPlayer();
                    long audio = SpoofCameraAudio.callVideoPositionMs(videoFile);
                    int duration = prepared.getDuration();
                    if (audio >= 0 && duration > 0) Slog.i(TAG, "call video synced audio="
                            + audio % duration + "ms video=" + prepared.getCurrentPosition() + "ms");
                });
                player.setOnErrorListener((source, what, extra) -> {
                    failed = true;
                    haveFrame = false;
                    Slog.w(TAG, "video playback failed " + what + "/" + extra);
                    return true;
                });
                player.prepareAsync();
            } catch (Throwable error) {
                failed = true;
                Slog.w(TAG, "video playback: " + error);
            }
        }
        if (context == EGL14.EGL_NO_CONTEXT || pbuffer == null || pbuffer == EGL14.EGL_NO_SURFACE) return false;
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) return false;
        if (ready && !failed && !seeking) syncCall(false);
        if (!failed && pendingFrame && !seeking) {
            pendingFrame = false;
            decoderTexture.updateTexImage();
            decoderTexture.getTransformMatrix(transform);
            haveFrame = true;
            if (!loggedFrame) {
                loggedFrame = true;
                Slog.i(TAG, "video decoder frame received");
            }
        }
        return true;
    }

    private void startPlayer() {
        player.start();
        Slog.i(TAG, "video playback started " + videoWidth + "x" + videoHeight
                + " position=" + player.getCurrentPosition() + "ms");
    }

    private boolean syncCall(boolean starting) {
        long now = System.nanoTime();
        if (seeking || (!starting && now - lastSyncCheck < 2000000000L)) return seeking;
        lastSyncCheck = now;
        long audio = SpoofCameraAudio.callVideoPositionMs(videoFile);
        int duration = player.getDuration();
        if (audio < 0 || duration <= 0) return false;
        int target = (int) (audio % duration);
        int position = player.getCurrentPosition();
        int drift = target - position;
        // Use the shorter distance across the loop boundary.
        if (drift > duration / 2) drift -= duration;
        if (drift < -duration / 2) drift += duration;
        if (Math.abs(drift) <= 200) return false;
        seeking = true;
        Slog.i(TAG, "call video sync audio=" + target + "ms video=" + position + "ms drift=" + drift + "ms");
        player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        return true;
    }

    boolean draw(Surface surface, Bitmap fallback) {
        if (!SpoofCamera2.isPreviewSurface(surface)) return false;
        if (context == EGL14.EGL_NO_CONTEXT) initGl();
        EGLSurface window = windows.get(surface);
        if (window == null) {
            window = EGL14.eglCreateWindowSurface(display, config, surface,
                    new int[]{EGL14.EGL_NONE}, 0);
            if (window == null || window == EGL14.EGL_NO_SURFACE) return false;
            windows.put(surface, window);
            Slog.i(TAG, "video output surface connected " + surface);
        }
        if (!EGL14.eglMakeCurrent(display, window, window, context)) return false;
        int[] width = new int[1];
        int[] height = new int[1];
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, width, 0);
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, height, 0);
        GLES20.glViewport(0, 0, width[0], height[0]);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (haveFrame && videoWidth > 0 && videoHeight > 0) {
            GLES20.glUseProgram(videoProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(videoProgram, "uTex"), 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(videoProgram, "uTransform"), 1, false, transform, 0);
            boolean rotated = Math.abs(transform[0]) < .5f && Math.abs(transform[1]) > .5f;
            setVertices(rotated ? videoHeight : videoWidth, rotated ? videoWidth : videoHeight,
                    width[0], height[0], false);
        } else if (fallback != null) {
            GLES20.glUseProgram(photoProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTexture);
            if (uploadedPhoto != fallback) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, fallback, 0);
                uploadedPhoto = fallback;
            }
            setVertices(fallback.getWidth(), fallback.getHeight(), width[0], height[0], true);
        }
        if (haveFrame || fallback != null) {
            vertices.position(0);
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, vertices);
            vertices.position(2);
            GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, vertices);
            GLES20.glEnableVertexAttribArray(0);
            GLES20.glEnableVertexAttribArray(1);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
        EGLExt.eglPresentationTimeANDROID(display, window, System.nanoTime());
        return EGL14.eglSwapBuffers(display, window) && GLES20.glGetError() == GLES20.GL_NO_ERROR;
    }

    void retainOutputs(Collection<Surface> outputs) {
        if (windows.isEmpty()) return;
        // Destroying the current window merely defers its disconnection. Bind
        // the pbuffer first so the same consumer can immediately accept a new
        // Surface wrapper, without restarting the player or its audio clock.
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context);
        Iterator<Map.Entry<Surface, EGLSurface>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Surface, EGLSurface> entry = iterator.next();
            if (!entry.getKey().isValid() || !outputs.contains(entry.getKey())) {
                EGL14.eglDestroySurface(display, entry.getValue());
                iterator.remove();
            }
        }
    }

    private void setVertices(int sourceWidth, int sourceHeight, int width, int height, boolean flipY) {
        String pkg = VirtualResourceManager.currentPackage();
        boolean call = SpoofCameraAudio.hasActiveCall();
        float targetAspect = (float) width / Math.max(1, height);
        float frameAspect = targetAspect;
        if ("ru.oneme.app".equals(pkg)) {
            // The call first rotates the camera frame and then center-crops it
            // to the view. Place the complete replacement inside the part of
            // the frame that survives that crop. Circles retain their square mapping.
            targetAspect = call ? displayAspect(width, height) : 1f;
            frameAspect = call ? callFrameAspect(width, height) : targetAspect;
        }
        boolean fill = VirtualResourceManager.getSpoofConfig(pkg).cameraFillFrame;
        String geometry = sourceWidth + "x" + sourceHeight + ":" + width + "x" + height
                + ":" + targetAspect + ":" + frameAspect + ":" + call + ":" + fill;
        if (!geometry.equals(geometryKey)) {
            geometryKey = geometry;
            Slog.i(TAG, "video geometry source=" + sourceWidth + "x" + sourceHeight
                    + " input=" + width + "x" + height + " displayAspect=" + targetAspect
                    + " frameAspect=" + frameAspect
                    + " call=" + call + " scale=" + (fill ? "fill" : "fit"));
        }
        setVertices(sourceWidth, sourceHeight, flipY, fill, targetAspect, frameAspect);
    }

    private static float callFrameAspect(int width, int height) {
        int deviceRotation = 0;
        try {
            WindowManager manager = (WindowManager) BlackBoxCore.getContext().getSystemService(Context.WINDOW_SERVICE);
            deviceRotation = manager.getDefaultDisplay().getRotation() * 90;
        } catch (Throwable ignored) {}
        if (!SpoofCamera2.frontFacing()) deviceRotation = 360 - deviceRotation;
        boolean rotated = (SpoofCamera2.sensorOrientation() + deviceRotation) % 180 != 0;
        return rotated ? (float) height / Math.max(1, width) : (float) width / Math.max(1, height);
    }

    private static float displayAspect(int width, int height) {
        try {
            WindowManager manager = (WindowManager) BlackBoxCore.getContext().getSystemService(Context.WINDOW_SERVICE);
            Point size = new Point();
            manager.getDefaultDisplay().getRealSize(size);
            if (size.x > 0 && size.y > 0) return (float) size.x / size.y;
        } catch (Throwable ignored) {}
        return (float) width / Math.max(1, height);
    }

    private void setVertices(int sourceWidth, int sourceHeight, boolean flipY, boolean fill, float targetAspect) {
        setVertices(sourceWidth, sourceHeight, flipY, fill, targetAspect, targetAspect);
    }

    private void setVertices(int sourceWidth, int sourceHeight, boolean flipY, boolean fill,
                             float targetAspect, float frameAspect) {
        float sourceAspect = (float) sourceWidth / sourceHeight;
        float x = fill && sourceAspect > targetAspect ? targetAspect / sourceAspect : 1f;
        float y = fill && sourceAspect < targetAspect ? sourceAspect / targetAspect : 1f;
        float quadX = !fill && sourceAspect < targetAspect ? sourceAspect / targetAspect : 1f;
        float quadY = !fill && sourceAspect > targetAspect ? targetAspect / sourceAspect : 1f;
        quadX *= Math.min(1f, targetAspect / frameAspect);
        quadY *= Math.min(1f, frameAspect / targetAspect);
        float left = (1f - x) / 2f;
        float right = 1f - left;
        float bottom = (1f - y) / 2f;
        float top = 1f - bottom;
        if (flipY) { float swap = bottom; bottom = top; top = swap; }
        vertices.clear();
        vertices.put(new float[]{-quadX, -quadY, left, bottom, quadX, -quadY, right, bottom,
                -quadX, quadY, left, top, quadX, quadY, right, top}).position(0);
    }

    void stop() {
        if (player != null) { player.release(); player = null; }
        if (decoderSurface != null) { decoderSurface.release(); decoderSurface = null; }
        if (decoderTexture != null) { decoderTexture.release(); decoderTexture = null; }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context);
            for (EGLSurface window : windows.values()) EGL14.eglDestroySurface(display, window);
        }
        windows.clear();
        key = "";
        pendingFrame = false;
        haveFrame = false;
        failed = false;
        loggedFrame = false;
        ready = false;
        seeking = false;
        lastSyncCheck = 0;
        videoFile = null;
        geometryKey = "";
        uploadedPhoto = null;
    }

    private void initGl() {
        if (context != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context);
            return;
        }
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("EGL init");
        int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0)
            throw new IllegalStateException("EGL config");
        config = configs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT || pbuffer == EGL14.EGL_NO_SURFACE
                || !EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context))
            throw new IllegalStateException("EGL context");
        String vertex = "attribute vec2 aPos;attribute vec2 aTex;varying vec2 vTex;"
                + "void main(){gl_Position=vec4(aPos,0.0,1.0);vTex=aTex;}";
        photoProgram = program(vertex, "precision mediump float;varying vec2 vTex;uniform sampler2D uTex;"
                + "void main(){gl_FragColor=texture2D(uTex,vTex);}");
        videoProgram = program("attribute vec2 aPos;attribute vec2 aTex;varying vec2 vTex;uniform mat4 uTransform;"
                + "void main(){gl_Position=vec4(aPos,0.0,1.0);vTex=(uTransform*vec4(aTex,0.0,1.0)).xy;}",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float;"
                + "varying vec2 vTex;uniform samplerExternalOES uTex;void main(){gl_FragColor=texture2D(uTex,vTex);}");
        int[] textures = new int[2];
        GLES20.glGenTextures(2, textures, 0);
        videoTexture = textures[0];
        photoTexture = textures[1];
        setupTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture);
        setupTexture(GLES20.GL_TEXTURE_2D, photoTexture);
    }

    private static void setupTexture(int target, int texture) {
        GLES20.glBindTexture(target, texture);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int program(String vertex, String fragment) {
        int result = GLES20.glCreateProgram();
        GLES20.glAttachShader(result, shader(GLES20.GL_VERTEX_SHADER, vertex));
        GLES20.glAttachShader(result, shader(GLES20.GL_FRAGMENT_SHADER, fragment));
        GLES20.glBindAttribLocation(result, 0, "aPos");
        GLES20.glBindAttribLocation(result, 1, "aTex");
        GLES20.glLinkProgram(result);
        int[] status = new int[1];
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(result));
        return result;
    }

    private static int shader(int type, String source) {
        int result = GLES20.glCreateShader(type);
        GLES20.glShaderSource(result, source);
        GLES20.glCompileShader(result);
        int[] status = new int[1];
        GLES20.glGetShaderiv(result, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(result));
        return result;
    }
}
