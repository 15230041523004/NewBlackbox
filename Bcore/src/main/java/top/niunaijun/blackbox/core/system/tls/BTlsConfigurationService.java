package top.niunaijun.blackbox.core.system.tls;

import android.os.Binder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.blacktwin.tls.contracts.AppScope;
import com.blacktwin.tls.contracts.CaGeneration;
import com.blacktwin.tls.contracts.ConfigPublication;
import com.blacktwin.tls.contracts.ConfigResult;
import com.blacktwin.tls.contracts.IConfigurationPublisher;
import com.blacktwin.tls.contracts.InterceptionFailureReason;
import com.blacktwin.tls.contracts.InterceptionMode;
import com.blacktwin.tls.contracts.ProxyCapabilityLevel;
import com.blacktwin.tls.contracts.OpaqueId;
import com.blacktwin.tls.contracts.OperationResult;
import com.blacktwin.tls.contracts.OperationState;
import com.blacktwin.tls.contracts.ProcessApplicationAck;
import com.blacktwin.tls.contracts.GuestIdentity;
import com.blacktwin.tls.contracts.RevisionState;

import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;

public final class BTlsConfigurationService extends IConfigurationPublisher.Stub implements ISystemService {
    private static final BTlsConfigurationService INSTANCE = new BTlsConfigurationService();
    private static final int MAX_TRUST_ANCHOR_DER_BYTES = 16_384;
    private static final int MAX_APPLIED_ACKS = 128;
    private final Map<AppScope, ConfigPublication> publications = new ConcurrentHashMap<>();
    private final Map<AppScope, TrustAnchorRecord> trustAnchors = new ConcurrentHashMap<>();
    private final Map<String, ProcessApplicationAck> applied = new ConcurrentHashMap<>();

    private static final class TrustAnchorRecord {
        final long desiredRevision;
        final CaGeneration generation;
        final byte[] der;
        TrustAnchorRecord(long desiredRevision, CaGeneration generation, byte[] der) {
            this.desiredRevision = desiredRevision;
            this.generation = generation;
            this.der = der.clone();
        }
    }

    public static BTlsConfigurationService get() { return INSTANCE; }
    private BTlsConfigurationService() {}

    @Override public void systemReady() {
        publications.clear();
        trustAnchors.clear();
        applied.clear();
    }

