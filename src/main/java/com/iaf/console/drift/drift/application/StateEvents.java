package com.iaf.console.drift.drift.application;

import java.util.Set;

import reactor.core.publisher.Mono;

/**
 * What an event of the state service's {@code state.resources} topic means here: {@code Deleted}
 * (the tombstone, the only event a succeeded delete produces) takes the resource off the watch
 * list and closes its finding; the echoes of this service's own calls ({@code Observed},
 * {@code DriftDetected}, {@code DriftResolved}) are skipped; anything else, a
 * {@code ProvisioningSucceeded} above all, reads the resource again, which makes it hot and due now.
 */
public final class StateEvents {

    /** The tombstone. */
    public static final String DELETED = "Deleted";
    /** The events this service's own calls produce. */
    public static final Set<String> ECHOES = Set.of("Observed", "DriftDetected", "DriftResolved");

    private final InventorySync sync;

    public StateEvents(InventorySync sync) {
        this.sync = sync;
    }

    public Mono<Void> handle(String type, String wfrn) {
        if (type == null || wfrn == null || ECHOES.contains(type)) {
            return Mono.empty();
        }
        if (DELETED.equals(type)) {
            return sync.removed(wfrn).then();
        }
        return sync.refresh(wfrn).then();
    }
}
