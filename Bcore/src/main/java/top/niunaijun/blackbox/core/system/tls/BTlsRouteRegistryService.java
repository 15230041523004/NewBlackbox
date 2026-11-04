package top.niunaijun.blackbox.core.system.tls;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import com.blacktwin.tls.contracts.AddressFamily;
import com.blacktwin.tls.contracts.AppScope;
import com.blacktwin.tls.contracts.ConfigPublication;
import com.blacktwin.tls.contracts.CaGeneration;
import com.blacktwin.tls.contracts.ContractVersion;
import com.blacktwin.tls.contracts.GuestIdentity;
import com.blacktwin.tls.contracts.IRouteRegistry;
import com.blacktwin.tls.contracts.InterceptionFailureReason;
import com.blacktwin.tls.contracts.InterceptionMode;
import com.blacktwin.tls.contracts.NativeChannelRequest;
import com.blacktwin.tls.contracts.NativeChannelResult;
import com.blacktwin.tls.contracts.OpaqueId;
import com.blacktwin.tls.contracts.OperationResult;
import com.blacktwin.tls.contracts.OperationState;
import com.blacktwin.tls.contracts.OriginalDestination;
import com.blacktwin.tls.contracts.ProxyChannelRequest;
import com.blacktwin.tls.contracts.ProxyGeneration;
import com.blacktwin.tls.contracts.ProxyNativeChannelResult;
import com.blacktwin.tls.contracts.ProxyTransportRegistration;
import com.blacktwin.tls.contracts.RouteClaim;
import com.blacktwin.tls.contracts.RouteRegistration;
import com.blacktwin.tls.contracts.RouteResult;
import com.blacktwin.tls.contracts.RouteRevocation;

import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;

public final class BTlsRouteRegistryService extends IRouteRegistry.Stub implements ISystemService {
    private static final String TAG = "BTlsRouteRegistry";
    private static final int MAX_SESSIONS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BTlsRouteRegistryService INSTANCE = new BTlsRouteRegistryService();

    private static final class GuestSession {
        final GuestIdentity identity;
        final OpaqueId requestId;
        final OriginalDestination ipv4;
        final OriginalDestination ipv6;
        final ParcelFileDescriptor routeMaster;
        final ParcelFileDescriptor proofMaster;
        final IBinder deathToken;
        final IBinder.DeathRecipient deathRecipient;
        final long desiredRevision;
        final CaGeneration caGeneration;

        GuestSession(GuestIdentity identity, OpaqueId requestId, OriginalDestination ipv4,
                     OriginalDestination ipv6, ParcelFileDescriptor routeMaster,
                     ParcelFileDescriptor proofMaster, IBinder deathToken,
                     IBinder.DeathRecipient deathRecipient, long desiredRevision, CaGeneration caGeneration) {
            this.identity = identity;
            this.requestId = requestId;
            this.ipv4 = ipv4;
            this.ipv6 = ipv6;
            this.routeMaster = routeMaster;
            this.proofMaster = proofMaster;
            this.deathToken = deathToken;
            this.deathRecipient = deathRecipient;
            this.desiredRevision = desiredRevision;
            this.caGeneration = caGeneration;
        }
    }

    private final OpaqueId registryGeneration = randomId();
    private final Map<Integer, GuestSession> sessionsByPid = new HashMap<>();
    private final ArrayDeque<OriginalDestination> freeIpv4 = new ArrayDeque<>();
    private final ArrayDeque<OriginalDestination> freeIpv6 = new ArrayDeque<>();
    private ProxyTransportRegistration proxyTransport;
    private OpaqueId proxyControllerGeneration;
    private IBinder proxyDeathToken;
    private IBinder.DeathRecipient proxyDeathRecipient;
    private boolean nativeReady;
    private boolean networkObserverReady;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private DefaultNetworkSnapshot.State networkSnapshot;

    static {
        try { System.loadLibrary("blacktwin_tls_transport"); }
        catch (Throwable error) { Log.e(TAG, "Cannot load TLS transport", error); }
    }

    public static BTlsRouteRegistryService get() { return INSTANCE; }
    private BTlsRouteRegistryService() {}
    public OpaqueId getRegistryGeneration() { return registryGeneration; }
    synchronized boolean matchesProxyTransport(ProxyGeneration generation) {
        return proxyTransport != null && proxyTransport.getProxyGeneration().equals(generation);
    }

