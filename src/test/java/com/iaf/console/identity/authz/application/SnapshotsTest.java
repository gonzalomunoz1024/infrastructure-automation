package com.iaf.console.identity.authz.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.iaf.console.identity.accounts.adapters.memory.InMemoryAccountStore;
import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.authz.adapters.memory.InMemoryAuthzVersions;
import com.iaf.console.identity.authz.domain.Compiled;
import com.iaf.console.identity.elevations.adapters.memory.InMemoryElevationStore;
import com.iaf.console.identity.events.IdentityChange;
import com.iaf.console.identity.iam.adapters.memory.InMemoryGroupStore;
import com.iaf.console.identity.iam.adapters.memory.InMemoryGuardrailStore;
import com.iaf.console.identity.iam.adapters.memory.InMemoryPolicyStore;
import com.iaf.console.identity.iam.adapters.memory.InMemoryRoleStore;
import com.iaf.console.identity.iam.domain.Guardrail;
import com.iaf.console.identity.iam.ports.outbound.GuardrailStore;
import com.iaf.console.identity.principals.adapters.memory.InMemoryMembershipStore;
import com.iaf.console.identity.principals.adapters.memory.InMemoryUserStore;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The compiled data keeps up with changes that land while a compilation runs: one made in this
 * process is compiled by the next caller, one from another process at the next revision check.
 * The guardrails are the compiler's last read, so holding them holds a compilation that has
 * read everything else.
 */
class SnapshotsTest {

    private static final Duration TTL = Duration.ofSeconds(2);
    private static final String FIRST = "000000000001";
    private static final String SECOND = "000000000002";

    private final MovingClock clock = new MovingClock();
    private final InMemoryAccountStore accounts = new InMemoryAccountStore();
    private final InMemoryAuthzVersions versions = new InMemoryAuthzVersions();
    private final HeldGuardrails guardrails = new HeldGuardrails();
    private final Snapshots snapshots = new Snapshots(new Compiler(accounts, new InMemoryUserStore(), new InMemoryMembershipStore(), new InMemoryGroupStore(),
            new InMemoryRoleStore(), new InMemoryPolicyStore(), guardrails, new InMemoryElevationStore(), versions), versions, clock, TTL);

    @Test
    void aChangeInThisProcessWhileACompilationRunsIsCompiledNext() {
        add(FIRST);
        guardrails.holdNext();
        CompletableFuture<Compiled> running = snapshots.get().toFuture();

        add(SECOND);
        snapshots.changed(change(SECOND));
        guardrails.release();

        assertThat(join(running).accounts()).as("read before the change").containsOnlyKeys(FIRST);
        assertThat(snapshots.get().block(Duration.ofSeconds(5)).accounts()).as("within the ttl too").containsOnlyKeys(FIRST, SECOND);
    }

    @Test
    void aCallerAfterAChangeGetsACompilationThatBeganAfterIt() {
        add(FIRST);
        guardrails.holdNext();
        CompletableFuture<Compiled> running = snapshots.get().toFuture();

        add(SECOND);
        snapshots.changed(change(SECOND));
        CompletableFuture<Compiled> after = snapshots.get().toFuture();
        assertThat(after).as("one compilation at a time: it waits for the one running").isNotDone();

        guardrails.release();
        assertThat(join(running).accounts()).containsOnlyKeys(FIRST);
        assertThat(join(after).accounts()).as("not the one that read the stores before the change").containsOnlyKeys(FIRST, SECOND);
        assertThat(snapshots.held()).isSameAs(join(after));
        assertThat(guardrails.reads()).isEqualTo(2);
    }

    @Test
    void callersWaitingOnAnOlderCompilationShareTheNextOne() {
        add(FIRST);
        guardrails.holdNext();
        CompletableFuture<Compiled> running = snapshots.get().toFuture();

        add(SECOND);
        snapshots.changed(change(SECOND));
        CompletableFuture<Compiled> one = snapshots.get().toFuture();
        snapshots.changed(change(SECOND));
        CompletableFuture<Compiled> two = snapshots.get().toFuture();
        guardrails.release();

        assertThat(join(running).accounts()).containsOnlyKeys(FIRST);
        assertThat(join(one)).isSameAs(join(two));
        assertThat(join(two).accounts()).containsOnlyKeys(FIRST, SECOND);
        assertThat(guardrails.reads()).as("the waiting callers compile once between them").isEqualTo(2);
    }

    @Test
    void aFailedCompilationFailsItsCallersAndTheNextCallerCompilesAgain() {
        add(FIRST);
        guardrails.failNext();
        assertThatThrownBy(() -> snapshots.get().block(Duration.ofSeconds(5))).hasMessageContaining("guardrails unavailable");
        assertThat(snapshots.get().block(Duration.ofSeconds(5)).accounts()).containsOnlyKeys(FIRST);
        assertThat(guardrails.reads()).isEqualTo(2);
    }

