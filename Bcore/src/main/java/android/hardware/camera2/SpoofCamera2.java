package android.hardware.camera2;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageWriter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import top.niunaijun.blackbox.fake.service.SpoofHookInstaller;
import top.niunaijun.blackbox.utils.ArtMethodHook;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.VirtualResourceManager;

/**
 * Serves Camera2 from the imported photo or video. Modern guests (CameraX,
 * messengers) never call the Camera1 API, so hooking {@code Camera.open} does
 * not change what they record.
 */
public final class SpoofCamera2 {
    private static final String TAG = "SpoofCamera2";
    private static final int MODE_PREVIEW = 4;
    private static final int MODE_IMAGE = 3;

    private static Method openWithHandler;
    private static Method openWithExecutor;
    private static Method openAsyncBackup;
    private static Handler pumpHandler;
    private static boolean pumping;
    private static final AtomicInteger SEQUENCES = new AtomicInteger();
    private static final AtomicLong FRAMES = new AtomicLong();
    private static boolean loggedBitmap;
    private static volatile int sensorOrientation = 90;
    private static volatile boolean frontFacing;
    private static final SpoofVideoRenderer VIDEO = new SpoofVideoRenderer();

    private static final List<SpoofSession> SESSIONS = new ArrayList<>();
    private static final Map<Surface, Integer> MODES = Collections.synchronizedMap(new WeakHashMap<Surface, Integer>());
    private static final Map<Surface, ImageWriter> WRITERS = Collections.synchronizedMap(new WeakHashMap<Surface, ImageWriter>());
    private static final Map<Surface, Boolean> SURFACE_FAILURES = Collections.synchronizedMap(new WeakHashMap<Surface, Boolean>());
    private static final Map<Surface, Integer> FORMATS = Collections.synchronizedMap(new WeakHashMap<Surface, Integer>());

    private SpoofCamera2() {
    }

    public static boolean hasActiveSession() {
        synchronized (SESSIONS) { return !SESSIONS.isEmpty(); }
    }

    public static int videoPositionMs() { return VIDEO.positionMs(); }

    static int sensorOrientation() { return sensorOrientation; }
    static boolean frontFacing() { return frontFacing; }

    /** Draws the replacement photo or video frame onto a preview texture. */
    public static void submitDraw(final Surface surface) {
        if (surface == null) return;
        pump().post(new Runnable() {
            @Override
            public void run() {
                String pkg = VirtualResourceManager.currentPackage();
                drawSurface(surface, VirtualResourceManager.cameraPreviewBitmap(pkg),
                        VirtualResourceManager.cameraJpeg(pkg));
            }
        });
    }

    public static void install() {
        Class<?> type = CameraManager.class;
        openWithHandler = hook(type, OpenHandler.class, OpenHandlerBackup.class, "openCamera",
                String.class, CameraDevice.StateCallback.class, Handler.class);
        openWithExecutor = hook(type, OpenExecutor.class, OpenExecutorBackup.class, "openCamera",
                String.class, Executor.class, CameraDevice.StateCallback.class);
        openAsyncBackup = hook(type, OpenAsync.class, OpenAsyncBackup.class,
                "openCameraDeviceUserAsync",
                String.class, CameraDevice.StateCallback.class, Executor.class,
                int.class, int.class, boolean.class);
        if (openAsyncBackup == null) {
            openAsyncBackup = hook(type, OpenAsync5.class, OpenAsync5Backup.class,
                    "openCameraDeviceUserAsync",
                    String.class, CameraDevice.StateCallback.class, Executor.class,
                    int.class, int.class);
        }
        logOpenMethods(type);
        pump();
    }