    synchronized boolean isCurrentGuestSession(GuestIdentity identity) {
        if (identity == null) return false;
        GuestSession session = sessionsByPid.get(identity.getPhysicalPid());
        return session != null && session.identity.equals(identity) &&
                TlsServiceSecurity.readStartTicks(identity.getPhysicalPid()) == identity.getProcessStartTicks();
    }

    synchronized boolean hasCurrentGuestConfiguration(int pid, AppScope scope, long desiredRevision,
                                                       CaGeneration caGeneration) {
        GuestSession session = sessionsByPid.get(pid);
        return session != null && session.identity.getScope().equals(scope) &&
                session.desiredRevision == desiredRevision &&
                java.util.Objects.equals(session.caGeneration, caGeneration) &&
                TlsServiceSecurity.readStartTicks(pid) == session.identity.getProcessStartTicks();
    }

    @Override
    public synchronized void systemReady() {
        nativeReady = false;
        stopNetworkObserverLocked();
        closeAllSessions();
        clearProxyLocked();
        Context context = top.niunaijun.blackbox.BlackBoxCore.getContext();
        connectivityManager = context == null ? null :
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            nativeReady = false;
            Log.e(TAG, "TLS_NETWORK_OBSERVER_UNAVAILABLE reason=connectivity_service_missing");
            return;
        }
        try {
            Network initialNetwork = connectivityManager.getActiveNetwork();
            LinkProperties initialProperties = initialNetwork == null ? null :
                    connectivityManager.getLinkProperties(initialNetwork);
            networkSnapshot = DefaultNetworkSnapshot.initial(1, initialNetwork, initialProperties);
        } catch (RuntimeException failure) {
            stopNetworkObserverLocked();
            nativeReady = false;
            Log.e(TAG, "TLS_NETWORK_OBSERVER_UNAVAILABLE reason=initial_snapshot_failed");
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { handleDefaultNetworkAvailable(network); }
            @Override public void onLost(Network network) { handleDefaultNetworkLost(network); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                handleDefaultLinkPropertiesChanged(network, properties);
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException failure) {
            stopNetworkObserverLocked();
            nativeReady = false;
            Log.e(TAG, "TLS_NETWORK_OBSERVER_UNAVAILABLE reason=registration_failed");
            return;
        }
        // Close the interval between the initial getActiveNetwork() and callback
        // registration. Keep callback events authoritative when this query is null.
        try {
            Network registeredNetwork = connectivityManager.getActiveNetwork();
            LinkProperties registeredProperties = registeredNetwork == null ? null :
                    connectivityManager.getLinkProperties(registeredNetwork);
            networkSnapshot = DefaultNetworkSnapshot.refreshAfterRegistration(
                    networkSnapshot, registeredNetwork, registeredProperties);
        } catch (RuntimeException failure) {
            Log.w(TAG, "TLS_NETWORK_SNAPSHOT_REFRESH_FAILED reason=query_failed");
        }
        Log.i(TAG, "TLS_NETWORK_SNAPSHOT_READY available=" +
                (networkSnapshot != null && networkSnapshot.isAvailable()) +
                " epoch=" + (networkSnapshot == null ? 0 : networkSnapshot.epoch));
        // Callback delivery can race registration and native startup. Its event
        // arguments are folded into networkSnapshot before nativeStartRegistry.
        if (networkSnapshot == null || networkCallback == null) {
            nativeReady = false;
            stopNetworkObserverLocked();
            Log.e(TAG, "TLS_NETWORK_OBSERVER_UNAVAILABLE reason=initial_callback_state_invalid");
            return;
        }
        nativeReady = nativeStartRegistry(registryGeneration.getHex(), networkSnapshot.epoch,
                networkSnapshot.isAvailable());
        if (!nativeReady) {
            stopNetworkObserverLocked();
            return;
        }
        networkObserverReady = true;
    }

