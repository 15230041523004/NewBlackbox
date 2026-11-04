package top.niunaijun.blackbox.core.system.tls;

import java.util.Objects;

/**
 * Immutable reducer for events from registerDefaultNetworkCallback().
 * Network callback events are authoritative; callers must not replace them
 * with a process-scoped getActiveNetwork() query.
 */
final class DefaultNetworkSnapshot {
    static final class State {
        final Object network;
        final Object linkProperties;
        final long epoch;

        private State(Object network, Object linkProperties, long epoch) {
            this.network = network;
            this.linkProperties = network == null ? null : linkProperties;
            this.epoch = epoch;
        }

        boolean isAvailable() { return network != null; }
    }

    private DefaultNetworkSnapshot() {}

    static State initial(long epoch, Object network, Object linkProperties) {
        if (epoch <= 0) throw new IllegalArgumentException("epoch must be positive");
        return new State(network, linkProperties, epoch);
    }

    static State onAvailable(State current, Object network, Object linkProperties) {
        if (current == null || network == null) return current;
        Object currentProperties = Objects.equals(current.network, network)
                ? current.linkProperties : linkProperties;
        return transition(current, network, currentProperties);
    }

    /**
     * Reconciles the snapshot after callback registration to close the query/register race.
     * A null query is not authoritative and must not erase a callback-delivered network.
     */
    static State refreshAfterRegistration(State current, Object activeNetwork, Object linkProperties) {
        if (activeNetwork == null) return current;
        return onAvailable(current, activeNetwork, linkProperties);
    }

    static State onLost(State current, Object lostNetwork) {
        if (current == null || lostNetwork == null ||
                !Objects.equals(current.network, lostNetwork)) return current;
        return transition(current, null, null);
    }

    static State onLinkPropertiesChanged(State current, Object network, Object linkProperties) {
        if (current == null || network == null ||
                !Objects.equals(current.network, network)) return current;
        return transition(current, network, linkProperties);
    }

    private static State transition(State current, Object network, Object linkProperties) {
        if (Objects.equals(current.network, network) &&
                Objects.equals(current.linkProperties, network == null ? null : linkProperties)) {
            return current;
        }
        if (current.epoch == Long.MAX_VALUE) return null;
        return new State(network, linkProperties, current.epoch + 1);
    }
}
