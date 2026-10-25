package top.niunaijun.blackbox.core.system.network;

import android.os.ParcelFileDescriptor;

interface IBNetworkGuestEndpoint {
    oneway void onSnapshotPublished(long revision, in ParcelFileDescriptor fd);
}

