package com.iaf.console.bff.digests.adapters.outbound;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.digests.domain.Digest;
import com.iaf.console.bff.digests.domain.DigestSummary;
import com.iaf.console.bff.digests.ports.outbound.DigestStore;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** Digests of one instance, in memory: the claims hold between the callers of this one instance. */
@Component
@Profile("!mongo")
public class InMemoryDigestStore implements DigestStore {

    private record Key(String accountId, LocalDate weekStart) {
    }

    /** A week claimed (owner and lease) or written (the digest). */
    private record Slot(String owner, Instant leaseUntil, Digest digest) {
    }

    private final Map<Key, Slot> slots = new HashMap<>();

    @Override
    public Mono<Claim> claim(String accountId, LocalDate weekStart, String owner, Instant now, Duration lease) {
        return Mono.fromSupplier(() -> {
            synchronized (slots) {
                Key key = new Key(accountId, weekStart);
                Slot slot = slots.get(key);
                if (slot != null && slot.digest() != null) {
                    return Claim.WRITTEN;
                }
                if (slot != null && !slot.owner().equals(owner) && slot.leaseUntil().isAfter(now)) {
                    return Claim.HELD;
                }
                slots.put(key, new Slot(owner, now.plus(lease), null));
                return Claim.CLAIMED;
            }
        });
    }

    @Override
    public Mono<Boolean> save(Digest digest, String owner) {
        return Mono.fromSupplier(() -> {
            synchronized (slots) {
                Key key = new Key(digest.accountId(), digest.weekStart());
                Slot slot = slots.get(key);
                if (slot != null && slot.digest() != null) {
                    return false;
                }
                slots.put(key, new Slot(owner, null, digest));
                return true;
            }
        });
    }

    @Override
    public Mono<Void> release(String accountId, LocalDate weekStart, String owner) {
        return Mono.fromRunnable(() -> {
            synchronized (slots) {
                slots.computeIfPresent(new Key(accountId, weekStart), (k, slot) -> slot.digest() == null && slot.owner().equals(owner) ? null : slot);
            }
        });
    }

    @Override
    public Mono<Digest> find(AccountScope scope, LocalDate weekStart) {
        return Mono.fromSupplier(() -> {
            synchronized (slots) {
                Slot slot = slots.get(new Key(scope.accountId(), weekStart));
                return slot == null ? null : slot.digest();
            }
        });
    }

    @Override
    public Mono<List<DigestSummary>> list(AccountScope scope, int limit, LocalDate before) {
        return Mono.fromSupplier(() -> {
            synchronized (slots) {
                return slots.entrySet().stream()
                        .filter(e -> e.getKey().accountId().equals(scope.accountId()) && e.getValue().digest() != null)
                        .filter(e -> before == null || e.getKey().weekStart().isBefore(before))
                        .map(e -> e.getValue().digest().summary())
                        .sorted(Comparator.comparing(DigestSummary::weekStart).reversed())
                        .limit(limit)
                        .toList();
            }
        });
    }
}
