package top.niunaijun.blackbox.fake.service;

import top.niunaijun.blackbox.utils.Slog;

/** Installed for every bound guest, independently of camera/microphone permissions. */
public final class GuestNetworkHooks {
    private static final String TAG = "GuestNetworkHooks";
    private static boolean installed;

    private GuestNetworkHooks() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        // Boot-image java.net.NetworkInterface methods cannot be redirected via ArtMethodHook
        // across DexFile boundaries because their bytecode refers to core-oj DexCache method indexes
        // which do not exist in the app DexFile, causing ART resolution aborts (Invalid method index)
        // and wiping out network interface enumeration needed for WebRTC ICE candidate discovery.
        // Tunnel interface hiding is handled safely at libc getifaddrs / ConnectivityManager level.
        Slog.i(TAG, "GuestNetworkHooks: NetworkInterface ART hooks safely bypassed for WebRTC compatibility");
    }
}
