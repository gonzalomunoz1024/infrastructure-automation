package com.iaf.platform.providers.ports.outbound;

import java.time.Instant;
import java.util.Optional;

/**
 * Where the provider alert evaluator keeps the instant of its last evaluation, so the first
 * evaluation after a restart starts from there instead of raising again an alert for every failed
 * call of the last day. The demo keeps it in memory; the {@code mongo} profile in MongoDB.
 */
public interface ProviderAlertStatePort {

    /** The instant of the last evaluation; empty before the first one. */
    Optional<Instant> lastEvaluatedAt();

    /** Remembers an evaluation; an instant older than the one kept changes nothing. */
    void lastEvaluatedAt(Instant at);
}