    private static void logOpenMethods(Class<?> type) {
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().startsWith("openCamera")) {
                    Slog.i(TAG, "camera method " + method.toGenericString());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static void onOpen(Object self, String cameraId, CameraDevice.StateCallback callback,
                       Handler handler, Executor executor) throws Throwable {
        if (callback == null) return;
        String pkg = VirtualResourceManager.currentPackage();
        boolean spoof = VirtualResourceManager.spoofCamera(pkg);
        Slog.i(TAG, "openCamera spoof=" + spoof + " pkg=" + pkg + " id=" + cameraId);
        if (!spoof) {
            if (executor != null) {
                if (openWithExecutor != null) ArtMethodHook.call(openWithExecutor, self, cameraId, executor, callback);
            } else if (openWithHandler != null) {
                ArtMethodHook.call(openWithHandler, self, cameraId, callback, handler);
            }
            return;
        }
        Slog.i(TAG, "virtual Camera2 for " + pkg + " id=" + cameraId);
        if ("ru.oneme.app".equals(pkg)) {
            try {
                CameraCharacteristics characteristics = ((CameraManager) self).getCameraCharacteristics(cameraId);
                Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (orientation != null) sensorOrientation = orientation;
                frontFacing = Integer.valueOf(CameraCharacteristics.LENS_FACING_FRONT).equals(facing);
                Slog.i(TAG, "camera orientation=" + sensorOrientation + " front=" + frontFacing);
            } catch (Throwable error) { Slog.w(TAG, "camera orientation unavailable: " + error); }
        }
        SpoofDevice device = new SpoofDevice(cameraId == null ? "0" : cameraId, callback, handler, executor);
        post(executor, handler, new Runnable() {
            @Override
            public void run() {
                try {
                    callback.onOpened(device);
                } catch (Throwable t) {
                    Slog.w(TAG, "onOpened: " + t.getMessage());
                }
            }
        });
    }

    private static Handler pump() {
        if (pumpHandler != null) return pumpHandler;
        synchronized (SpoofCamera2.class) {
            if (pumpHandler == null) {
                HandlerThread thread = new HandlerThread("spoof-camera");
                thread.start();
                pumpHandler = new Handler(thread.getLooper());
            }
        }
        return pumpHandler;
    }

    private static void startPump() {
        if (pumping) return;
        pumping = true;
        pump().post(PUMP);
    }

    private static final Runnable PUMP = new Runnable() {
        @Override
        public void run() {
            long started = SystemClock.uptimeMillis();
            String pkg = VirtualResourceManager.currentPackage();
            boolean spoof = VirtualResourceManager.spoofCamera(pkg);
            List<SpoofSession> sessions;
            synchronized (SESSIONS) {
                sessions = new ArrayList<>(SESSIONS);
            }
            Set<Surface> outputs = new ArraySet<>();
            for (SpoofSession session : sessions) outputs.addAll(session.targets());
            // A camera switch can reuse the consumer texture with a new Surface
            // wrapper. Disconnect the old EGL producer before reconnecting it.
            VIDEO.retainOutputs(outputs);
            boolean active = false;
            if (spoof && !sessions.isEmpty()) {
                VIDEO.beginFrame(VirtualResourceManager.getCameraFile(pkg, "record.mp4"), pump());
                Bitmap bitmap = VirtualResourceManager.cameraBitmap(pkg);
                byte[] jpeg = VirtualResourceManager.cameraJpeg(pkg);
                for (SpoofSession session : sessions) {
                    active = true;
                    for (Surface surface : session.targets()) {
                        drawSurface(surface, bitmap, jpeg);
                    }
                    if (session.repeating) session.fireRepeating();
                }
            }
            if (active) pump().postDelayed(this, Math.max(1L, 33L - (SystemClock.uptimeMillis() - started)));
            else {
                VIDEO.stop();
                for (ImageWriter writer : new ArrayList<>(WRITERS.values())) {
                    try { writer.close(); } catch (Throwable ignored) {}
                }
                WRITERS.clear();
                pumping = false;
            }
        }
    };

    private static boolean drawSurface(Surface surface, Bitmap bitmap, byte[] jpeg) {
        return drawSurface(surface, bitmap, jpeg, System.nanoTime());
    }

    private static int surfaceFormat(Surface surface) {
        Integer cached = FORMATS.get(surface);
        if (cached != null) return cached;
        try {
            Class<?> utils = Class.forName("android.hardware.camera2.utils.SurfaceUtils");
            Method query = utils.getDeclaredMethod("getSurfaceFormat", Surface.class);
            query.setAccessible(true);
            int format = (Integer) query.invoke(null, surface);
            FORMATS.put(surface, format);
            Slog.i(TAG, "surface format=" + format + " " + surface);
            return format;
        } catch (Throwable error) {
            surfaceFailure(surface, "cannot determine consumer format: " + error);
            return -1;
        }
    }

    static boolean isPreviewSurface(Surface surface) {
        return surface != null && surface.isValid() && surfaceFormat(surface) == ImageFormat.PRIVATE;
    }

    private static boolean drawSurface(Surface surface, Bitmap bitmap, byte[] jpeg, long timestamp) {
        if (surface == null || !surface.isValid()) return false;
        Integer mode;
        synchronized (MODES) {
            mode = MODES.get(surface);
        }
        try {
            int format = surfaceFormat(surface);
            // EGL overrides the producer format with RGBA. Doing this to
            // a JPEG ImageReader makes its native getPlanes() abort the process.
            if (format == ImageFormat.JPEG || format == 0x21 || format == ImageFormat.YUV_420_888) {
                boolean queued = writeImage(surface, bitmap, jpeg, timestamp);
                if (queued && !Integer.valueOf(MODE_IMAGE).equals(mode)) putMode(surface, MODE_IMAGE);
                return queued;
            }
            if (format != ImageFormat.PRIVATE) {
                surfaceFailure(surface, "unsupported consumer format=" + format);
                return false;
            }
            // Keep one EGL producer for video and the fallback photo. Canvas
            // must not take over a camera texture: HWUI can abort asynchronously
            // when that consumer is released or reconnected during a call.
            boolean queued = VIDEO.draw(surface, bitmap);
            if (queued && !Integer.valueOf(MODE_PREVIEW).equals(mode)) putMode(surface, MODE_PREVIEW);
            return queued;
        } catch (Throwable t) {
            surfaceFailure(surface, t.toString());
        }
        return false;
    }

    private static void surfaceFailure(Surface surface, String reason) {
        if (SURFACE_FAILURES.put(surface, Boolean.TRUE) == null) {
            Slog.w(TAG, "surface frame failed: " + surface + ": " + reason);
        }
    }

    private static void putMode(Surface surface, int mode) {
        synchronized (MODES) {
            MODES.put(surface, mode);
        }
        Slog.i(TAG, "surface frame queued mode=" + mode + " " + surface);
    }

    private static Rect centerCrop(int sourceWidth, int sourceHeight, int width, int height) {
        if ("ru.oneme.app".equals(VirtualResourceManager.currentPackage())) { width = 1; height = 1; }
        int cropWidth = sourceWidth;
        int cropHeight = sourceHeight;
        if ((long) sourceWidth * height > (long) sourceHeight * width) {
            cropWidth = Math.max(1, (int) ((long) sourceHeight * width / Math.max(1, height)));
        } else {
            cropHeight = Math.max(1, (int) ((long) sourceWidth * height / Math.max(1, width)));
        }
        int left = (sourceWidth - cropWidth) / 2;
        int top = (sourceHeight - cropHeight) / 2;
        return new Rect(left, top, left + cropWidth, top + cropHeight);
    }

    private static boolean writeImage(Surface surface, Bitmap bitmap, byte[] jpeg, long timestamp) {
        int format = surfaceFormat(surface);
        if (format == ImageFormat.JPEG || format == 0x21) return writeJpeg(surface, bitmap, jpeg, timestamp);
        ImageWriter writer;
        synchronized (WRITERS) {
            writer = WRITERS.get(surface);
            if (writer == null) {
                writer = ImageWriter.newInstance(surface, 3);
                WRITERS.put(surface, writer);
            }
        }
        Image image = writer.dequeueInputImage();
        try {
            image.setTimestamp(timestamp);
            if (image.getFormat() != ImageFormat.YUV_420_888 || !fillYuv(image, bitmap)) {
                image.close();
                return false;
            }
            writer.queueInputImage(image);
            return true;
        } catch (Throwable t) {
            try {
                image.close();
            } catch (Throwable ignored) {
            }
            throw t;
        }
    }

    private static boolean writeJpeg(Surface surface, Bitmap bitmap, byte[] jpeg, long timestamp) {
        // Android explicitly supports square RGBA buffers carrying a JPEG blob.
        // RGB pixels are never sent to this consumer: the plane contains a real
        // JPEG followed by the camera blob footer, at the end of the allocation.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            surfaceFailure(surface, "JPEG producer requires ImageWriter dimensions support");
            return false;
        }
        byte[] payload = jpeg;
        if (payload == null || payload.length < 3 || (payload[0] & 0xff) != 0xff
                || (payload[1] & 0xff) != 0xd8 || (payload[2] & 0xff) != 0xff) payload = jpegBytes(bitmap);
        if (payload == null || payload.length == 0) return false;
        int edge = (int) Math.ceil(Math.sqrt((payload.length + 8L) / 4.0));
        edge = Math.max(16, (edge + 15) & ~15);
        ImageWriter writer = WRITERS.get(surface);
        if (writer == null || writer.getWidth() < edge || writer.getHeight() != writer.getWidth()) {
            if (writer != null) writer.close();
            writer = new ImageWriter.Builder(surface).setMaxImages(3)
                    .setWidthAndHeight(edge, edge).setImageFormat(PixelFormat.RGBA_8888).build();
            WRITERS.put(surface, writer);
        }
        Image image = writer.dequeueInputImage();
        try {
            image.setTimestamp(timestamp);
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
            buffer.clear();
            // The JPEG reader excludes padding after the last RGBA row. Its
            // blob footer is therefore not necessarily at buffer.capacity().
            int blobSize = (image.getHeight() - 1) * plane.getRowStride()
                    + image.getWidth() * plane.getPixelStride();
            if (blobSize > buffer.capacity() || payload.length > blobSize - 8) {
                image.close();
                return false;
            }
            buffer.put(payload);
            buffer.putInt(blobSize - 8, 0x00ff);
            buffer.putInt(blobSize - 4, payload.length);
            writer.queueInputImage(image);
            return true;
        } catch (Throwable error) {
            image.close();
            throw error;
        }
    }

