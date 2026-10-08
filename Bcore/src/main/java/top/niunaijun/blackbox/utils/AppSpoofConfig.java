package top.niunaijun.blackbox.utils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Parsed representation of spoof_config.json.
 *
 * Example JSON:
 * {
 *   "grant_camera": false,
 *   "grant_mic": false,
 *   "grant_sensors": false
 * }
 *
 * grant_* = true means the app uses the REAL hardware.
 * grant_* = false means the app uses the FAKE (virtual) hardware.
 */
public class AppSpoofConfig {
    public final boolean grantCamera;
    public final boolean grantMic;
    public final boolean grantSensors;
    public final boolean cameraAudioFromVideo;

    private AppSpoofConfig(boolean grantCamera, boolean grantMic, boolean grantSensors, boolean cameraAudioFromVideo) {
        this.grantCamera = grantCamera;
        this.grantMic = grantMic;
        this.grantSensors = grantSensors;
        this.cameraAudioFromVideo = cameraAudioFromVideo;
    }

    public static AppSpoofConfig fromFile(File f) {
        if (f == null || !f.exists()) {
            return new AppSpoofConfig(true, true, true, false); // Default: grant all (real hardware)
        }
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject o = new JSONObject(sb.toString());
            // Default to true (real) if not specified
            boolean cam = o.optBoolean("grant_camera", true);
            boolean mic = o.optBoolean("grant_mic", true);
            boolean sens = o.optBoolean("grant_sensors", true);
            return new AppSpoofConfig(cam, mic, sens, "video".equals(o.optString("camera_audio_source", "microphone")));
        } catch (Exception e) {
            Slog.w("AppSpoofConfig", "Failed to parse config: " + e.getMessage());
            return new AppSpoofConfig(true, true, true, false);
        }
    }
}