    @Override
    public OperationResult publish(ConfigPublication publication) {
        TlsServiceSecurity.enforceMainCaller();
        if (publication == null || !publication.getRegistryGeneration().equals(BTlsRouteRegistryService.get().getRegistryGeneration())) {
            return rejected(publication != null ? publication.getConfig().getScope().getPackageName() : "invalid",
                    InterceptionFailureReason.STALE_REGISTRY_GENERATION);
        }
        boolean routingOnly = publication.getConfig().getMode() == InterceptionMode.ROUTING_ONLY;
        boolean tlsMitm = publication.getConfig().getMode() == InterceptionMode.TRANSPARENT_LOCAL_TLS;
        ProxyCapabilityLevel expectedCapability = routingOnly
                ? ProxyCapabilityLevel.TCP_ROUTING_ONLY
                : tlsMitm ? ProxyCapabilityLevel.TLS_INTERCEPTION : ProxyCapabilityLevel.LIFECYCLE_ONLY;
        if (publication.getConfig().getMode() != InterceptionMode.DISABLED &&
                (publication.getCapabilityLevel() != expectedCapability ||
                 publication.getProxyGeneration() == null ||
                 !BTlsRouteRegistryService.get().matchesProxyTransport(publication.getProxyGeneration()) ||
                 (tlsMitm && publication.getCaGeneration() == null) ||
                 (routingOnly && publication.getCaGeneration() != null))) {
            return rejected(publication.getConfig().getScope().getPackageName(),
                    InterceptionFailureReason.PROXY_NOT_READY);
        }
        if (publication.getConfig().getMode() == InterceptionMode.DISABLED &&
                publication.getCapabilityLevel() != ProxyCapabilityLevel.LIFECYCLE_ONLY) {
            return rejected(publication.getConfig().getScope().getPackageName(),
                    InterceptionFailureReason.PROCESS_ROLE_MISMATCH);
        }
        AppScope scope = publication.getConfig().getScope();
        if (tlsMitm) {
            TrustAnchorRecord anchor = trustAnchors.get(scope);
            if (anchor == null || anchor.desiredRevision != publication.getConfig().getDesiredRevision() ||
                    !anchor.generation.equals(publication.getCaGeneration())) {
                return rejected(scope.getPackageName(), InterceptionFailureReason.CA_NOT_READY);
            }
        }
        ConfigPublication current = publications.get(scope);
        if (current != null && current.getConfig().getDesiredRevision() > publication.getConfig().getDesiredRevision()) {
            return rejected(publication.getConfig().getScope().getPackageName(), InterceptionFailureReason.STALE_REVISION);
        }
        if (current != null && current.getConfig().getDesiredRevision() == publication.getConfig().getDesiredRevision()) {
            boolean unchangedConfiguration = current.getConfig().equals(publication.getConfig()) &&
                    current.getRegistryGeneration().equals(publication.getRegistryGeneration()) &&
                    java.util.Objects.equals(current.getCaGeneration(), publication.getCaGeneration()) &&
                    current.getCapabilityLevel() == publication.getCapabilityLevel();
            if (!unchangedConfiguration) {
                return rejected(publication.getConfig().getScope().getPackageName(),
                        InterceptionFailureReason.STALE_REVISION);
            }
            if (current.equals(publication)) {
                return new OperationResult(randomId(), OperationState.COMPLETED,
                        InterceptionFailureReason.NONE, publication.getConfig().getDesiredRevision());
            }
        }
        publications.put(scope, publication);
        if (publication.getConfig().getMode() == InterceptionMode.DISABLED) {
            trustAnchors.remove(scope);
        } else if (routingOnly) {
            trustAnchors.remove(scope);
        }
        if (publication.getConfig().getMode() == InterceptionMode.DISABLED || current == null ||
                current.getConfig().getDesiredRevision() != publication.getConfig().getDesiredRevision()) {
            applied.entrySet().removeIf(entry -> entry.getValue().getGuest().getScope().equals(scope));
        }
        boolean hasEnabled = false;
        for (ConfigPublication candidate : publications.values()) {
            if (candidate.getConfig().getMode() != InterceptionMode.DISABLED) {
                hasEnabled = true;
                break;
            }
        }
        BTlsRouteRegistryService.get().onConfigurationPublished(publication, hasEnabled);
        return new OperationResult(randomId(), OperationState.COMPLETED, InterceptionFailureReason.NONE,
                publication.getConfig().getDesiredRevision());
    }

    @Override
    public ConfigResult getCurrent(AppScope scope) {
        int pid = Binder.getCallingPid();
        BProcessManagerService.NetworkCaller guest = BProcessManagerService.get().resolveNetworkCaller(pid);
        if (guest == null) {
            TlsServiceSecurity.enforceMainCaller();
        } else if (guest.userId != scope.getProfileId() || !guest.packageName.equals(scope.getPackageName())) {
            throw new SecurityException("Guest requested another TLS scope");
        }
        ConfigPublication publication = publications.get(scope);
        return new ConfigResult(publication == null ? InterceptionFailureReason.INVALID_ARGUMENT : InterceptionFailureReason.NONE, publication);
    }

