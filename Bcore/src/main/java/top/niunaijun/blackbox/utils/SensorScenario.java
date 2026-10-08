package top.niunaijun.blackbox.utils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.StringWriter;

/**
 * Parsed representation of sensors/scenario.json.
 *
 * Example JSON:
 * {
 *   "motion": "walking",          // stationary | walking | running | cycling
 *   "initialStepCount": 5000,
 *   "accelerometer": true,
 *   "gyroscope": true,
 *   "stepCounter": true
 * }
 */
public class SensorScenario {
    /** Motion mode. One of: stationary, walking, running, cycling. */
    public final String motionMode;
    public final long   initialStepCount;
    public final boolean interceptAccelerometer;
    public final boolean interceptGyroscope;
    public final boolean interceptStepCounter;

    private SensorScenario(String motionMode,
                            long initialStepCount,
                            boolean interceptAccelerometer,
                            boolean interceptGyroscope,
                            boolean interceptStepCounter) {
        this.motionMode              = motionMode;
        this.initialStepCount        = initialStepCount;
        this.interceptAccelerometer  = interceptAccelerometer;
        this.interceptGyroscope      = interceptGyroscope;
        this.interceptStepCounter    = interceptStepCounter;
    }

    public static SensorScenario fromFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new FileReader(f));
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return fromJson(sb.toString());
    }

    public static SensorScenario fromJson(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        String motion  = o.optString("motion", "stationary");
        long stepInit  = o.optLong("initialStepCount", 0);
        boolean accel  = o.optBoolean("accelerometer", true);
        boolean gyro   = o.optBoolean("gyroscope", true);
        boolean step   = o.optBoolean("stepCounter", true);
        return new SensorScenario(motion, stepInit, accel, gyro, step);
    }

    /** Default stationary scenario used when JSON fails to parse. */
    public static SensorScenario stationary() {
        return new SensorScenario("stationary", 0, true, true, true);
    }
}
