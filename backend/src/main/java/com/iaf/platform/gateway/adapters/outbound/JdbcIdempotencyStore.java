package com.iaf.platform.gateway.adapters.outbound;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.gateway.domain.KeyedRequest;
import com.iaf.platform.gateway.domain.StoredAnswer;
import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;

import lombok.extern.slf4j.Slf4j;

/**
 * The idempotency keys of the {@code postgres} profile: one row per caller and key in
 * {@code idempotency_keys}, shared by every API replica. A claim is an insert; when the row
 * exists it is locked ({@code SELECT ... FOR UPDATE}) and either returned, when it still holds
 * (not expired, and answered or not abandoned), or taken over. A completion stores the answer, a
 * release deletes an unanswered row, and a sweep every ten minutes deletes the rows past their
 * expiry ({@code platform.gateway.idempotency.ttl} from when the key was claimed).
 */
@Slf4j
@Component
@Primary
@Profile("postgres")
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {
    };
    private static final String INSERT = "INSERT INTO idempotency_keys (caller, request_key, fingerprint, created_at, created_ns, expires_ns)"
            + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    private static final String SELECT = "SELECT fingerprint, created_ns, expires_ns, status, headers, body FROM idempotency_keys"
            + " WHERE caller = ? AND request_key = ? FOR UPDATE";
    private static final String TAKE_OVER = "UPDATE idempotency_keys SET fingerprint = ?, created_at = ?, created_ns = ?, expires_ns = ?,"
            + " status = NULL, headers = NULL, body = NULL WHERE caller = ? AND request_key = ?";

    private final Jdbc jdbc;
    private final Clock clock;

    public JdbcIdempotencyStore(Jdbc jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<KeyedRequest> claim(KeyedRequest inFlight, Instant now) {
        return jdbc.inTransaction(() -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (jdbc.insertIfAbsent(INSERT, inFlight.caller(), inFlight.key(), inFlight.fingerprint(), Jdbc.time(inFlight.createdAt()),
                        Jdbc.nanos(inFlight.createdAt()), Jdbc.nanos(inFlight.expiresAt()))) {
                    return Optional.<KeyedRequest>empty();
                }
                Optional<KeyedRequest> held = jdbc.first(SELECT, (rs, i) -> row(inFlight.caller(), inFlight.key(), rs), inFlight.caller(),
                        inFlight.key());
                if (held.isPresent()) {
                    if (held.get().liveAt(now)) {
                        return held;
                    }
                    jdbc.update(TAKE_OVER, inFlight.fingerprint(), Jdbc.time(inFlight.createdAt()), Jdbc.nanos(inFlight.createdAt()),
                            Jdbc.nanos(inFlight.expiresAt()), inFlight.caller(), inFlight.key());
                    return Optional.<KeyedRequest>empty();
                }
                // the row went away (released) between the insert and the select: insert again
            }
            throw new IllegalStateException("could not claim idempotency key '" + inFlight.key() + "' for " + inFlight.caller());
        });
    }

    @Override
    public void complete(String caller, String key, StoredAnswer answer) {
        jdbc.update("UPDATE idempotency_keys SET status = ?, headers = ?, body = ? WHERE caller = ? AND request_key = ?",
                answer.status(), jdbc.json(answer.headers()), answer.body(), caller, key);
    }

    @Override
    public void release(String caller, String key) {
        jdbc.update("DELETE FROM idempotency_keys WHERE caller = ? AND request_key = ? AND status IS NULL", caller, key);
    }

    /** Deletes the keys past their expiry; returns how many. */
    public int sweep(Instant now) {
        return jdbc.update("DELETE FROM idempotency_keys WHERE expires_ns < ?", Jdbc.nanos(now));
    }

    @Scheduled(fixedDelay = 10, initialDelay = 10, timeUnit = TimeUnit.MINUTES)
    void sweepOnSchedule() {
        try {
            int deleted = sweep(clock.instant());
            if (deleted > 0) {
                log.info("deleted {} expired idempotency keys", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("expired idempotency keys could not be deleted: {}", e.toString());
        }
    }

    /** For tests: how many keys are kept, expired ones included until the sweep. */
    int size() {
        return jdbc.first("SELECT COUNT(*) FROM idempotency_keys", (rs, i) -> rs.getInt(1)).orElse(0);
    }

    private KeyedRequest row(String caller, String key, ResultSet rs) throws SQLException {
        String fingerprint = rs.getString(1);
        Instant created = instant(rs.getLong(2));
        Instant expires = instant(rs.getLong(3));
        int status = rs.getInt(4);
        StoredAnswer answer = rs.wasNull() ? null : new StoredAnswer(status, headers(rs.getString(5)), rs.getString(6));
        return new KeyedRequest(caller, key, fingerprint, created, expires, answer);
    }

    private Map<String, String> headers(String json) {
        return json == null || json.isBlank() ? Map.of() : jdbc.read(json, HEADERS);
    }

    private static Instant instant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }
}
