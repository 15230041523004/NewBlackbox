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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Listeners currently receiving synthetic motion, possibly one emitter per sensor. */
    private static final Map<SensorEventListener, List<VirtualSensorEmitter>>
            sEmitters = new ConcurrentHashMap<>();

    /**
     * @return true when the call was satisfied by the virtual emitter and the
     * real sensor must not be registered.
     */
    public static boolean handleRegister(SensorEventListener listener, Sensor sensor, Handler handler) {
        if (listener == null || sensor == null) return false;
        String pkg = VirtualResourceManager.currentPackage();
        if (!VirtualResourceManager.spoofSensors(pkg)) return false;
        int type = sensor.getType();
        if (type != Sensor.TYPE_ACCELEROMETER
                && type != Sensor.TYPE_GYROSCOPE
                && type != Sensor.TYPE_STEP_COUNTER
                && type != Sensor.TYPE_STEP_DETECTOR) {
            return false;
        }
        SensorScenario scenario = VirtualResourceManager.scenarioOrStationary(pkg);
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        VirtualSensorEmitter emitter = new VirtualSensorEmitter(listener, sensor, scenario, handler);
        List<VirtualSensorEmitter> list = sEmitters.get(listener);
        if (list == null) {
            list = Collections.synchronizedList(new ArrayList<VirtualSensorEmitter>());
            List<VirtualSensorEmitter> raced = sEmitters.putIfAbsent(listener, list);
            if (raced != null) list = raced;
        }
        list.add(emitter);
        emitter.start();
        Slog.d(TAG, "virtual sensor type=" + type + " motion=" + scenario.motionMode);
        return true;
    }

    public static void stopVirtual(SensorEventListener listener, Sensor sensor) {
        if (listener == null) return;
        List<VirtualSensorEmitter> list = sEmitters.get(listener);
        if (list == null) return;
        synchronized (list) {
            Iterator<VirtualSensorEmitter> it = list.iterator();
            while (it.hasNext()) {
                VirtualSensorEmitter emitter = it.next();
                if (emitter.matches(sensor)) {
                    emitter.stop();
                    it.remove();
                }
            }
        }
        if (list.isEmpty()) sEmitters.remove(listener);
    }

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
            Handler handler = null;
            if (args.length >= 4 && args[3] instanceof Handler) {
                handler = (Handler) args[3];
            } else if (args.length >= 5 && args[4] instanceof Handler) {
                handler = (Handler) args[4];
            }
            if (handleRegister(listener, sensor, handler)) return true;
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("unregisterListener")
    public static class UnregisterListener extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length >= 1 && args[0] instanceof SensorEventListener) {
                Sensor sensor = args.length >= 2 && args[1] instanceof Sensor ? (Sensor) args[1] : null;
                stopVirtual((SensorEventListener) args[0], sensor);
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