    @Test
    void aCallerWaitingOnACompilationThatFailsGetsTheNextOne() {
        add(FIRST);
        guardrails.holdNext();
        guardrails.failHeld();
        CompletableFuture<Compiled> running = snapshots.get().toFuture();
        snapshots.changed(change(FIRST));
        CompletableFuture<Compiled> after = snapshots.get().toFuture();
        guardrails.release();

        assertThat(running).failsWithin(Duration.ofSeconds(5));
        assertThat(join(after).accounts()).containsOnlyKeys(FIRST);
    }

    @Test
    void callersWithNoChangeBetweenThemShareOneCompilation() {
        add(FIRST);
        guardrails.holdNext();
        CompletableFuture<Compiled> first = snapshots.get().toFuture();
        CompletableFuture<Compiled> second = snapshots.get().toFuture();
        guardrails.release();

        assertThat(join(first)).isSameAs(join(second));
        assertThat(guardrails.reads()).isEqualTo(1);
        assertThat(snapshots.get().block(Duration.ofSeconds(5))).as("held until the data changes").isSameAs(join(first));
        assertThat(guardrails.reads()).isEqualTo(1);
    }

    @Test
    void aChangeFromAnotherProcessWhileACompilationRunsIsCompiledAtTheNextCheck() {
        add(FIRST);
        guardrails.holdNext();
        CompletableFuture<Compiled> running = snapshots.get().toFuture();

        // another instance writes and moves the revision; this process hears nothing of it
        add(SECOND);
        guardrails.release();

        assertThat(join(running).accounts()).containsOnlyKeys(FIRST);
        assertThat(snapshots.get().block(Duration.ofSeconds(5)).accounts()).as("the held data answers within the ttl").containsOnlyKeys(FIRST);
        clock.advance(TTL);
        assertThat(snapshots.get().block(Duration.ofSeconds(5)).accounts()).as("the revision it was labelled with is behind the store")
                .containsOnlyKeys(FIRST, SECOND);
    }

    @Test
    void anAccountsVersionIsNeverAheadOfItsData() {
        add(FIRST);
        guardrails.holdNext();
        CompletableFuture<Compiled> running = snapshots.get().toFuture();
        versions.bump(FIRST).block();
        guardrails.release();

        assertThat(join(running).accounts().get(FIRST).version()).as("read before the bump").isEqualTo(1);
        clock.advance(TTL);
        assertThat(snapshots.get().block(Duration.ofSeconds(5)).accounts().get(FIRST).version()).isEqualTo(2);
    }

    private void add(String accountId) {
        accounts.insert(Account.create(accountId, "Account " + accountId, "team-" + accountId, "global", null, clock.instant())).block();
        versions.bump(accountId).block();
    }

    private static IdentityChange change(String accountId) {
        return new IdentityChange("account", "Account", accountId, accountId, 1, "created");
    }

    private static Compiled join(CompletableFuture<Compiled> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("the compilation did not finish", e);
        }
    }

    /** The in-memory guardrails, whose next read can be held until released, or fail; counts the reads. */
    private static final class HeldGuardrails implements GuardrailStore {

        private final InMemoryGuardrailStore store = new InMemoryGuardrailStore();
        private final AtomicReference<Sinks.Empty<Void>> next = new AtomicReference<>();
        private final AtomicReference<Sinks.Empty<Void>> held = new AtomicReference<>();
        private final AtomicBoolean failNext = new AtomicBoolean();
        private final AtomicBoolean failHeld = new AtomicBoolean();
        private final AtomicInteger reads = new AtomicInteger();

        void holdNext() {
            Sinks.Empty<Void> gate = Sinks.empty();
            next.set(gate);
            held.set(gate);
        }

        void failNext() {
            failNext.set(true);
        }

        /** The held read fails when released. */
        void failHeld() {
            failHeld.set(true);
        }

        void release() {
            if (failHeld.get()) {
                held.get().tryEmitError(new IllegalStateException("guardrails unavailable"));
            } else {
                held.get().tryEmitEmpty();
            }
        }

        int reads() {
            return reads.get();
        }

        @Override
        public Mono<Guardrail> insert(Guardrail guardrail) {
            return store.insert(guardrail);
        }

        @Override
        public Mono<Guardrail> update(Guardrail guardrail, long expectedVersion) {
            return store.update(guardrail, expectedVersion);
        }

        @Override
        public Mono<Boolean> delete(String guardrailId) {
            return store.delete(guardrailId);
        }

        @Override
        public Mono<Guardrail> findById(String guardrailId) {
            return store.findById(guardrailId);
        }

        @Override
        public Flux<Guardrail> findAll() {
            return Flux.defer(() -> {
                reads.incrementAndGet();
                if (failNext.getAndSet(false)) {
                    return Flux.error(new IllegalStateException("guardrails unavailable"));
                }
                Sinks.Empty<Void> gate = next.getAndSet(null);
                return gate == null ? store.findAll() : gate.asMono().thenMany(Flux.defer(store::findAll));
            });
        }
    }

    private static final class MovingClock extends Clock {

        private volatile Instant now = Instant.parse("2026-10-23T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
