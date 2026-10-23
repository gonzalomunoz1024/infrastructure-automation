package com.iaf.console.bff.digests.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.digests.domain.Digest;
import com.iaf.console.bff.digests.domain.DigestAccount;
import com.iaf.console.bff.digests.domain.DigestComposer;
import com.iaf.console.bff.digests.domain.DigestFacts;
import com.iaf.console.bff.digests.domain.DigestSummary;
import com.iaf.console.bff.digests.domain.DigestTemplate;
import com.iaf.console.bff.digests.domain.DigestText;
import com.iaf.console.bff.digests.domain.DigestWeeks;
import com.iaf.console.bff.digests.ports.outbound.DigestAccounts;
import com.iaf.console.bff.digests.ports.outbound.DigestStore;
import com.iaf.console.bff.digests.ports.outbound.DigestWriter;
import com.iaf.console.bff.notifications.application.Notifications;
import com.iaf.console.bff.notifications.domain.Audience;
import com.iaf.console.bff.notifications.domain.Draft;
import com.iaf.console.bff.notifications.domain.Links;
import com.iaf.console.bff.notifications.domain.Notification;
import com.iaf.console.bff.notifications.domain.NotificationKinds;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * The weekly digests (decision 32.4): writing an account's week once across replicas (a claim on
 * the account and week, then a save that only the first writer makes), from the sources' facts
 * and the writer's text, the rules template making up for a writer that fails; then
 * {@code digest.ready} to the account's members. Reading them takes {@code digests:get} on
 * {@code wfrn:<partition>:digests::<account>:*}, which every managed role has.
 */
public class Digests {

    private static final Logger log = LoggerFactory.getLogger(Digests.class);

    public static final String ACTION = "digests:get";
    public static final String WRITTEN = "written";
    /** Written before, by this run's replica or another. */
    public static final String EXISTS = "exists";
    /** Another replica holds the week and is writing it. */
    public static final String BUSY = "busy";
    public static final String FAILED = "failed";
    /** How long a writer may take before the template writes the digest instead. */
    static final Duration WRITER_TIMEOUT = Duration.ofMinutes(1);

    /** What one account's run came to: written, exists (written before), busy (another replica holds the week) or failed, and why. */
    public record Outcome(String accountId, LocalDate weekStart, String result, String reason) {

        /** Whether the account's week needs no more runs: it is written, now or before. */
        public boolean done() {
            return WRITTEN.equals(result) || EXISTS.equals(result);
        }
    }

    private final DigestStore store;
    private final DigestAccounts accounts;
    private final DigestSources sources;
    private final DigestWriter writer;
    private final Notifications notifications;
    private final Access access;
    private final String partition;
    private final Duration lease;
    private final Clock clock;
    private final String owner = DigestSources.CONSOLE + ":" + UUID.randomUUID();

    public Digests(DigestStore store, DigestAccounts accounts, DigestSources sources, DigestWriter writer, Notifications notifications, Access access,
            String partition, Duration lease, Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.sources = sources;
        this.writer = writer;
        this.notifications = notifications;
        this.access = access;
        this.partition = partition;
        this.lease = lease;
        this.clock = clock;
    }

    // ---- writing

    /** Writes every active account's digest of a week (its Monday) that is not written yet, one account after another. */
    public Mono<List<Outcome>> generate(LocalDate weekStart) {
        return Mono.defer(() -> {
            check(weekStart);
            return accounts.listAccounts().concatMap(account -> generate(account, weekStart)).collectList();
        });
    }

    /** Writes one account's digest of a week unless it is written; a 404 for an account that is not active. */
    public Mono<Outcome> generate(String accountId, LocalDate weekStart) {
        return Mono.defer(() -> {
            check(weekStart);
            return accounts.listAccounts().filter(a -> a.accountId().equals(accountId)).next()
                    .switchIfEmpty(Mono.error(() -> new NotFoundException("no active account " + accountId)))
                    .flatMap(account -> generate(account, weekStart));
        });
    }

