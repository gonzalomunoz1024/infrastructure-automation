package com.iaf.platform.providers.adapters.outbound;

import java.time.Instant;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.providers.ports.outbound.ProviderAlertStatePort;

/**
 * The alert evaluator's state under the {@code postgres} profile: one row of
 * {@code provider_alert_state}, so a restarted instance picks up where the last evaluation
 * stopped. The exact instant is kept in nanoseconds beside the timestamp column, and it only
 * moves forward.
 */
@Component
@Primary
@Profile("postgres")
public class JdbcProviderAlertState implements ProviderAlertStatePort {

    static final String EVALUATOR = "evaluator";
    private static final long NANOS = 1_000_000_000L;

    private final Jdbc jdbc;

    public JdbcProviderAlertState(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> lastEvaluatedAt() {
        return jdbc.first("SELECT last_evaluated_ns FROM provider_alert_state WHERE id = ?", (rs, i) -> rs.getLong(1), EVALUATOR)
                .map(ns -> Instant.ofEpochSecond(Math.floorDiv(ns, NANOS), Math.floorMod(ns, NANOS)));
    }

    @Override
    public void lastEvaluatedAt(Instant at) {
        if (at == null) {
            return;
        }
        jdbc.upsert("UPDATE provider_alert_state SET last_evaluated_at = ?, last_evaluated_ns = ? WHERE id = ? AND last_evaluated_ns < ?",
                new Object[] {Jdbc.time(at), Jdbc.nanos(at), EVALUATOR, Jdbc.nanos(at)},
                "INSERT INTO provider_alert_state (id, last_evaluated_at, last_evaluated_ns) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                new Object[] {EVALUATOR, Jdbc.time(at), Jdbc.nanos(at)});
    }
}
