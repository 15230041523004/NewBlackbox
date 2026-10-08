package top.niunaijun.blackbox.fake.service;

import android.media.AudioRecord;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * AudioRecordProxy - delegates all AudioRecord hooks to VirtualMicProxy logic.
 *
 * All read() calls return either silence (mute mode) or looped bytes from
 *   virtual/<profile>/<pkg>/mic/audio.wav
 *
 * @see VirtualMicProxy for the buffer logic.
 */
public class AudioRecordProxy extends ClassInvocationStub {
    public static final String TAG = "AudioRecordProxy";

    public AudioRecordProxy() {
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

    @ProxyMethod("<init>")
    public static class Constructor extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioRecord: Constructor called – loading virtual PCM");
            VirtualMicProxy.loadPcmStatic();
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("startRecording")
    public static class StartRecording extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioRecord: startRecording");
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("stop")
    public static class Stop extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioRecord: stop");
            return method.invoke(who, args);
        }
    }

    /** Intercept read(byte[], int, int) – deliver virtual PCM or silence. */
    @ProxyMethod("read")
    public static class Read extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length >= 3
                    && args[0] instanceof byte[]
                    && args[1] instanceof Integer
                    && args[2] instanceof Integer) {
                int result = VirtualMicProxy.fillBufferStatic(
                        (byte[]) args[0], (Integer) args[1], (Integer) args[2]);
                if (result >= 0) return result;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("release")
    public static class Release extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioRecord: release");
            VirtualMicProxy.resetStatic();
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
}
