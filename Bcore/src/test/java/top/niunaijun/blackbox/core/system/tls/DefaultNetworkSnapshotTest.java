package top.niunaijun.blackbox.core.system.tls;

/** Standalone deterministic test; run with javac/java without Android or Gradle. */
public final class DefaultNetworkSnapshotTest {
    public static void main(String[] args) {
        callbackAvailabilityRecoversNullInitialSnapshot();
        staleLossDoesNotClearReplacementNetwork();
        currentLossClearsNetworkAndAdvancesEpoch();
        staleLinkPropertiesAreIgnored();
        duplicateEventsDoNotAdvanceEpoch();
        callbackBeforeNativeStartupIsRetained();
        postRegistrationRefreshClosesQueryRegistrationRace();
        epochExhaustionFailsClosed();
        System.out.println("PASS DefaultNetworkSnapshotTest (8 cases)");
    }

    private static void callbackAvailabilityRecoversNullInitialSnapshot() {
        Object wifi = new Object();
        DefaultNetworkSnapshot.State before = DefaultNetworkSnapshot.initial(1, null, null);
        DefaultNetworkSnapshot.State after = DefaultNetworkSnapshot.onAvailable(before, wifi, null);
        check(after != before && after.isAvailable() && after.epoch == 2,
                "onAvailable must be authoritative after a null initial query");
    }

    private static void staleLossDoesNotClearReplacementNetwork() {
        Object oldNetwork = new Object();
        Object newNetwork = new Object();
        DefaultNetworkSnapshot.State initial = DefaultNetworkSnapshot.initial(1, oldNetwork, "old-links");
        DefaultNetworkSnapshot.State replacement =
                DefaultNetworkSnapshot.onAvailable(initial, newNetwork, "new-links");
        DefaultNetworkSnapshot.State afterStaleLoss =
                DefaultNetworkSnapshot.onLost(replacement, oldNetwork);
        check(replacement.epoch == 2 && afterStaleLoss == replacement && afterStaleLoss.isAvailable(),
                "late loss for old default must not revoke replacement");
    }

    private static void currentLossClearsNetworkAndAdvancesEpoch() {
        Object network = new Object();
        DefaultNetworkSnapshot.State initial = DefaultNetworkSnapshot.initial(4, network, "links");
        DefaultNetworkSnapshot.State lost = DefaultNetworkSnapshot.onLost(initial, network);
        check(lost != initial && !lost.isAvailable() && lost.epoch == 5,
                "loss of current default must clear availability and advance epoch");
    }

    private static void staleLinkPropertiesAreIgnored() {
        Object currentNetwork = new Object();
        Object staleNetwork = new Object();
        DefaultNetworkSnapshot.State initial =
                DefaultNetworkSnapshot.initial(9, currentNetwork, "current-links");
        DefaultNetworkSnapshot.State afterStaleUpdate =
                DefaultNetworkSnapshot.onLinkPropertiesChanged(initial, staleNetwork, "stale-links");
        check(afterStaleUpdate == initial && afterStaleUpdate.epoch == 9,
                "link properties for a non-current network must be ignored");
        DefaultNetworkSnapshot.State afterCurrentUpdate =
                DefaultNetworkSnapshot.onLinkPropertiesChanged(initial, currentNetwork, "updated-links");
        check(afterCurrentUpdate != initial && afterCurrentUpdate.epoch == 10,
                "link properties for current default must advance epoch");
    }

    private static void duplicateEventsDoNotAdvanceEpoch() {
        Object network = new Object();
        DefaultNetworkSnapshot.State initial = DefaultNetworkSnapshot.initial(2, network, "links");
        check(DefaultNetworkSnapshot.onAvailable(initial, network, "links") == initial,
                "duplicate availability must be idempotent");
        check(DefaultNetworkSnapshot.onLinkPropertiesChanged(initial, network, "links") == initial,
                "duplicate link properties must be idempotent");
        check(DefaultNetworkSnapshot.onLost(initial, new Object()) == initial,
                "unrelated loss must be idempotent");
    }

    private static void callbackBeforeNativeStartupIsRetained() {
        Object network = new Object();
        DefaultNetworkSnapshot.State initial = DefaultNetworkSnapshot.initial(1, null, null);
        DefaultNetworkSnapshot.State callbackState =
                DefaultNetworkSnapshot.onAvailable(initial, network, null);
        check(callbackState.isAvailable() && callbackState.epoch == 2,
                "an immediate callback event must be retained for native startup");
        DefaultNetworkSnapshot.State linkState =
                DefaultNetworkSnapshot.onLinkPropertiesChanged(callbackState, network, "links");
        check(linkState.isAvailable() && linkState.epoch == 3,
                "link-properties callback must apply to the retained default network");
    }

    private static void postRegistrationRefreshClosesQueryRegistrationRace() {
        Object wifi = new Object();
        DefaultNetworkSnapshot.State before = DefaultNetworkSnapshot.initial(1, null, null);
        DefaultNetworkSnapshot.State refreshed =
                DefaultNetworkSnapshot.refreshAfterRegistration(before, wifi, "wifi-links");
        check(refreshed.isAvailable() && refreshed.epoch == 2,
                "post-registration active network must recover an initially empty snapshot");
        check(DefaultNetworkSnapshot.refreshAfterRegistration(refreshed, null, null) == refreshed,
                "a null refresh must not erase an available callback snapshot");
    }

    private static void epochExhaustionFailsClosed() {
        Object network = new Object();
        DefaultNetworkSnapshot.State initial =
                DefaultNetworkSnapshot.initial(Long.MAX_VALUE, network, "links");
        check(DefaultNetworkSnapshot.onLost(initial, network) == null,
                "epoch overflow must signal fail-closed to caller");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
