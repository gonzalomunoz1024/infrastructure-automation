package com.iaf.platform.providers.adapters.outbound;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.providers.ports.outbound.ProviderAlertStatePort;

/**
 * The demo's alert evaluator state: kept for the life of the process, like the demo's telemetry.
 * The {@code mongo} profile keeps it in MongoDB ({@link MongoProviderAlertState}).
 */
@Profile("!mongo")
@Component
public class InMemoryProviderAlertState implements ProviderAlertStatePort {

    private final AtomicReference<Instant> last = new AtomicReference<>();

    @Override
    public Optional<Instant> lastEvaluatedAt() {
        return Optional.ofNullable(last.get());
    }

    @Override
    public void lastEvaluatedAt(Instant at) {
        if (at != null) {
            last.accumulateAndGet(at, (kept, given) -> kept == null || given.isAfter(kept) ? given : kept);
        }
    }
}