    @Override
    public OperationResult reportApplied(ProcessApplicationAck acknowledgement) {
        BProcessManagerService.NetworkCaller guest = TlsServiceSecurity.requireGuestCaller();
        if (acknowledgement == null || acknowledgement.getGuest().getPhysicalPid() != guest.pid ||
                acknowledgement.getGuest().getScope().getProfileId() != guest.userId ||
                !acknowledgement.getGuest().getScope().getPackageName().equals(guest.packageName) ||
                !acknowledgement.getGuest().getGuestProcessName().equals(guest.processName) ||
                acknowledgement.getGuest().getProcessStartTicks() != TlsServiceSecurity.readStartTicks(guest.pid) ||
                !BTlsRouteRegistryService.get().isCurrentGuestSession(acknowledgement.getGuest())) {
            throw new SecurityException("Guest TLS acknowledgement identity mismatch");
        }
        ConfigPublication current = publications.get(acknowledgement.getGuest().getScope());
        RevisionState revisionState = acknowledgement.getRevision();
        if (current == null ||
                current.getConfig().getMode() == InterceptionMode.DISABLED ||
                revisionState.getDesiredRevision() != current.getConfig().getDesiredRevision() ||
                !revisionState.getRegistryGeneration().equals(current.getRegistryGeneration()) ||
                !java.util.Objects.equals(revisionState.getProxyGeneration(), current.getProxyGeneration()) ||
                !java.util.Objects.equals(revisionState.getCaGeneration(), current.getCaGeneration()) ||
                !java.util.Objects.equals(acknowledgement.getGuest().getRegistryGeneration(), current.getRegistryGeneration())) {
            return new OperationResult(randomId(), OperationState.REJECTED,
                    InterceptionFailureReason.STALE_REVISION, null);
        }
        boolean routingOnly = current.getConfig().getMode() == InterceptionMode.ROUTING_ONLY;
        if ((routingOnly && acknowledgement.getTrustApplied()) ||
                (acknowledgement.getFailure() == InterceptionFailureReason.NONE &&
                        (!acknowledgement.getNativeSnapshotApplied() ||
                         (!routingOnly && !acknowledgement.getTrustApplied())))) {
            return new OperationResult(randomId(), OperationState.REJECTED,
                    InterceptionFailureReason.STALE_REVISION, null);
        }
        if (acknowledgement.getTrustApplied() && acknowledgement.getNativeSnapshotApplied() &&
                (revisionState.getAppliedRevision() != revisionState.getDesiredRevision() ||
                 acknowledgement.getFailure() != InterceptionFailureReason.NONE)) {
            return new OperationResult(randomId(), OperationState.REJECTED,
                    InterceptionFailureReason.STALE_REVISION, null);
        }
        applied.put(acknowledgement.getGuest().getGuestSessionId().getHex(), acknowledgement);
        pruneApplied();
        long revision = revisionState.getAppliedRevision();
        return new OperationResult(randomId(), OperationState.COMPLETED,
                acknowledgement.getFailure(), revision);
    }

    @Override
    public OperationResult publishTrustAnchor(AppScope scope, long desiredRevision,
                                               CaGeneration caGeneration, byte[] certificateDer) {
        TlsServiceSecurity.enforceMainCaller();
        if (scope == null || desiredRevision <= 0 || caGeneration == null || certificateDer == null ||
                certificateDer.length == 0 || certificateDer.length > MAX_TRUST_ANCHOR_DER_BYTES) {
            return rejected(scope == null ? "invalid" : scope.getPackageName(),
                    InterceptionFailureReason.INVALID_ARGUMENT);
        }
        ConfigPublication current = publications.get(scope);
        if (current != null && current.getConfig().getMode() == InterceptionMode.ROUTING_ONLY &&
                current.getConfig().getDesiredRevision() == desiredRevision) {
            return rejected(scope.getPackageName(), InterceptionFailureReason.CA_NOT_READY);
        }
        if (current != null && current.getConfig().getDesiredRevision() > desiredRevision) {
            return rejected(scope.getPackageName(), InterceptionFailureReason.STALE_REVISION);
        }
        if (current != null && current.getConfig().getDesiredRevision() == desiredRevision &&
                (current.getConfig().getMode() == InterceptionMode.DISABLED ||
                 !caGeneration.equals(current.getCaGeneration()))) {
            return rejected(scope.getPackageName(), InterceptionFailureReason.STALE_CA_GENERATION);
        }
        TrustAnchorRecord previous = trustAnchors.get(scope);
        if (previous != null && previous.desiredRevision == desiredRevision &&
                previous.generation.equals(caGeneration) && !java.util.Arrays.equals(previous.der, certificateDer)) {
            return rejected(scope.getPackageName(), InterceptionFailureReason.STALE_CA_GENERATION);
        }
        trustAnchors.put(scope, new TrustAnchorRecord(desiredRevision, caGeneration, certificateDer));
        return new OperationResult(randomId(), OperationState.COMPLETED,
                InterceptionFailureReason.NONE, desiredRevision);
    }

