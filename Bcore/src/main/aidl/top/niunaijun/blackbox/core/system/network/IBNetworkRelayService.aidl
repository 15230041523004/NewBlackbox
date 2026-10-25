package top.niunaijun.blackbox.core.system.network;

import android.os.ParcelFileDescriptor;
import top.niunaijun.blackbox.core.system.network.IBNetworkMainSink;
import top.niunaijun.blackbox.core.system.network.IBNetworkGuestEndpoint;

interface IBNetworkRelayService {
    void registerMainSink(IBNetworkMainSink sink);
    void registerGuest(IBNetworkGuestEndpoint endpoint, long sessionId);
    ParcelFileDescriptor getCurrentSnapshot();
    void publishSnapshot(long revision, in ParcelFileDescriptor fd);
    oneway void submitStats(long sessionId, long sequence, in byte[] payload);
    oneway void reportAppliedRevision(long sessionId, long revision, String status);
}

