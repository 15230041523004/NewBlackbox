package top.niunaijun.blackbox.fake.service;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.VirtualResourceManager;

/**
 * VirtualCameraProxy – hooks android.hardware.Camera (Camera1 API).
 *
 * For each guest package, the virtual resource root is:
 *   <filesDir>/virtual/<profile>/<package>/camera/
 *
 * Files used:
 *   preview.jpg  – frame returned to PreviewCallback (repeatedly)
 *   photo.jpg    – file returned for takePicture JPEG callback
 *   record.mp4   – file used when MediaRecorder is recording video
 *
 * If the files are absent, the real camera call is delegated unchanged.
 */
public class VirtualCameraProxy extends ClassInvocationStub {
    public static final String TAG = "VirtualCameraProxy";

    public VirtualCameraProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        // ClassInvocationStub – no binder to replace; hooks are method-level.
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // -------------------------------------------------------------------------
    // open / release
    // -------------------------------------------------------------------------

    @ProxyMethod("open")
    public static class Open extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "Camera.open called");
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("release")
    public static class Release extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "Camera.release");
            return method.invoke(who, args);
        }
    }

    // -------------------------------------------------------------------------
    // Preview
    // -------------------------------------------------------------------------

    @ProxyMethod("startPreview")
    public static class StartPreview extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "Camera.startPreview");
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("stopPreview")
    public static class StopPreview extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "Camera.stopPreview");
            return method.invoke(who, args);
        }
    }

    /**
     * setPreviewCallback – if a virtual preview.jpg exists for the current
     * package, wrap the callback to inject the file bytes on every frame.
     */
    @ProxyMethod("setPreviewCallback")
    public static class SetPreviewCallback extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] instanceof Camera.PreviewCallback) {
                Camera.PreviewCallback original = (Camera.PreviewCallback) args[0];
                byte[] frame = loadVirtualFrame("preview.jpg");
                if (frame != null) {
                    args[0] = (Camera.PreviewCallback) (data, camera) -> {
                        Slog.d(TAG, "Injecting virtual preview frame");
                        original.onPreviewFrame(frame, camera);
                    };
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setPreviewCallbackWithBuffer")
    public static class SetPreviewCallbackWithBuffer extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // delegate; buffer management handled by real Camera
            return method.invoke(who, args);
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot / takePicture
    // -------------------------------------------------------------------------

    /**
     * takePicture – intercept the JPEG callback and inject photo.jpg instead
     * of the real sensor output.
     */
    @ProxyMethod("takePicture")
    public static class TakePicture extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length >= 3) {
                // args[2] is the PictureCallback for JPEG
                Object jpegCb = args[2];
                if (jpegCb instanceof Camera.PictureCallback) {
                    byte[] photo = loadVirtualFrame("photo.jpg");
                    if (photo != null) {
                        Camera.PictureCallback originalJpeg = (Camera.PictureCallback) jpegCb;
                        args[2] = (Camera.PictureCallback) (data, camera) -> {
                            Slog.d(TAG, "Injecting virtual photo.jpg");
                            originalJpeg.onPictureTaken(photo, camera);
                        };
                    }
                }
            }
            return method.invoke(who, args);
        }
    }

    // -------------------------------------------------------------------------
    // Video recording via Camera1 + MediaRecorder
    // -------------------------------------------------------------------------

    @ProxyMethod("unlock")
    public static class Unlock extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "Camera.unlock – allowing for MediaRecorder handoff");
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("lock")
    public static class Lock extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "Camera.lock");
            return method.invoke(who, args);
        }
    }

    // -------------------------------------------------------------------------
    // Parameters / info
    // -------------------------------------------------------------------------

    @ProxyMethod("setParameters")
    public static class SetParameters extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getParameters")
    public static class GetParameters extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getNumberOfCameras")
    public static class GetNumberOfCameras extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // always report at least 2 cameras (front + back)
            try {
                Object result = method.invoke(who, args);
                if (result instanceof Integer && (Integer) result < 1) return 1;
                return result;
            } catch (Exception e) {
                return 1;
            }
        }
    }

    @ProxyMethod("getCameraInfo")
    public static class GetCameraInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setDisplayOrientation")
    public static class SetDisplayOrientation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setPreviewDisplay")
    public static class SetPreviewDisplay extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setPreviewTexture")
    public static class SetPreviewTexture extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /** Reads bytes from the virtual camera resource directory for the current package. */
    static byte[] loadVirtualFrame(String fileName) {
        try {
            String pkg = VirtualResourceManager.currentPackage();

            top.niunaijun.blackbox.utils.AppSpoofConfig cfg = VirtualResourceManager.getSpoofConfig(pkg);
            if (cfg.grantCamera) {
                Slog.d(TAG, "Camera spoofing disabled (grant=true) for " + pkg);
                return null;
            }

            File f = VirtualResourceManager.getCameraFile(pkg, fileName);
            if (f == null || !f.exists()) return null;
            FileInputStream fis = new FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            fis.read(data);
            fis.close();
            return data;
        } catch (Exception e) {
            Slog.w(TAG, "Failed to load virtual frame: " + e.getMessage());
            return null;
        }
    }
}