    @Override
    public synchronized NativeChannelResult openNativeChannel(NativeChannelRequest request, IBinder deathToken) {
        BProcessManagerService.NetworkCaller caller = TlsServiceSecurity.requireGuestCaller();
        if (proxyTransport != null && !nativeIsProxyChannelReady()) {
            Log.w(TAG, "TLS_NATIVE_CHANNEL_REJECT phase=proxy_transport_state proxyPresent=true nativeReady=false" +
                    " profile=" + caller.userId + " package=" + caller.packageName);
            clearProxyLocked();
        }
        if (!nativeReady || !networkObserverReady || networkSnapshot == null ||
                !networkSnapshot.isAvailable() || networkSnapshot.epoch <= 0 ||
                request == null || deathToken == null || request.getSchemaVersion() != ContractVersion.SCHEMA) {
            Log.w(TAG, "TLS_NATIVE_CHANNEL_REJECT phase=prerequisite nativeReady=" + nativeReady +
                    " requestPresent=" + (request != null) + " deathTokenPresent=" + (deathToken != null) +
                    " schema=" + (request == null ? -1 : request.getSchemaVersion()) +
                    " expectedSchema=" + ContractVersion.SCHEMA + " pid=" + caller.pid +
                    " package=" + caller.packageName + " networkObserverReady=" + networkObserverReady +
                    " networkAvailable=" + (networkSnapshot != null && networkSnapshot.isAvailable()) +
                    " networkEpoch=" + (networkSnapshot == null ? 0 : networkSnapshot.epoch));
            return failedNative(InterceptionFailureReason.PREREQUISITE_UNAVAILABLE);
        }
        GuestSession existing = sessionsByPid.get(caller.pid);
        long startTicks = TlsServiceSecurity.readStartTicks(caller.pid);
        if (existing != null) {
            if (existing.identity.getProcessStartTicks() != startTicks) {
                closeSessionLocked(caller.pid);
            } else {
                return duplicateResult(existing);
            }
        }
        if (sessionsByPid.size() >= MAX_SESSIONS || proxyTransport == null || startTicks <= 0) {
            Log.w(TAG, "TLS_NATIVE_CHANNEL_REJECT phase=proxy_prerequisite sessions=" + sessionsByPid.size() +
                    " proxyPresent=" + (proxyTransport != null) + " startTicksValid=" + (startTicks > 0) +
                    " profile=" + caller.userId + " package=" + caller.packageName);
            return failedNative(InterceptionFailureReason.PROXY_NOT_READY);
        }
        AppScope scope = new AppScope(caller.userId, caller.packageName);
        ConfigPublication publication = BTlsConfigurationService.get().current(scope);
        if (publication == null || publication.getProxyGeneration() == null ||
                !publication.getProxyGeneration().equals(proxyTransport.getProxyGeneration())) {
            Log.w(TAG, "TLS_NATIVE_CHANNEL_REJECT phase=publication_prerequisite publicationPresent=" +
                    (publication != null) + " generationPresent=" +
                    (publication != null && publication.getProxyGeneration() != null) +
                    " generationMatches=" + (publication != null && publication.getProxyGeneration() != null &&
                    publication.getProxyGeneration().equals(proxyTransport.getProxyGeneration())) +
                    " profile=" + caller.userId + " package=" + caller.packageName);
            return failedNative(InterceptionFailureReason.PROXY_NOT_READY);
        }
        OriginalDestination ipv4 = freeIpv4.pollFirst();
        OriginalDestination ipv6 = freeIpv6.pollFirst();
        if (ipv4 == null && ipv6 == null) return failedNative(InterceptionFailureReason.ROUTE_QUEUE_FULL);

        OpaqueId sessionId = randomId();
        GuestIdentity identity = new GuestIdentity(scope, sessionId, caller.processName, caller.pid,
                startTicks, registryGeneration);
        java.util.List<com.blacktwin.tls.contracts.DomainExclusion> exclusions =
                publication.getConfig().getExclusions();
        String[] exclusionNames = new String[exclusions.size()];
        boolean[] exclusionSubdomains = new boolean[exclusions.size()];
        for (int index = 0; index < exclusions.size(); index++) {
            exclusionNames[index] = exclusions.get(index).getAsciiDnsName();
            exclusionSubdomains[index] = exclusions.get(index).getIncludeSubdomains();
        }
        int[] fds = nativeOpenGuestChannels(
                sessionId.getHex(), proxyTransport.getProxyGeneration().getId().getHex(),
                proxyTransport.getListenerGeneration().getHex(), caller.userId, caller.pid, startTicks,
                publication.getConfig().getDesiredRevision(),
                ipv4 != null ? ipv4.getPort() : 0, ipv6 != null ? ipv6.getPort() : 0,
                caller.packageName, publication.getCaGeneration() == null ? ""
                        : publication.getCaGeneration().getId().getHex(),
                exclusionNames, exclusionSubdomains);
        if (fds == null || fds.length != 2 || fds[0] < 0 || fds[1] < 0) {
            Log.e(TAG, "TLS_NATIVE_CHANNEL_REJECT phase=native_open_guest_channels result=" +
                    (fds == null ? "null" : "invalid_fds") + " nativeReady=" + nativeReady +
                    " pid=" + caller.pid + " package=" + caller.packageName +
                    " profile=" + caller.userId + " startTicksValid=" + (startTicks > 0) +
                    " proxyGenerationPresent=" + (proxyTransport != null) +
                    " listenerGenerationPresent=" + (proxyTransport != null && proxyTransport.getListeners() != null && !proxyTransport.getListeners().isEmpty()));
            releaseListeners(ipv4, ipv6);
            return failedNative(InterceptionFailureReason.PREREQUISITE_UNAVAILABLE);
        }
        ParcelFileDescriptor routeMaster = ParcelFileDescriptor.adoptFd(fds[0]);
        ParcelFileDescriptor proofMaster = ParcelFileDescriptor.adoptFd(fds[1]);
        IBinder.DeathRecipient recipient = () -> {
            synchronized (BTlsRouteRegistryService.this) { closeSessionLocked(caller.pid); }
        };
        try {
            deathToken.linkToDeath(recipient, 0);
        } catch (RemoteException error) {
            closeQuietly(routeMaster);
            closeQuietly(proofMaster);
            releaseListeners(ipv4, ipv6);
            nativeCloseGuestSession(sessionId.getHex());
            return failedNative(InterceptionFailureReason.GUEST_DIED);
        }
        GuestSession session = new GuestSession(identity, request.getRequestId(), ipv4, ipv6,
                routeMaster, proofMaster, deathToken, recipient,
                publication.getConfig().getDesiredRevision(), publication.getCaGeneration());
        sessionsByPid.put(caller.pid, session);
        return duplicateResult(session);
    }

