package com.iaf.console.bff.digests.ports.outbound;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.iaf.console.bff.digests.domain.Digest;
import com.iaf.console.bff.digests.domain.DigestSummary;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * Where the digests live, one per account and week (a unique key), and how replicas agree on who
 * writes one: a claim takes the week for a lease, a save writes it once, a release gives up a
 * claim that wrote nothing.
 */
public interface DigestStore {

    /** What a claim found: the week taken for this owner, written already, or held by another owner whose lease has not run out. */
    enum Claim {
        CLAIMED, WRITTEN, HELD
    }

    /** Takes an account's week for writing, for {@code lease} from {@code now}, unless it is written or another owner holds it. */
    Mono<Claim> claim(String accountId, LocalDate weekStart, String owner, Instant now, Duration lease);

    /** Writes a digest unless its week is written already; true when this call wrote it. */
    Mono<Boolean> save(Digest digest, String owner);

    /** Gives up a claim that wrote nothing, so another replica or the next run may write the week. */
    Mono<Void> release(String accountId, LocalDate weekStart, String owner);

    /** The scope's account's digest of a week; empty when it is not written. */
    Mono<Digest> find(AccountScope scope, LocalDate weekStart);

    /** The scope's account's written digests, newest first, at most {@code limit}, only weeks before {@code before} when it is given. */
    Mono<List<DigestSummary>> list(AccountScope scope, int limit, LocalDate before);
}
