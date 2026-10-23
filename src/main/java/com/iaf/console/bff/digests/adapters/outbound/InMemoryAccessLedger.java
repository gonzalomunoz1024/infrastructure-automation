package com.iaf.console.bff.digests.adapters.outbound;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.digests.domain.AccessFact;
import com.iaf.console.bff.digests.domain.SubjectState;
import com.iaf.console.bff.digests.ports.outbound.AccessLedger;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** The identity events' record of one instance, in memory, keeping each event's facts for five weeks. */
@Component
@Profile("!mongo")
public class InMemoryAccessLedger implements AccessLedger {

    static final Duration RETENTION = Duration.ofDays(35);

    private record Recorded(String accountId, Instant at, List<AccessFact> facts) {
    }

    private final Map<String, Recorded> events = new LinkedHashMap<>();
    private final Map<String, SubjectState> states = new HashMap<>();
    private Instant since;

    @Override
    public synchronized Mono<SubjectState> findState(AccountScope scope, String aggregate, String aggregateId) {
        return Mono.justOrEmpty(states.get(stateKey(scope.accountId(), aggregate, aggregateId)));
    }

    @Override
    public Mono<Void> record(String accountId, String eventKey, Instant at, List<AccessFact> facts, String aggregate, String aggregateId,
            SubjectState after) {
        return Mono.fromRunnable(() -> {
            synchronized (this) {
                if (!facts.isEmpty() && !events.containsKey(eventKey)) {
                    events.put(eventKey, new Recorded(accountId, at, List.copyOf(facts)));
                    Instant oldest = at.minus(RETENTION);
                    events.values().removeIf(r -> r.at().isBefore(oldest));
                }
                if (aggregate != null) {
                    String key = stateKey(accountId, aggregate, aggregateId);
                    SubjectState held = states.get(key);
                    if (after == null) {
                        states.remove(key);
                    } else if (held == null || after.version() > held.version()) {
                        states.put(key, after);
                    }
                }
            }
        });
    }

    @Override
    public synchronized Mono<List<AccessFact>> list(AccountScope scope, Instant from, Instant to) {
        List<AccessFact> out = new ArrayList<>();
        for (Recorded r : events.values()) {
            if (r.accountId().equals(scope.accountId()) && !r.at().isBefore(from) && r.at().isBefore(to)) {
                out.addAll(r.facts());
            }
        }
        return Mono.just(List.copyOf(out));
    }

    @Override
    public synchronized Mono<Instant> follow(Instant now) {
        if (since == null) {
            since = now;
        }
        return Mono.just(since);
    }

    @Override
    public synchronized Mono<Instant> followingSince() {
        return Mono.justOrEmpty(since);
    }

    private static String stateKey(String accountId, String aggregate, String aggregateId) {
        return accountId + ":" + aggregate + ":" + aggregateId;
    }
}