    @Override
    public synchronized ProxyNativeChannelResult openProxyChannel(ProxyChannelRequest request, IBinder deathToken) {
        TlsServiceSecurity.enforceMainCaller();
        if (!nativeReady || request == null || deathToken == null) {
            return failedProxy(InterceptionFailureReason.PREREQUISITE_UNAVAILABLE);
        }
        clearProxyLocked();
        IBinder.DeathRecipient recipient = () -> {
            synchronized (BTlsRouteRegistryService.this) { clearProxyLocked(); }
        };
        try {
            deathToken.linkToDeath(recipient, 0);
        } catch (RemoteException error) {
            return failedProxy(InterceptionFailureReason.PROXY_DIED);
        }
        int fd = nativeOpenProxyChannel(request.getProxyGeneration().getId().getHex());
        if (fd >= 0) {
            proxyControllerGeneration = request.getControllerGeneration();
            proxyDeathToken = deathToken;
            proxyDeathRecipient = recipient;
        } else {
            try { deathToken.unlinkToDeath(recipient, 0); } catch (Throwable ignored) {}
        }
        if (fd < 0) return failedProxy(InterceptionFailureReason.PREREQUISITE_UNAVAILABLE);
        return new ProxyNativeChannelResult(InterceptionFailureReason.NONE,
                ContractVersion.NATIVE_TRANSPORT_SCHEMA, registryGeneration,
                request.getProxyGeneration(), ParcelFileDescriptor.adoptFd(fd));
    }

    @Override
    public synchronized OperationResult registerProxyTransport(ProxyTransportRegistration registration) {
        TlsServiceSecurity.enforceMainCaller();
        if (!nativeReady || registration == null || !registryGeneration.equals(registration.getRegistryGeneration())) {
            return operation(registration != null ? registration.getRequestId() : randomId(),
                    OperationState.REJECTED, InterceptionFailureReason.STALE_REGISTRY_GENERATION, null);
        }
        if (!registration.getControllerGeneration().equals(proxyControllerGeneration)) {
            return operation(registration.getRequestId(), OperationState.REJECTED,
                    InterceptionFailureReason.UNAUTHORIZED, null);
        }
        if (registration.getListeners().size() > ContractVersion.MAX_PROXY_LISTENERS ||
                registration.getListeners().isEmpty()) {
            return operation(registration.getRequestId(), OperationState.REJECTED,
                    InterceptionFailureReason.INVALID_ARGUMENT, null);
        }
        Set<String> endpoints = new HashSet<>();
        for (OriginalDestination listener : registration.getListeners()) {
            String address = listener.getAddress().getAddressHex();
            boolean loopback = (listener.getAddress().getFamily() == AddressFamily.IPV4 && "7f000001".equals(address)) ||
                    (listener.getAddress().getFamily() == AddressFamily.IPV6 &&
                            "00000000000000000000000000000001".equals(address));
            String key = listener.getAddress().getFamily().name() + ":" + listener.getPort();
            if (!loopback || !endpoints.add(key)) {
                return operation(registration.getRequestId(), OperationState.REJECTED,
                        InterceptionFailureReason.INVALID_ARGUMENT, null);
            }
        }
        proxyTransport = registration;
        closeAllSessions();
        freeIpv4.clear();
        freeIpv6.clear();
        for (OriginalDestination listener : registration.getListeners()) {
            if (listener.getAddress().getFamily() == AddressFamily.IPV4) freeIpv4.add(listener);
            else freeIpv6.add(listener);
        }
        boolean configured = nativeRegisterProxyTransport(
                registration.getProxyGeneration().getId().getHex(),
                registration.getListenerGeneration().getHex());
        if (!configured) {
            proxyTransport = null;
            freeIpv4.clear();
            freeIpv6.clear();
        }
        return operation(registration.getRequestId(), configured ? OperationState.COMPLETED : OperationState.REJECTED,
                configured ? InterceptionFailureReason.NONE : InterceptionFailureReason.PREREQUISITE_UNAVAILABLE, null);
    }

