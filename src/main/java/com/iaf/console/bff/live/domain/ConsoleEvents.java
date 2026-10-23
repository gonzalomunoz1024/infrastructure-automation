package com.iaf.console.bff.live.domain;

import java.time.Instant;
import java.util.Optional;

import com.iaf.console.kit.events.EventEnvelope;

/** The console's own events (state.resources, drift.findings, identity.changes) as live events, scoped on {@code context.accountId}. */
public final class ConsoleEvents {

    private ConsoleEvents() {
    }

    public static Optional<PlatformEvents.Mapped> toLive(EventEnvelope envelope) {
        if (envelope == null || envelope.accountId() == null) {
            return Optional.empty();
        }
        String wfrn = envelope.context() == null ? null : envelope.context().wfrn();
        Long version = envelope.data() != null && envelope.data().hasNonNull("version") ? envelope.data().get("version").asLong() : null;
        Instant at = envelope.at() == null ? Instant.now() : envelope.at();
        return Optional.of(new PlatformEvents.Mapped(envelope.accountId(), new LiveEvent(envelope.type(), wfrn, at, version, envelope.key(), envelope.data())));
    }

    /** Whether an event changes who may do what in its account. */
    public static boolean changesIdentity(EventEnvelope envelope) {
        String type = envelope.type() == null ? "" : envelope.type();
        return type.endsWith("Changed") && envelope.aggregate() != null
                && switch (envelope.aggregate()) {
                    case "account", "user", "group", "role", "policy", "accessKey", "quota" -> true;
                    default -> false;
                };
    }
}
