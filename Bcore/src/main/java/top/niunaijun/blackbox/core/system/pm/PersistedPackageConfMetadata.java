package top.niunaijun.blackbox.core.system.pm;

import android.os.Parcel;
import android.os.Process;

import java.util.HashMap;

import top.niunaijun.blackbox.entity.pm.InstallOption;

/** Reads only the stable BPackageSettings tail, avoiding stale framework Parcelable values. */
final class PersistedPackageConfMetadata {
    private static final String INSTALL_OPTION_CLASS = InstallOption.class.getName();
    private static final int MAX_USER_STATES = 1024;
    private static final int KNOWN_INSTALL_FLAGS = InstallOption.FLAG_SYSTEM |
            InstallOption.FLAG_STORAGE | InstallOption.FLAG_URI_FILE;

    final int appId;
    final InstallOption installOption;
    final HashMap<Integer, BPackageUserState> userState;

    PersistedPackageConfMetadata(
            int appId,
            InstallOption installOption,
            HashMap<Integer, BPackageUserState> userState) {
        this.appId = appId;
        this.installOption = installOption;
        this.userState = userState;
    }

    static PersistedPackageConfMetadata read(byte[] bytes) {
        int start = ParcelableClassNameLocator.findLast(bytes, INSTALL_OPTION_CLASS);
        if (start < 4) {
            return null;
        }
        int appId = readIntLe(bytes, start - 4);
        if (appId < Process.FIRST_APPLICATION_UID || appId > Process.LAST_APPLICATION_UID) {
            return null;
        }

        Parcel tail = Parcel.obtain();
        try {
            tail.unmarshall(bytes, start, bytes.length - start);
            tail.setDataPosition(0);
            if (!INSTALL_OPTION_CLASS.equals(tail.readString())) {
                return null;
            }
            int flags = tail.readInt();
            if ((flags & ~KNOWN_INSTALL_FLAGS) != 0) {
                return null;
            }
            int count = tail.readInt();
            if (count < 0 || count > MAX_USER_STATES) {
                return null;
            }
            HashMap<Integer, BPackageUserState> states = new HashMap<>(count);
            for (int i = 0; i < count; i++) {
                Object keyValue = tail.readValue(Integer.class.getClassLoader());
                if (!(keyValue instanceof Integer)) {
                    return null;
                }
                BPackageUserState value = tail.readParcelable(BPackageUserState.class.getClassLoader());
                if (value == null || states.put((Integer) keyValue, value) != null) {
                    return null;
                }
            }
            if (tail.dataAvail() != 0) {
                return null;
            }
            InstallOption option = new InstallOption();
            option.flags = flags;
            return new PersistedPackageConfMetadata(appId, option, states);
        } catch (Throwable ignored) {
            return null;
        } finally {
            tail.recycle();
        }
    }

    private static int readIntLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) |
                ((bytes[offset + 1] & 0xff) << 8) |
                ((bytes[offset + 2] & 0xff) << 16) |
                ((bytes[offset + 3] & 0xff) << 24);
    }
}