    @Override
    public RouteResult registerRoute(RouteRegistration registration) {
        TlsServiceSecurity.requireGuestCaller();
        return new RouteResult(InterceptionFailureReason.REDIRECT_UNAVAILABLE, null);
    }

    @Override
    public OperationResult revokeRoute(RouteRevocation revocation) {
        TlsServiceSecurity.requireGuestCaller();
        return operation(revocation.getRouteId(), OperationState.REJECTED,
                InterceptionFailureReason.REDIRECT_UNAVAILABLE, null);
    }

    @Override
    public RouteResult claimRoute(RouteClaim claim) {
        throw new SecurityException("Proxy claims use the attested native channel");
    }

    public synchronized void onGuestProcessDied(int pid) { closeSessionLocked(pid); }

    synchronized void onConfigurationPublished(ConfigPublication publication, boolean hasEnabledPublications) {
        AppScope scope = publication.getConfig().getScope();
        boolean disabled = publication.getConfig().getMode() == InterceptionMode.DISABLED;
        long revision = publication.getConfig().getDesiredRevision();
        for (Integer pid : sessionsByPid.keySet().toArray(new Integer[0])) {
            GuestSession session = sessionsByPid.get(pid);
            if (session == null || !session.identity.getScope().equals(scope)) continue;
            if (disabled || session.desiredRevision != revision || publication.getProxyGeneration() == null ||
                    !publication.getProxyGeneration().equals(proxyTransport != null
                            ? proxyTransport.getProxyGeneration() : null) ||
                    (session.caGeneration == null ? publication.getCaGeneration() != null
                            : !session.caGeneration.equals(publication.getCaGeneration()))) {
                closeSessionLocked(pid);
            }
        }
        if (!hasEnabledPublications) clearProxyLocked();
    }

    private NativeChannelResult duplicateResult(GuestSession session) {
        try {
            return new NativeChannelResult(InterceptionFailureReason.NONE,
                    ContractVersion.NATIVE_TRANSPORT_SCHEMA, session.identity,
                    proxyTransport.getProxyGeneration(), proxyTransport.getListenerGeneration(),
                    session.ipv4, session.ipv6, session.routeMaster.dup(), session.proofMaster.dup(),
                    networkSnapshot == null ? 0 : networkSnapshot.epoch);
        } catch (IOException error) {
            return failedNative(InterceptionFailureReason.INTERNAL_ERROR);
        }
    }

    private NativeChannelResult failedNative(InterceptionFailureReason reason) {
        return new NativeChannelResult(reason, ContractVersion.NATIVE_TRANSPORT_SCHEMA,
                null, null, null, null, null, null, null, 0);
    }

    private ProxyNativeChannelResult failedProxy(InterceptionFailureReason reason) {
        return new ProxyNativeChannelResult(reason, ContractVersion.NATIVE_TRANSPORT_SCHEMA,
                null, null, null);
    }

    private void closeSessionLocked(int pid) {
        GuestSession session = sessionsByPid.remove(pid);
        if (session == null) return;
        try { session.deathToken.unlinkToDeath(session.deathRecipient, 0); } catch (Throwable ignored) {}
        closeQuietly(session.routeMaster);
        closeQuietly(session.proofMaster);
        nativeCloseGuestSession(session.identity.getGuestSessionId().getHex());
        releaseListeners(session.ipv4, session.ipv6);
    }

