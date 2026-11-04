package top.niunaijun.blackbox.core.system.pm;

import java.nio.charset.StandardCharsets;

/** Locates the last Parcel.writeParcelable class-name marker without decoding preceding values. */
final class ParcelableClassNameLocator {
    private ParcelableClassNameLocator() {
    }

    static int findLast(byte[] parcel, String className) {
        if (parcel == null || className == null || className.isEmpty()) {
            return -1;
        }
        byte[] encodedName = className.getBytes(StandardCharsets.UTF_16LE);
        for (int nameStart = parcel.length - encodedName.length - 2; nameStart >= 4; nameStart--) {
            int markerStart = nameStart - 4;
            if ((markerStart & 3) != 0 || readIntLe(parcel, markerStart) != className.length()) {
                continue;
            }
            if (parcel[nameStart + encodedName.length] != 0 ||
                    parcel[nameStart + encodedName.length + 1] != 0) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < encodedName.length; i++) {
                if (parcel[nameStart + i] != encodedName[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return markerStart;
            }
        }
        return -1;
    }

    private static int readIntLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) |
                ((bytes[offset + 1] & 0xff) << 8) |
                ((bytes[offset + 2] & 0xff) << 16) |
                ((bytes[offset + 3] & 0xff) << 24);
    }
}
