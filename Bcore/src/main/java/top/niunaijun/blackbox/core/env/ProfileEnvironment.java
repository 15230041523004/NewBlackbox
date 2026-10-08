package top.niunaijun.blackbox.core.env;

import java.io.File;
import java.io.IOException;

/** Process-start policy. Files are read afresh so settings are shared across host processes. */
public final class ProfileEnvironment {
    private ProfileEnvironment() {}

    private static File debugMarker(int userId) {
        if (userId < 0) throw new IllegalArgumentException("Invalid user id");
        return new File(BEnvironment.getSystemDir(), "debug-profile-" + userId);
    }

    public static boolean isAdbHidden(int userId) {
        return !debugMarker(userId).exists();
    }

    public static synchronized void setAdbHidden(int userId, boolean hidden) throws IOException {
        File marker = debugMarker(userId);
        if (hidden) {
            if (marker.exists() && !marker.delete()) throw new IOException("Cannot remove profile debug policy");
        } else {
            File dir = marker.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create profile policy directory");
            if (!marker.exists() && !marker.createNewFile()) throw new IOException("Cannot save profile debug policy");
        }
    }
}