    private void closeAllSessions() {
        for (Integer pid : sessionsByPid.keySet().toArray(new Integer[0])) closeSessionLocked(pid);
    }

    private void clearProxyLocked() {
        closeAllSessions();
        if (proxyDeathToken != null && proxyDeathRecipient != null) {
            try { proxyDeathToken.unlinkToDeath(proxyDeathRecipient, 0); } catch (Throwable ignored) {}
        }
        proxyDeathToken = null;
        proxyDeathRecipient = null;
        proxyControllerGeneration = null;
        proxyTransport = null;
        freeIpv4.clear();
        freeIpv6.clear();
        nativeCloseProxyChannel();
    }

    private void handleDefaultNetworkAvailable(Network network) {
        synchronized (this) {
            if (networkCallback == null || networkSnapshot == null) return;
            applyNetworkSnapshotLocked(DefaultNetworkSnapshot.onAvailable(networkSnapshot, network, null));
        }
    }

    private void handleDefaultNetworkLost(Network network) {
        synchronized (this) {
            if (networkCallback == null || networkSnapshot == null) return;
            applyNetworkSnapshotLocked(DefaultNetworkSnapshot.onLost(networkSnapshot, network));
        }
    }

    private void handleDefaultLinkPropertiesChanged(Network network, LinkProperties properties) {
        synchronized (this) {
            if (networkCallback == null || networkSnapshot == null) return;
            applyNetworkSnapshotLocked(DefaultNetworkSnapshot.onLinkPropertiesChanged(
                    networkSnapshot, network, properties));
        }
    }

    private void applyNetworkSnapshotLocked(DefaultNetworkSnapshot.State next) {
        if (next == null) {
            failClosedForNetworkObserverLocked("epoch_exhausted");
            return;
        }
        if (next == networkSnapshot) return;
        if (!nativeReady) {
            networkSnapshot = next;
            return;
        }
        if (!nativeUpdateNetworkEpoch(next.epoch, next.isAvailable())) {
            failClosedForNetworkObserverLocked("native_update_rejected");
            return;
        }
        networkSnapshot = next;
        Log.i(TAG, "TLS_NETWORK_EPOCH_CHANGED available=" + next.isAvailable() +
                " epoch=" + next.epoch);
    }

    private void failClosedForNetworkObserverLocked(String reason) {
        Log.e(TAG, "TLS_NETWORK_EPOCH_FAIL_CLOSED reason=" + reason);
        stopNetworkObserverLocked();
        nativeReady = false;
        clearProxyLocked();
    }

    private void stopNetworkObserverLocked() {
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) {}
        }
        networkCallback = null;
        networkObserverReady = false;
        connectivityManager = null;
        networkSnapshot = null;
    }

    private void releaseListeners(OriginalDestination ipv4, OriginalDestination ipv6) {
        if (ipv4 != null) freeIpv4.addLast(ipv4);
        if (ipv6 != null) freeIpv6.addLast(ipv6);
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        try { if (fd != null) fd.close(); } catch (IOException ignored) {}
    }

    private static OperationResult operation(OpaqueId id, OperationState state,
                                             InterceptionFailureReason failure, Long revision) {
        return new OperationResult(id, state, failure, revision);
    }

    static OpaqueId randomId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        boolean zero = true;
        for (byte value : bytes) zero &= value == 0;
        if (zero) bytes[0] = 1;
        StringBuilder result = new StringBuilder(32);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return new OpaqueId(result.toString());
    }

    private static native boolean nativeStartRegistry(String registryGeneration, long networkEpoch,
            boolean networkAvailable);
    private static native int[] nativeOpenGuestChannels(String guestSessionId, String proxyGeneration,
            String listenerGeneration, int profileId, int physicalPid, long processStartTicks,
            long desiredRevision, int ipv4ListenerPort, int ipv6ListenerPort, String packageName,
            String caGeneration, String[] exclusionNames, boolean[] exclusionSubdomains);
    private static native int nativeOpenProxyChannel(String proxyGeneration);
    private static native boolean nativeRegisterProxyTransport(String proxyGeneration, String listenerGeneration);
    private static native boolean nativeUpdateNetworkEpoch(long networkEpoch, boolean networkAvailable);
    private static native boolean nativeIsProxyChannelReady();
    private static native void nativeCloseProxyChannel();
    private static native void nativeCloseGuestSession(String guestSessionId);
}
