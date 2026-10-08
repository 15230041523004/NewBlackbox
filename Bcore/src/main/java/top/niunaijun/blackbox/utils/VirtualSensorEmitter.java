package top.niunaijun.blackbox.utils;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import top.niunaijun.blackbox.utils.Slog;

/**
 * Delivers synthetic SensorEvent objects to a registered listener on a
 * background handler, simulating one of four motion modes:
 *   stationary, walking, running, cycling
 */
public class VirtualSensorEmitter implements Runnable {
    private static final String TAG = "VirtualSensorEmitter";
    private static final int INTERVAL_MS = 50; // ~20 Hz

    private final SensorEventListener listener;
    private final Sensor sensor;
    private final SensorScenario scenario;
    private final Handler handler;
    private volatile boolean running = false;
    private long stepCount;
    private double phase = 0;

    public VirtualSensorEmitter(SensorEventListener listener,
                                 Sensor sensor,
                                 SensorScenario scenario,
                                 Handler handler) {
        this.listener  = listener;
        this.sensor    = sensor;
        this.scenario  = scenario;
        this.handler   = handler;
        this.stepCount = scenario.initialStepCount;
    }

    public void start() {
        running = true;
        handler.postDelayed(this, INTERVAL_MS);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(this);
    }

    @Override
    public void run() {
        if (!running) return;
        try {
            dispatchEvent();
        } catch (Exception e) {
            Slog.w(TAG, "dispatch error: " + e.getMessage());
        }
        handler.postDelayed(this, INTERVAL_MS);
    }

    private void dispatchEvent() throws Exception {
        SensorEvent event = createEvent();
        if (event == null) return;

        int type = sensor.getType();
        phase += 0.1;

        // Values depend on motion mode
        float ax = 0, ay = 0, az = SensorManager.GRAVITY_EARTH;
        float gx = 0, gy = 0, gz = 0;

        switch (scenario.motionMode) {
            case "walking":
                ax = (float) (0.5 * Math.sin(phase));
                ay = (float) (0.3 * Math.cos(phase * 1.1));
                az = SensorManager.GRAVITY_EARTH + (float)(0.2 * Math.sin(phase * 2));
                gx = (float) (0.05 * Math.sin(phase));
                gy = (float) (0.03 * Math.cos(phase));
                if (phase % (2 * Math.PI) < 0.15) stepCount++;
                break;
            case "running":
                ax = (float) (1.5 * Math.sin(phase * 2));
                ay = (float) (1.0 * Math.cos(phase * 2.1));
                az = SensorManager.GRAVITY_EARTH + (float)(0.6 * Math.sin(phase * 4));
                gx = (float) (0.15 * Math.sin(phase * 2));
                gy = (float) (0.10 * Math.cos(phase * 2));
                if (phase % (2 * Math.PI) < 0.12) stepCount++;
                break;
            case "cycling":
                ax = (float) (0.2 * Math.sin(phase * 0.5));
                ay = (float) (0.1 * Math.cos(phase * 0.5));
                az = SensorManager.GRAVITY_EARTH;
                gx = (float) (0.8 * Math.sin(phase));
                gy = (float) (0.1 * Math.cos(phase));
                gz = (float) (0.05 * Math.sin(phase * 2));
                break;
            case "stationary":
            default:
                // gravity only, slight noise
                ax = (float) (0.01 * Math.sin(phase * 0.2));
                ay = (float) (0.01 * Math.cos(phase * 0.3));
                az = SensorManager.GRAVITY_EARTH;
                break;
        }

        setEventValues(event, type, ax, ay, az, gx, gy, gz);
        listener.onSensorChanged(event);
    }

    private SensorEvent createEvent() {
        try {
            Constructor<SensorEvent> ctor = SensorEvent.class.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            SensorEvent e = ctor.newInstance(3);
            Field sensorField = SensorEvent.class.getDeclaredField("sensor");
            sensorField.setAccessible(true);
            sensorField.set(e, sensor);
            Field accuracyField = SensorEvent.class.getDeclaredField("accuracy");
            accuracyField.setAccessible(true);
            accuracyField.set(e, SensorManager.SENSOR_STATUS_ACCURACY_HIGH);
            Field tsField = SensorEvent.class.getDeclaredField("timestamp");
            tsField.setAccessible(true);
            tsField.set(e, SystemClock.elapsedRealtimeNanos());
            return e;
        } catch (Exception ex) {
            Slog.w(TAG, "createEvent: " + ex.getMessage());
            return null;
        }
    }

    private void setEventValues(SensorEvent event, int type,
                                 float ax, float ay, float az,
                                 float gx, float gy, float gz) throws Exception {
        if (event.values == null || event.values.length < 3) return;
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER:
                event.values[0] = ax;
                event.values[1] = ay;
                event.values[2] = az;
                break;
            case Sensor.TYPE_GYROSCOPE:
                event.values[0] = gx;
                event.values[1] = gy;
                event.values[2] = gz;
                break;
            case Sensor.TYPE_STEP_COUNTER:
                if (event.values.length >= 1) event.values[0] = stepCount;
                break;
            case Sensor.TYPE_STEP_DETECTOR:
                // 1.0 when a step is detected
                boolean step = scenario.motionMode.equals("walking")
                        || scenario.motionMode.equals("running");
                event.values[0] = (step && phase % (2 * Math.PI) < 0.15) ? 1.0f : 0.0f;
                break;
            default:
                event.values[0] = ax;
                if (event.values.length > 1) event.values[1] = ay;
                if (event.values.length > 2) event.values[2] = az;
                break;
        }
    }
}
