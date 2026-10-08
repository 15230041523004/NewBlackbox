package top.niunaijun.blackbox.fake.service;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.VirtualResourceManager;
import top.niunaijun.blackbox.utils.SensorScenario;
import top.niunaijun.blackbox.utils.VirtualSensorEmitter;

/**
 * VirtualSensorProxy – hooks android.hardware.SystemSensorManager.
 *
 * Replaces real sensor events with synthetic ones loaded from
 *   <filesDir>/virtual/<profile>/<package>/sensors/scenario.json
 *
 * Supported scenarios (field "motion"): stationary, walking, running, cycling
 * Supported sensors: TYPE_ACCELEROMETER, TYPE_GYROSCOPE, TYPE_STEP_COUNTER
 *
 * If no scenario file exists, the real sensor data is forwarded unchanged.
 */
public class VirtualSensorProxy extends ClassInvocationStub {
    public static final String TAG = "VirtualSensorProxy";

    /** Map of listener → handler used for synthetic event delivery. */
    private static final Map<SensorEventListener, VirtualSensorEmitter>
            sEmitters = new ConcurrentHashMap<>();

    public VirtualSensorProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // -------------------------------------------------------------------------
    // registerListener
    // -------------------------------------------------------------------------

    @ProxyMethod("registerListener")
    public static class RegisterListener extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args == null || args.length < 2
                    || !(args[0] instanceof SensorEventListener)
                    || !(args[1] instanceof Sensor)) {
                return method.invoke(who, args);
            }

            SensorEventListener listener = (SensorEventListener) args[0];
            Sensor sensor = (Sensor) args[1];

            String pkg = BlackBoxCore.get().getHostPkg();
            SensorScenario scenario = VirtualResourceManager.getSensorScenario(pkg);
            if (scenario == null) {
                // no virtual scenario – delegate to real sensor
                return method.invoke(who, args);
            }

            int type = sensor.getType();
            if (type == Sensor.TYPE_ACCELEROMETER
                    || type == Sensor.TYPE_GYROSCOPE
                    || type == Sensor.TYPE_STEP_COUNTER
                    || type == Sensor.TYPE_STEP_DETECTOR) {

                // Extract or create a handler
                Handler handler = null;
                if (args.length >= 4 && args[3] instanceof Handler) {
                    handler = (Handler) args[3];
                }
                if (handler == null) {
                    handler = new Handler(Looper.getMainLooper());
                }

                VirtualSensorEmitter emitter = new VirtualSensorEmitter(
                        listener, sensor, scenario, handler);
                sEmitters.put(listener, emitter);
                emitter.start();
                Slog.d(TAG, "Started virtual sensor emitter for type=" + type
                        + " scenario=" + scenario.motionMode);
                return true;
            }

            return method.invoke(who, args);
        }
    }

    @ProxyMethod("unregisterListener")
    public static class UnregisterListener extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length >= 1
                    && args[0] instanceof SensorEventListener) {
                VirtualSensorEmitter emitter = sEmitters.remove(args[0]);
                if (emitter != null) {
                    emitter.stop();
                    Slog.d(TAG, "Stopped virtual sensor emitter");
                    return null;
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getSensorList")
    public static class GetSensorList extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getDefaultSensor")
    public static class GetDefaultSensor extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }
}