    private static byte[] jpegBytes(Bitmap bitmap) {
        if (bitmap == null) return VirtualResourceManager.cameraJpeg(VirtualResourceManager.currentPackage());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return out.toByteArray();
    }

    private static CaptureFailure captureFailure(CaptureRequest request, int sequence, long frame) {
        try {
            for (Constructor<?> constructor : CaptureFailure.class.getDeclaredConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if ((types.length == 5 || types.length == 6) && types[0] == CaptureRequest.class) {
                    constructor.setAccessible(true);
                    Object[] values = {request, CaptureFailure.REASON_ERROR, false, sequence, frame, null};
                    return (CaptureFailure) constructor.newInstance(java.util.Arrays.copyOf(values, types.length));
                }
            }
        } catch (Throwable error) { Slog.w(TAG, "capture failure callback: " + error); }
        return null;
    }

    private static int[] yuvPixels;
    private static Bitmap yuvSource;
    private static int yuvW;
    private static int yuvH;

    private static boolean fillYuv(Image image, Bitmap bitmap) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0 || bitmap == null) return false;
        if ((long) width * (long) height > 1920L * 1080L) return false;
        if (yuvPixels == null || yuvSource != bitmap || yuvW != width || yuvH != height) {
            Bitmap scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            new Canvas(scaled).drawBitmap(bitmap, centerCrop(bitmap.getWidth(), bitmap.getHeight(), width, height),
                    new Rect(0, 0, width, height), null);
            if (yuvPixels == null || yuvPixels.length != width * height) yuvPixels = new int[width * height];
            scaled.getPixels(yuvPixels, 0, width, 0, 0, width, height);
            scaled.recycle();
            yuvSource = bitmap;
            yuvW = width;
            yuvH = height;
        }
        int[] pixels = yuvPixels;
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) return false;
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride();
        int uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride();
        int vPix = planes[2].getPixelStride();
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int color = pixels[j * width + i];
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int yPos = j * yRow + i * yPix;
                if (yPos >= 0 && yPos < yBuf.capacity()) yBuf.put(yPos, (byte) clamp(y));
                if ((j & 1) == 0 && (i & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    int uPos = (j / 2) * uRow + (i / 2) * uPix;
                    int vPos = (j / 2) * vRow + (i / 2) * vPix;
                    if (uPos >= 0 && uPos < uBuf.capacity()) uBuf.put(uPos, (byte) clamp(u));
                    if (vPos >= 0 && vPos < vBuf.capacity()) vBuf.put(vPos, (byte) clamp(v));
                }
            }
        }
        return true;
    }

    private static int clamp(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    /**
     * CameraX runs a sequential executor that executes inline when already on
     * its thread. Delivering onOpened or onCaptureCompleted inline makes that
     * graph call back into the camera and overflow the stack, which the guest
     * shows as a failed video note and an empty circle.
     */
    private static void post(final Executor executor, final Handler handler, final Runnable runnable) {
        if (runnable == null) return;
        pump().post(new Runnable() {
            @Override
            public void run() {
                if (handler != null) {
                    handler.post(runnable);
                } else if (executor != null) {
                    executor.execute(runnable);
                } else {
                    runnable.run();
                }
            }
        });
    }

    private static List<Surface> surfacesOf(CaptureRequest request) {
        List<Surface> found = new ArrayList<>();
        if (request == null) return found;
        try {
            for (Field field : request.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                collect(field.get(request), found);
            }
        } catch (Throwable ignored) {
        }
        return found;
    }

    private static void collect(Object value, List<Surface> found) {
        if (value instanceof Surface) {
            found.add((Surface) value);
        } else if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                if (item instanceof Surface) found.add((Surface) item);
            }
        } else if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).keySet()) {
                if (item instanceof Surface) found.add((Surface) item);
            }
        }
    }

    private static CaptureRequest.Builder newBuilder(String cameraId) {
        try {
            Class<?> metaClass = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
            Object metadata = metaClass.getDeclaredConstructor().newInstance();
            Constructor<?>[] constructors = CaptureRequest.Builder.class.getDeclaredConstructors();
            for (Constructor<?> ctor : constructors) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 0 || !metaClass.isAssignableFrom(params[0])) continue;
                Object[] args = new Object[params.length];
                args[0] = metadata;
                for (int i = 1; i < params.length; i++) {
                    if (params[i] == boolean.class) args[i] = Boolean.FALSE;
                    else if (params[i] == int.class) args[i] = Integer.valueOf(-1);
                    else if (params[i] == String.class) args[i] = cameraId;
                    else args[i] = null;
                }
                ctor.setAccessible(true);
                try {
                    return (CaptureRequest.Builder) ctor.newInstance(args);
                } catch (Throwable ignored) {
                }
            }
            return unsafeBuilder(metaClass, metadata, cameraId);
        } catch (Throwable t) {
            Slog.w(TAG, "request builder: " + t.getMessage());
            return null;
        }
    }

    private static CaptureRequest.Builder unsafeBuilder(Class<?> metaClass, Object metadata, String cameraId) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
            CaptureRequest.Builder builder = (CaptureRequest.Builder) allocate.invoke(unsafe, CaptureRequest.Builder.class);
            for (Field field : CaptureRequest.Builder.class.getDeclaredFields()) {
                field.setAccessible(true);
                if (metaClass.isAssignableFrom(field.getType())) field.set(builder, metadata);
                else if (Set.class.isAssignableFrom(field.getType()) && field.get(builder) == null) {
                    field.set(builder, new ArraySet<Surface>());
                } else if (Map.class.isAssignableFrom(field.getType()) && field.get(builder) == null) {
                    field.set(builder, new ArrayMap<Object, Object>());
                } else if (field.getType() == String.class && field.get(builder) == null) {
                    field.set(builder, cameraId);
                }
            }
            return builder;
        } catch (Throwable t) {
            Slog.w(TAG, "unsafe builder: " + t.getMessage());
            return null;
        }
    }

    private static Object resultMetadata(Class<?> metaClass, CaptureRequest request, long timestamp) throws Exception {
        // Result constructors take ownership of metadata. Give every attempt its
        // own copy, including the request's crop and control settings.
        Object metadata;
        try {
            Method copy = CaptureRequest.class.getDeclaredMethod("getNativeCopy");
            copy.setAccessible(true);
            metadata = copy.invoke(request);
        } catch (ReflectiveOperationException e) {
            metadata = metaClass.getDeclaredConstructor().newInstance();
        }
        Method set = metaClass.getMethod("set", CaptureResult.Key.class, Object.class);
        set.invoke(metadata, CaptureResult.SENSOR_TIMESTAMP, Long.valueOf(timestamp));
        set.invoke(metadata, CaptureResult.SENSOR_FRAME_DURATION, Long.valueOf(33333333L));
        set.invoke(metadata, CaptureResult.CONTROL_AE_STATE, Integer.valueOf(CaptureResult.CONTROL_AE_STATE_CONVERGED));
        set.invoke(metadata, CaptureResult.CONTROL_AF_STATE, Integer.valueOf(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED));
        set.invoke(metadata, CaptureResult.CONTROL_AWB_STATE, Integer.valueOf(CaptureResult.CONTROL_AWB_STATE_CONVERGED));
        set.invoke(metadata, CaptureResult.FLASH_STATE, Integer.valueOf(CaptureResult.FLASH_STATE_UNAVAILABLE));
        set.invoke(metadata, CaptureResult.LENS_STATE, Integer.valueOf(CaptureResult.LENS_STATE_STATIONARY));
        return metadata;
    }

    private static TotalCaptureResult newResult(CaptureRequest request, String cameraId,
                                                int sequenceId, long frameNumber, long timestamp) {
        try {
            Class<?> metaClass = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
            for (Constructor<?> ctor : TotalCaptureResult.class.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                boolean hasRequest = false;
                boolean hasFrameNumber = false;
                for (Class<?> param : params) {
                    hasRequest |= param == CaptureRequest.class;
                    hasFrameNumber |= param == long.class;
                }
                // The request-less test constructor moves metadata and returns
                // frame -1. Trying it first empties metadata for later attempts.
                if (!hasRequest || !hasFrameNumber) continue;
                Object[] args = new Object[params.length];
                int intIndex = 0;
                for (int i = 0; i < params.length; i++) {
                    if (metaClass.isAssignableFrom(params[i])) args[i] = resultMetadata(metaClass, request, timestamp);
                    else if (params[i] == CaptureRequest.class) args[i] = request;
                    else if (params[i] == String.class) args[i] = cameraId;
                    else if (params[i] == int.class) args[i] = Integer.valueOf(intIndex++ == 0 ? sequenceId : 0);
                    else if (params[i] == long.class) args[i] = Long.valueOf(frameNumber);
                    else if (params[i] == boolean.class) args[i] = Boolean.FALSE;
                    else if (List.class.isAssignableFrom(params[i])) args[i] = new ArrayList<>();
                    else if (params[i].isArray()) {
                        args[i] = java.lang.reflect.Array.newInstance(params[i].getComponentType(), 0);
                    } else args[i] = null;
                }
                ctor.setAccessible(true);
                try {
                    TotalCaptureResult result = (TotalCaptureResult) ctor.newInstance(args);
                    if (result != null && result.getFrameNumber() == frameNumber
                            && result.getRequest() == request) return result;
                } catch (Throwable ignored) {
                }
            }
            return unsafeResult(metaClass, resultMetadata(metaClass, request, timestamp), request,
                    cameraId, sequenceId, frameNumber);
        } catch (Throwable t) {
            Slog.w(TAG, "capture result: " + t.getMessage());
        }
        return null;
    }

    private static TotalCaptureResult unsafeResult(Class<?> metaClass, Object metadata, CaptureRequest request,
                                                   String cameraId, int sequenceId, long frameNumber) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
            TotalCaptureResult result = (TotalCaptureResult) allocate.invoke(unsafe, TotalCaptureResult.class);
            for (Class<?> type = result.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    if (metaClass.isAssignableFrom(field.getType())) field.set(result, metadata);
                    else if (field.getType() == CaptureRequest.class) field.set(result, request);
                    else if (List.class.isAssignableFrom(field.getType()) && field.get(result) == null) {
                        field.set(result, new ArrayList<Object>());
                    } else if (Map.class.isAssignableFrom(field.getType()) && field.get(result) == null) {
                        // Android 16 declares the physical results as HashMap,
                        // while other camera metadata uses ArrayMap.
                        field.set(result, emptyMap(field.getType()));
                    } else if (field.getType() == long.class && field.getName().toLowerCase().contains("frame")) {
                        field.setLong(result, frameNumber);
                    } else if (field.getType() == int.class && field.getName().equals("mSequenceId")) {
                        field.setInt(result, sequenceId);
                    } else if (field.getType() == String.class && field.getName().equals("mCameraId")) {
                        field.set(result, cameraId);
                    }
                }
            }
            return result;
        } catch (Throwable t) {
            Slog.w(TAG, "unsafe result: " + t.getMessage());
            return null;
        }
    }

    private static Object emptyMap(Class<?> type) throws Exception {
        if (type.isAssignableFrom(ArrayMap.class)) return new ArrayMap<Object, Object>();
        return type.getDeclaredConstructor().newInstance();
    }

    private static Method hook(Class<?> target, Class<?> hookClass, Class<?> backupClass, String name, Class<?>... params) {
        try {
            Method original = target.getDeclaredMethod(name, params);
            Method replacement = hookClass.getDeclaredMethod(name, params);
            Method backup = backupClass.getDeclaredMethod(name, params);
            original.setAccessible(true);
            replacement.setAccessible(true);
            backup.setAccessible(true);
            if (!ArtMethodHook.hook(original, replacement, backup)) {
                Slog.w(TAG, "copy failed CameraManager." + name);
                return null;
            }
            Slog.i(TAG, "hooked CameraManager." + name);
            return backup;
        } catch (Throwable t) {
            Slog.w(TAG, "skip CameraManager." + name + ": " + t.getMessage());
            return null;
        }
    }

    public static final class OpenHandler {
        public void openCamera(String cameraId, CameraDevice.StateCallback callback, Handler handler)
                throws CameraAccessException {
            try {
                onOpen(this, cameraId, callback, handler, null);
            } catch (Throwable t) {
                SpoofHookInstaller.<RuntimeException>sneaky(t);
            }
        }
    }

    public static final class OpenHandlerBackup {
        public void openCamera(String cameraId, CameraDevice.StateCallback callback, Handler handler)
                throws CameraAccessException {
        }
    }

    public static final class OpenExecutor {
        public void openCamera(String cameraId, Executor executor, CameraDevice.StateCallback callback)
                throws CameraAccessException {
            try {
                onOpen(this, cameraId, callback, null, executor);
            } catch (Throwable t) {
                SpoofHookInstaller.<RuntimeException>sneaky(t);
            }
        }
    }

    public static final class OpenExecutorBackup {
        public void openCamera(String cameraId, Executor executor, CameraDevice.StateCallback callback)
                throws CameraAccessException {
        }
    }

    /**
     * Every public {@code openCamera} ends here and then calls the camera service.
     * Hooking this stops a real connect when a public overload was not replaced.
     * A non-spoof call uses the saved original and does not re-enter {@code onOpen}.
     */
    public static final class OpenAsync {
        public CameraDevice openCameraDeviceUserAsync(String cameraId, CameraDevice.StateCallback callback,
                                                      Executor executor, int oomScoreOffset, int rotationOverride,
                                                      boolean sharedMode) throws CameraAccessException {
            return openAsync(this, cameraId, callback, executor, new Object[]{
                    cameraId, callback, executor, oomScoreOffset, rotationOverride, sharedMode
            });
        }
    }

    public static final class OpenAsyncBackup {
        public CameraDevice openCameraDeviceUserAsync(String cameraId, CameraDevice.StateCallback callback,
                                                      Executor executor, int oomScoreOffset, int rotationOverride,
                                                      boolean sharedMode) throws CameraAccessException {
            return null;
        }
    }

    public static final class OpenAsync5 {
        public CameraDevice openCameraDeviceUserAsync(String cameraId, CameraDevice.StateCallback callback,
                                                      Executor executor, int oomScoreOffset, int rotationOverride)
                throws CameraAccessException {
            return openAsync(this, cameraId, callback, executor, new Object[]{
                    cameraId, callback, executor, oomScoreOffset, rotationOverride
            });
        }
    }

    public static final class OpenAsync5Backup {
        public CameraDevice openCameraDeviceUserAsync(String cameraId, CameraDevice.StateCallback callback,
                                                      Executor executor, int oomScoreOffset, int rotationOverride)
                throws CameraAccessException {
            return null;
        }
    }

    private static CameraDevice openAsync(Object self, String cameraId, CameraDevice.StateCallback callback,
                                         Executor executor, Object[] originalArgs) {
        try {
            String pkg = VirtualResourceManager.currentPackage();
            if (!VirtualResourceManager.spoofCamera(pkg)) {
                if (openAsyncBackup == null) return null;
                return (CameraDevice) ArtMethodHook.call(openAsyncBackup, self, originalArgs);
            }
            Slog.i(TAG, "blocked camera service connect for " + pkg + " id=" + cameraId);
            onOpen(self, cameraId, callback, null, executor);
            return null;
        } catch (Throwable t) {
            SpoofHookInstaller.<RuntimeException>sneaky(t);
            return null;
        }
    }

    private static final class SpoofDevice extends CameraDevice {
        private final String id;
        private final StateCallback callback;
        private final Handler handler;
        private final Executor executor;
        private volatile boolean closed;

        SpoofDevice(String id, StateCallback callback, Handler handler, Executor executor) {
            this.id = id;
            this.callback = callback;
            this.handler = handler;
            this.executor = executor;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void createCaptureSession(List<Surface> outputs, CameraCaptureSession.StateCallback callback, Handler handler)
                throws CameraAccessException {
            configure(outputs, callback, handler, null);
        }

        @Override
        public void createCaptureSessionByOutputConfigurations(List<android.hardware.camera2.params.OutputConfiguration> outputs,
                                                               CameraCaptureSession.StateCallback callback, Handler handler)
                throws CameraAccessException {
            configure(surfacesFrom(outputs), callback, handler, null);
        }

        @Override
        public void createReprocessableCaptureSession(android.hardware.camera2.params.InputConfiguration input,
                                                      List<Surface> outputs, CameraCaptureSession.StateCallback callback,
                                                      Handler handler) throws CameraAccessException {
            configure(outputs, callback, handler, null);
        }

        @Override
        public void createReprocessableCaptureSessionByConfigurations(android.hardware.camera2.params.InputConfiguration input,
                                                                      List<android.hardware.camera2.params.OutputConfiguration> outputs,
                                                                      CameraCaptureSession.StateCallback callback, Handler handler)
                throws CameraAccessException {
            configure(surfacesFrom(outputs), callback, handler, null);
        }

        @Override
        public void createConstrainedHighSpeedCaptureSession(List<Surface> outputs, CameraCaptureSession.StateCallback callback,
                                                             Handler handler) throws CameraAccessException {
            configure(outputs, callback, handler, null);
        }

        @Override
        public void createCaptureSession(android.hardware.camera2.params.SessionConfiguration config) throws CameraAccessException {
            if (config == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
            configure(surfacesFrom(config.getOutputConfigurations()), config.getStateCallback(), null, config.getExecutor());
        }

        @Override
        public CaptureRequest.Builder createCaptureRequest(int templateType) throws CameraAccessException {
            CaptureRequest.Builder builder = newBuilder(id);
            if (builder == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
            return builder;
        }

        @Override
        public CaptureRequest.Builder createCaptureRequest(int templateType, Set<String> physicalCameraIdSet)
                throws CameraAccessException {
            return createCaptureRequest(templateType);
        }

        @Override
        public CaptureRequest.Builder createReprocessCaptureRequest(TotalCaptureResult inputResult) throws CameraAccessException {
            return createCaptureRequest(TEMPLATE_STILL_CAPTURE);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            synchronized (SESSIONS) {
                for (int i = SESSIONS.size() - 1; i >= 0; i--) {
                    if (SESSIONS.get(i).device == this) SESSIONS.remove(i).repeating = false;
                }
            }
            final StateCallback cb = callback;
            post(executor, handler, new Runnable() {
                @Override
                public void run() {
                    try {
                        cb.onClosed(SpoofDevice.this);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        @Override
        public boolean isSessionConfigurationSupported(android.hardware.camera2.params.SessionConfiguration config) {
            return true;
        }

        private void configure(List<Surface> outputs, final CameraCaptureSession.StateCallback callback,
                               final Handler handler, final Executor executor) throws CameraAccessException {
            if (closed) throw new IllegalStateException("camera closed");
            final SpoofSession session = new SpoofSession(this, outputs);
            synchronized (SESSIONS) {
                SESSIONS.add(session);
            }
            startPump();
            if (callback == null) return;
            post(executor, handler, new Runnable() {
                @Override
                public void run() {
                    try {
                        callback.onConfigured(session);
                    } catch (Throwable t) {
                        Slog.w(TAG, "onConfigured: " + t.getMessage());
                    }
                }
            });
        }

        private static List<Surface> surfacesFrom(List<android.hardware.camera2.params.OutputConfiguration> outputs) {
            List<Surface> surfaces = new ArrayList<>();
            if (outputs == null) return surfaces;
            for (android.hardware.camera2.params.OutputConfiguration output : outputs) {
                if (output != null && output.getSurface() != null) surfaces.add(output.getSurface());
            }
            return surfaces;
        }
    }

    private static final class SpoofSession extends CameraCaptureSession {
        private final SpoofDevice device;
        private final List<Surface> surfaces = new ArrayList<>();
        private volatile boolean repeating;
        private volatile CaptureRequest repeatingRequest;
        private volatile CaptureCallback repeatingCallback;
        private volatile Handler repeatingHandler;
        private volatile Executor repeatingExecutor;
        private volatile int repeatingSequence;
        private volatile boolean closed;
        private static boolean loggedResult;

        SpoofSession(SpoofDevice device, List<Surface> outputs) {
            this.device = device;
            if (outputs != null) {
                surfaces.addAll(outputs);
                for (Surface surface : outputs) if (surface != null) surfaceFormat(surface);
            }
        }

        List<Surface> targets() {
            // Configured still outputs must stay empty until a request targets
            // them. MAX configures its JPEG reader when the paperclip opens.
            return repeating && !closed ? surfacesOf(repeatingRequest) : Collections.emptyList();
        }

        void fireRepeating() {
            if (!repeating || repeatingCallback == null) return;
            final CaptureRequest request = repeatingRequest;
            final CaptureCallback callback = repeatingCallback;
            final long frame = FRAMES.getAndIncrement();
            final long timestamp = System.nanoTime();
            final TotalCaptureResult result = newResult(request, device.id, repeatingSequence, frame, timestamp);
            if (result == null) {
                if (!loggedResult) {
                    loggedResult = true;
                    Slog.w(TAG, "capture result unavailable; frames are still substituted");
                }
                return;
            }
            post(repeatingExecutor, repeatingHandler, new Runnable() {
                @Override
                public void run() {
                    try {
                        callback.onCaptureStarted(SpoofSession.this, request, timestamp, frame);
                        callback.onCaptureCompleted(SpoofSession.this, request, result);
                        if (!loggedResult) {
                            loggedResult = true;
                            Slog.i(TAG, "capture completed frame=" + frame + " sequence=" + result.getSequenceId());
                        }
                    } catch (Throwable t) {
                        if (!loggedResult) {
                            loggedResult = true;
                            Slog.w(TAG, "capture callback: " + t);
                        }
                    }
                }
            });
        }

        @Override
        public CameraDevice getDevice() {
            return device;
        }

        @Override
        public void prepare(Surface surface) {
        }

        @Override
        public void finalizeOutputConfigurations(List<android.hardware.camera2.params.OutputConfiguration> outputConfigs) {
            surfaces.addAll(SpoofDevice.surfacesFrom(outputConfigs));
        }

        @Override
        public int capture(CaptureRequest request, CaptureCallback listener, Handler handler) throws CameraAccessException {
            return captureOne(request, listener, handler, null);
        }

        @Override
        public int captureSingleRequest(CaptureRequest request, Executor executor, CaptureCallback listener)
                throws CameraAccessException {
            return captureOne(request, listener, null, executor);
        }

        @Override
        public int captureBurst(List<CaptureRequest> requests, CaptureCallback listener, Handler handler)
                throws CameraAccessException {
            int id = 0;
            if (requests != null) {
                for (CaptureRequest request : requests) id = captureOne(request, listener, handler, null);
            }
            return id;
        }

        @Override
        public int captureBurstRequests(List<CaptureRequest> requests, Executor executor, CaptureCallback listener)
                throws CameraAccessException {
            int id = 0;
            if (requests != null) {
                for (CaptureRequest request : requests) id = captureOne(request, listener, null, executor);
            }
            return id;
        }

        @Override
        public int setRepeatingRequest(CaptureRequest request, CaptureCallback listener, Handler handler)
                throws CameraAccessException {
            return repeat(request, listener, handler, null);
        }

        @Override
        public int setSingleRepeatingRequest(CaptureRequest request, Executor executor, CaptureCallback listener)
                throws CameraAccessException {
            return repeat(request, listener, null, executor);
        }

        @Override
        public int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback listener, Handler handler)
                throws CameraAccessException {
            CaptureRequest request = requests == null || requests.isEmpty() ? null : requests.get(requests.size() - 1);
            return repeat(request, listener, handler, null);
        }

        @Override
        public int setRepeatingBurstRequests(List<CaptureRequest> requests, Executor executor, CaptureCallback listener)
                throws CameraAccessException {
            CaptureRequest request = requests == null || requests.isEmpty() ? null : requests.get(requests.size() - 1);
            return repeat(request, listener, null, executor);
        }

        @Override
        public void stopRepeating() {
            repeating = false;
        }

        @Override
        public void abortCaptures() {
            repeating = false;
        }

        @Override
        public boolean isReprocessable() {
            return false;
        }

        @Override
        public Surface getInputSurface() {
            return null;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            repeating = false;
            synchronized (SESSIONS) {
                SESSIONS.remove(this);
            }
        }

        private int repeat(CaptureRequest request, CaptureCallback listener, Handler handler, Executor executor) {
            int id = SEQUENCES.getAndIncrement();
            repeatingRequest = request;
            repeatingCallback = listener;
            repeatingHandler = handler;
            repeatingExecutor = executor;
            repeatingSequence = id;
            repeating = true;
            startPump();
            return id;
        }

        private int captureOne(final CaptureRequest request, final CaptureCallback listener, final Handler handler,
                               final Executor executor) {
            final int id = SEQUENCES.getAndIncrement();
            pump().post(new Runnable() {
                @Override
                public void run() {
                    String pkg = VirtualResourceManager.currentPackage();
                    Bitmap bitmap = VirtualResourceManager.cameraBitmap(pkg);
                    byte[] jpeg = VirtualResourceManager.cameraJpeg(pkg);
                    final long frame = FRAMES.getAndIncrement();
                    final long timestamp = System.nanoTime();
                    boolean queued = true;
                    for (Surface surface : surfacesOf(request)) queued &= drawSurface(surface, bitmap, jpeg, timestamp);
                    if (listener == null) return;
                    final boolean delivered = queued;
                    final TotalCaptureResult result = newResult(request, device.id, id, frame, timestamp);
                    post(executor, handler, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                listener.onCaptureStarted(SpoofSession.this, request, timestamp, frame);
                                if (delivered && result != null) {
                                    listener.onCaptureCompleted(SpoofSession.this, request, result);
                                    listener.onCaptureSequenceCompleted(SpoofSession.this, id, frame);
                                } else {
                                    CaptureFailure failure = captureFailure(request, id, frame);
                                    if (failure != null) listener.onCaptureFailed(SpoofSession.this, request, failure);
                                    listener.onCaptureSequenceAborted(SpoofSession.this, id);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
            });
            return id;
        }
    }
}
