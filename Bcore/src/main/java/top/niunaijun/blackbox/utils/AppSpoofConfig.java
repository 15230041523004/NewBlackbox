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
    public final boolean cameraFillFrame;
    public final String micMode;
    public final String micProfile;
    public final float micInputGain;
    public final float micPitchSemitones;
    public final float micFormantShift;
    public final boolean micUnderflowSilence;

    private AppSpoofConfig(boolean grantCamera, boolean grantMic, boolean grantSensors,
                           boolean cameraAudioFromVideo, boolean cameraFillFrame,
                           String micMode, String micProfile, float micInputGain,
                           float micPitchSemitones, float micFormantShift,
                           boolean micUnderflowSilence) {
        this.grantCamera = grantCamera;
        this.grantMic = grantMic;
        this.grantSensors = grantSensors;
        this.cameraAudioFromVideo = cameraAudioFromVideo;
        this.cameraFillFrame = cameraFillFrame;
        this.micMode = micMode;
        this.micProfile = micProfile;
        this.micInputGain = micInputGain;
        this.micPitchSemitones = micPitchSemitones;
        this.micFormantShift = micFormantShift;
        this.micUnderflowSilence = micUnderflowSilence;
    }

    public static AppSpoofConfig fromFile(File f) {
        if (f == null || !f.exists()) {
            return defaults(); // Default: grant all (real hardware)
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
            String mode = normalizeMode(o.optString("mic_mode", "file"));
            String profile = normalizeProfile(o.optString("mic_profile", "female"));
            float gain = clamp((float) o.optDouble("mic_input_gain", 1.0), 0f, 4f);
            float pitch = clamp((float) o.optDouble("mic_pitch_semitones", 4.0), -12f, 12f);
            float formant = clamp((float) o.optDouble("mic_formant_shift", 0.25), -1f, 1f);
            return new AppSpoofConfig(cam, mic, sens,
                    "video".equals(o.optString("camera_audio_source", "microphone")),
                    "fill".equals(o.optString("camera_scale_mode", "fit")),
                    mode, profile, gain, pitch, formant,
                    "silence".equals(o.optString("mic_underflow_policy", "bypass")));
        } catch (Exception e) {
            Slog.w("AppSpoofConfig", "Failed to parse config: " + e.getMessage());
            return defaults();
        }
    }

    public boolean micUsesJavaFileSource() {
        return "file".equals(micMode);
    }

    public int nativeMicMode() {
        if ("bypass".equals(micMode)) return 1;
        if ("dsp".equals(micMode)) return 2;
        if ("silence".equals(micMode)) return 3;
        return 0;
    }

    public int nativeMicProfile() {
        if ("child".equals(micProfile)) return 1;
        if ("deep".equals(micProfile)) return 2;
        if ("robot".equals(micProfile)) return 3;
        return 0;
    }

    private static AppSpoofConfig defaults() {
        return new AppSpoofConfig(true, true, true, false, false,
                "file", "female", 1f, 4f, 0.25f, false);
    }

    private static String normalizeMode(String value) {
        return "bypass".equals(value) || "dsp".equals(value) || "silence".equals(value)
                ? value : "file";
    }

    private static String normalizeProfile(String value) {
        return "child".equals(value) || "deep".equals(value) || "robot".equals(value)
                ? value : "female";
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