    private Mono<Outcome> generate(DigestAccount account, LocalDate weekStart) {
        String accountId = account.accountId();
        return store.claim(accountId, weekStart, owner, clock.instant(), lease).flatMap(claim -> {
            if (claim == DigestStore.Claim.WRITTEN) {
                return Mono.just(new Outcome(accountId, weekStart, EXISTS, null));
            }
            if (claim == DigestStore.Claim.HELD) {
                return Mono.just(new Outcome(accountId, weekStart, BUSY, "another replica is writing it"));
            }
            return sources.facts(account, weekStart)
                    .flatMap(this::written)
                    .flatMap(digest -> store.save(digest, owner).flatMap(saved -> saved
                            ? notifyReady(account, digest).thenReturn(new Outcome(accountId, weekStart, WRITTEN, null))
                            : Mono.just(new Outcome(accountId, weekStart, EXISTS, "another replica wrote it first"))))
                    .onErrorResume(e -> {
                        log.warn("the weekly digest of account {} for the week of {} failed: {}", accountId, weekStart, e.toString());
                        return store.release(accountId, weekStart, owner).onErrorResume(r -> Mono.empty())
                                .thenReturn(new Outcome(accountId, weekStart, FAILED, e.toString()));
                    });
        });
    }

    /** The digest the writer makes of the facts, or the template's when the writer fails, is slow or answers nothing. */
    private Mono<Digest> written(DigestFacts facts) {
        Mono<Digest> rules = Mono.fromSupplier(() -> DigestComposer.compose(facts, DigestTemplate.write(facts), Digest.RULES, clock.instant()));
        if (Digest.RULES.equals(writer.source())) {
            return rules;
        }
        return writer.write(facts).timeout(WRITER_TIMEOUT)
                .map((DigestText text) -> DigestComposer.compose(facts, text, writer.source(), clock.instant()))
                .switchIfEmpty(rules)
                .onErrorResume(e -> {
                    log.warn("the {} writer did not write the digest of account {} for the week of {}: the template does: {}", writer.source(),
                            facts.accountId(), facts.weekStart(), e.toString());
                    return rules;
                });
    }

    /** {@code digest.ready} to every member of the account who has it on, once per account and week. */
    private Mono<Void> notifyReady(DigestAccount account, Digest digest) {
        String name = account.name() == null || account.name().isBlank() ? account.accountId() : account.name();
        Draft draft = new Draft("digest.ready:" + account.accountId() + ":" + digest.weekStart(), NotificationKinds.DIGEST_READY, Notification.INFO,
                "The weekly digest of " + name + " is ready", digest.headline(), account.accountId(), null, null, null,
                Links.digest(digest.weekStart()), digest.generatedAt(), List.of(Audience.members()));
        return notifications.accept(draft).onErrorResume(e -> {
            log.warn("notifying the digest of account {} for the week of {} failed: {}", account.accountId(), digest.weekStart(), e.toString());
            return Mono.empty();
        });
    }

    private void check(LocalDate weekStart) {
        DigestWeeks.requireMonday(weekStart);
        if (DigestWeeks.start(weekStart).isAfter(clock.instant())) {
            throw new IllegalArgumentException("the week of " + weekStart + " has not begun");
        }
    }

    // ---- reading

    /** {@code GET /console/v1/digests}: the session account's digests, newest first. */
    public Mono<Page<DigestSummary>> list(Session session, Integer limit, String after) {
        return Mono.defer(() -> {
            int size = CursorPaging.pageSize(limit);
            LocalDate before = after == null || after.isBlank() ? null : weekOf(CursorPaging.decode(after));
            return require(session).then(Mono.defer(() -> store.list(CurrentSession.scope(session), size + 1, before)))
                    .map(found -> Page.from(found, size, d -> new Cursor(d.weekStart().toEpochDay(), d.weekStart().toString())));
        });
    }

    /** {@code GET /console/v1/digests/{weekStart}}: one digest of the session's account; 400 for a day that is not a Monday, 404 when none is written. */
    public Mono<Digest> get(Session session, String weekStart) {
        return Mono.defer(() -> {
            LocalDate week = DigestWeeks.parse(weekStart);
            return require(session).then(Mono.defer(() -> store.find(CurrentSession.scope(session), week)))
                    .switchIfEmpty(Mono.error(() -> new NotFoundException("no digest of the week of " + week + " in account " + session.accountId())));
        });
    }

    /** What reading the account's digests is decided on: {@code wfrn:<partition>:digests::<account>:*}. */
    public String resourceOf(String accountId) {
        return "wfrn:" + partition + ":digests::" + accountId + ":*";
    }

    private Mono<Void> require(Session session) {
        return access.require(session, ACTION, resourceOf(session.accountId()));
    }

    private static LocalDate weekOf(Cursor cursor) {
        try {
            return LocalDate.parse(cursor.id());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the cursor is not one this service issued");
        }
    }
}