    @Override
    public byte[] getTrustAnchor(AppScope scope, long desiredRevision, CaGeneration caGeneration) {
        int pid = Binder.getCallingPid();
        BProcessManagerService.NetworkCaller guest = BProcessManagerService.get().resolveNetworkCaller(pid);
        if (guest == null) {
            TlsServiceSecurity.enforceMainCaller();
        } else if (guest.userId != scope.getProfileId() || !guest.packageName.equals(scope.getPackageName()) ||
                !BTlsRouteRegistryService.get().hasCurrentGuestConfiguration(
                        pid, scope, desiredRevision, caGeneration)) {
            throw new SecurityException("Guest requested an unbound TLS trust anchor");
        }
        ConfigPublication current = publications.get(scope);
        TrustAnchorRecord anchor = trustAnchors.get(scope);
        if (current == null || current.getConfig().getMode() == InterceptionMode.DISABLED || anchor == null ||
                current.getConfig().getDesiredRevision() != desiredRevision ||
                !java.util.Objects.equals(current.getCaGeneration(), caGeneration) ||
                anchor.desiredRevision != desiredRevision || !anchor.generation.equals(caGeneration)) return null;
        return anchor.der.clone();
    }

    @Override
    public ProcessApplicationAck[] getApplied(AppScope scope) {
        TlsServiceSecurity.enforceMainCaller();
        ConfigPublication current = publications.get(scope);
        if (current == null || current.getConfig().getMode() == InterceptionMode.DISABLED) {
            return new ProcessApplicationAck[0];
        }
        java.util.ArrayList<ProcessApplicationAck> result = new java.util.ArrayList<>();
        for (ProcessApplicationAck acknowledgement : applied.values()) {
            if (acknowledgement.getGuest().getScope().equals(scope) &&
                    BTlsRouteRegistryService.get().isCurrentGuestSession(acknowledgement.getGuest()) &&
                    acknowledgement.getRevision().getDesiredRevision() == current.getConfig().getDesiredRevision() &&
                    java.util.Objects.equals(acknowledgement.getRevision().getProxyGeneration(), current.getProxyGeneration()) &&
                    java.util.Objects.equals(acknowledgement.getRevision().getCaGeneration(), current.getCaGeneration()) &&
                    acknowledgement.getRevision().getRegistryGeneration().equals(current.getRegistryGeneration())) {
                result.add(acknowledgement);
            }
        }
        result.sort(java.util.Comparator.comparing(a -> a.getGuest().getGuestProcessName()));
        return result.toArray(new ProcessApplicationAck[0]);
    }

    private void pruneApplied() {
        if (applied.size() <= MAX_APPLIED_ACKS) return;
        java.util.Iterator<Map.Entry<String, ProcessApplicationAck>> iterator = applied.entrySet().iterator();
        while (applied.size() > MAX_APPLIED_ACKS && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    ConfigPublication current(AppScope scope) { return publications.get(scope); }

    private static OperationResult rejected(String ignored, InterceptionFailureReason reason) {
        return new OperationResult(randomId(), OperationState.REJECTED, reason, null);
    }

    private static OpaqueId randomId() { return BTlsRouteRegistryService.randomId(); }
}
