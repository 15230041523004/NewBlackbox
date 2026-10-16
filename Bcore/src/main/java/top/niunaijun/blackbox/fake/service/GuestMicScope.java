package top.niunaijun.blackbox.fake.service;

/** Process/package boundary for guest microphone interception, independent of audio processing. */
public final class GuestMicScope {
    private GuestMicScope() {}

    public static boolean allows(boolean guestProcess, String boundPackage, String requestedPackage) {
        return guestProcess && boundPackage != null && !boundPackage.isEmpty()
                && (requestedPackage == null || requestedPackage.isEmpty()
                    || boundPackage.equals(requestedPackage));
    }
}
