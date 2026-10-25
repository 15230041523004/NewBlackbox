package top.niunaijun.blackbox.core.system.network;

import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;

public class BNetworkRelayService extends IBNetworkRelayService.Stub implements ISystemService {
    private static final String TAG = "BNetworkRelayService";
    private static final BNetworkRelayService sInstance = new BNetworkRelayService();

    private IBNetworkMainSink mMainSink;
    private final Object mSinkLock = new Object();

    private ParcelFileDescriptor mCurrentSnapshotFd;
    private long mCurrentRevision = 0;
    private final Object mSnapshotLock = new Object();

    private static class GuestSession {
        final long sessionId;
        final int pid;
        final int userId;
        final String packageName;
        final String processName;
        final IBNetworkGuestEndpoint endpoint;

        GuestSession(long sessionId, int pid, int userId, String packageName, String processName, IBNetworkGuestEndpoint endpoint) {
            this.sessionId = sessionId;
            this.pid = pid;
            this.userId = userId;
            this.packageName = packageName;
            this.processName = processName;
            this.endpoint = endpoint;
        }
    }

    private final Map<Long, GuestSession> mGuestSessions = new ConcurrentHashMap<>();

    public static BNetworkRelayService get() {
        return sInstance;
    }

    private BNetworkRelayService() {
    }

    @Override
    public void systemReady() {
        Log.i(TAG, "BNetworkRelayService systemReady");
    }

    @Override
    public void registerMainSink(IBNetworkMainSink sink) {
        synchronized (mSinkLock) {
            mMainSink = sink;
            if (sink != null) {
                try {
                    sink.asBinder().linkToDeath(() -> {
                        synchronized (mSinkLock) {
                            if (mMainSink == sink) {
                                mMainSink = null;
                            }
                        }
                    }, 0);
                } catch (RemoteException e) {
                    mMainSink = null;
                }
            }
        }
    }

    @Override
    public void registerGuest(IBNetworkGuestEndpoint endpoint, long sessionId) {
        int callingPid = Binder.getCallingPid();
        BProcessManagerService.NetworkCaller caller = BProcessManagerService.get().resolveNetworkCaller(callingPid);

        if (caller == null) {
            Log.w(TAG, "Caller PID " + callingPid + " not resolved yet in BProcessManagerService");
            return;
        }

        GuestSession session = new GuestSession(sessionId, callingPid, caller.userId, caller.packageName, caller.processName, endpoint);
        mGuestSessions.put(sessionId, session);

        try {
            endpoint.asBinder().linkToDeath(() -> {
                mGuestSessions.remove(sessionId);
                notifyGuestStatus(sessionId, caller.userId, caller.packageName, caller.processName, callingPid, 0, "DEAD");
            }, 0);
        } catch (RemoteException ignored) {
            mGuestSessions.remove(sessionId);
            return;
        }

        // Fan-out current snapshot immediately to the newly registered guest
        synchronized (mSnapshotLock) {
            if (mCurrentSnapshotFd != null) {
                try {
                    ParcelFileDescriptor dupFd = mCurrentSnapshotFd.dup();
                    endpoint.onSnapshotPublished(mCurrentRevision, dupFd);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to dup and send current snapshot to guest session: " + sessionId, e);
                }
            }
        }

        notifyGuestStatus(sessionId, caller.userId, caller.packageName, caller.processName, callingPid, mCurrentRevision, "ACTIVE");
    }

    @Override
    public ParcelFileDescriptor getCurrentSnapshot() {
        synchronized (mSnapshotLock) {
            if (mCurrentSnapshotFd != null) {
                try {
                    return mCurrentSnapshotFd.dup();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to dup current snapshot FD", e);
                }
            }
            return null;
        }
    }

    @Override
    public void publishSnapshot(long revision, ParcelFileDescriptor fd) {
        synchronized (mSnapshotLock) {
            if (mCurrentSnapshotFd != null) {
                try {
                    mCurrentSnapshotFd.close();
                } catch (IOException ignored) {}
            }
            mCurrentSnapshotFd = fd;
            mCurrentRevision = revision;

            // Fan-out to all connected guest sessions
            for (GuestSession session : mGuestSessions.values()) {
                try {
                    ParcelFileDescriptor dupFd = fd.dup();
                    session.endpoint.onSnapshotPublished(revision, dupFd);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to fan-out snapshot to guest session: " + session.sessionId, e);
                }
            }
        }
    }

    @Override
    public void submitStats(long sessionId, long sequence, byte[] payload) {
        GuestSession session = mGuestSessions.get(sessionId);
        if (session == null) {
            return;
        }

        synchronized (mSinkLock) {
            if (mMainSink != null) {
                try {
                    mMainSink.onStatsBatchReceived(payload);
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to forward stats to MainSink", e);
                }
            }
        }
    }

    @Override
    public void reportAppliedRevision(long sessionId, long revision, String status) {
        GuestSession session = mGuestSessions.get(sessionId);
        if (session != null) {
            notifyGuestStatus(sessionId, session.userId, session.packageName, session.processName, session.pid, revision, status);
        }
    }

    private void notifyGuestStatus(long sessionId, int profileId, String packageName, String processName, int pid, long revision, String state) {
        synchronized (mSinkLock) {
            if (mMainSink != null) {
                try {
                    mMainSink.onGuestStatusChanged(sessionId, profileId, packageName, processName, pid, revision, state);
                } catch (RemoteException ignored) {}
            }
        }
    }
}

