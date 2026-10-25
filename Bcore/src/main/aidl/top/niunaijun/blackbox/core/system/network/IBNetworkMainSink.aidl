package top.niunaijun.blackbox.core.system.network;

interface IBNetworkMainSink {
    oneway void onStatsBatchReceived(in byte[] payload);
    oneway void onGuestStatusChanged(long sessionId, int profileId, String packageName, String processName, int pid, long appliedRevision, String state);
}

