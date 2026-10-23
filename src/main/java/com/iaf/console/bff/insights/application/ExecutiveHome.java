package com.iaf.console.bff.insights.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.insights.domain.AccountProfile;
import com.iaf.console.bff.insights.domain.Counted;
import com.iaf.console.bff.insights.domain.DriftNumbers;
import com.iaf.console.bff.insights.domain.Insights;
import com.iaf.console.bff.insights.domain.InsightsScope;
import com.iaf.console.bff.insights.domain.Period;
import com.iaf.console.bff.insights.domain.RequestNumbers;
import com.iaf.console.bff.insights.domain.RequestRecord;
import com.iaf.console.bff.insights.ports.outbound.AccountProfilesPort;
import com.iaf.console.bff.insights.ports.outbound.DriftStatsPort;
import com.iaf.console.bff.jobs.application.JobRetries;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.tags.application.TagPolicies;
import com.iaf.console.bff.tags.domain.RequiredTag;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.JobFilter;
import com.iaf.console.platform.client.PagedList;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.EvidenceRecord;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.platform.model.JobSummary;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The executive home's numbers (WP 9.5, hub decision 27) for the accounts a person may read
 * insights in ({@code insights:get} on each account, every region), or for the session's account:
 * <ul>
 * <li>resources managed now and at the start of the period, from the search index's counts across
 * the accounts as the console-internal principal (live now, less those created since the start,
 * plus the tombstones of those created before it and deleted since);</li>
 * <li>requests, self-served requests, failures, the lead time and adoption by week, from each
 * account's top-level platform jobs a person started through the console in the period; a
 * succeeded request is self-served unless its evidence holds a person's decision on a failed step
 * (retried, skipped or given a new input by hand) or it is the console's own retry of an earlier
 * failed request ({@link JobRetries#RETRY_OF_LABEL});</li>
 * <li>hours saved against the ticket baseline in configuration;</li>
 * <li>drift found, fixed and open from the drift service.</li>
 * </ul>
 * One answer per set of accounts and period is kept for {@code console.insights.cache-ttl}, so a
 * room of people opening the home at once costs one computation.
 */
public class ExecutiveHome {

    public static final String SERVICE = "insights";
    public static final String ACTION = SERVICE + ":get";
    static final int JOB_PAGE = 200;
    /** The required tag that holds an account's cost centre when the identity service's account record has none. */
    static final String COST_CENTRE_TAG = "cost-centre";
    static final String DECISION = "decision";
    static final String SYSTEM = "system";
    private static final DateTimeFormatter TERM_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final Logger log = LoggerFactory.getLogger(ExecutiveHome.class);

    private final Access access;
    private final PlatformClients clients;
    private final PlatformProperties platform;
    private final InventoryCounts counts;
    private final DriftStatsPort drift;
    private final AccountProfilesPort profiles;
    private final TagPolicies tags;
    private final InsightsProperties properties;
    private final Clock clock;
    private final Cache<String, Mono<Insights>> answers;

    public ExecutiveHome(Access access, PlatformClients clients, PlatformProperties platform, InventoryCounts counts, DriftStatsPort drift,
            AccountProfilesPort profiles, TagPolicies tags, InsightsProperties properties, Clock clock) {
        this.access = access;
        this.clients = clients;
        this.platform = platform;
        this.counts = counts;
        this.drift = drift;
        this.profiles = profiles;
        this.tags = tags;
        this.properties = properties;
        this.clock = clock;
        this.answers = Caffeine.newBuilder().expireAfterWrite(properties.cacheTtl()).maximumSize(10_000).build();
    }

    /** The numbers of a period; 403 with the refusal when the person may read insights in none of the accounts asked for. */
    public Mono<Insights> insights(Session session, Period period, InsightsScope scope) {
        return covered(session, scope).flatMap(members -> {
            String key = scope.id() + "|" + period.id() + "|" + members.stream().map(AccountMembership::accountId).collect(Collectors.joining(","));
            // An answer missing an account's drift numbers is served once, not kept: the next call asks again.
            return answers.get(key, k -> compute(session, period, scope, members).doOnNext(answer -> {
                if (answer.drift().partial() != null) {
                    answers.invalidate(k);
                }
            }).doOnError(e -> answers.invalidate(k)).cache());
        });
    }

    /** What {@code insights:get} is decided on: everything of the service in the account, in every region. */
    public String resourceOf(String accountId) {
        return AccessCheck.servicePattern(platform.partition(), SERVICE, "*", accountId);
    }

    /** The accounts covered, in the session's order: those of the scope the person may read insights in. */
    Mono<List<AccountMembership>> covered(Session session, InsightsScope scope) {
        List<AccountMembership> candidates = scope == InsightsScope.ACCOUNT ? List.of(session.currentAccount())
                : session.accounts().stream().filter(m -> !"closed".equals(m.status())).toList();
        return Flux.fromIterable(candidates)
                .flatMapSequential(m -> access.decide(session.switchedTo(m.accountId(), regionOf(session, m)),
                        List.of(new AccessCheck(ACTION, resourceOf(m.accountId())))).map(decided -> Map.entry(m, decided.get(0))), properties.concurrency())
                .collectList()
                .flatMap(decided -> {
                    List<AccountMembership> allowed = decided.stream().filter(e -> e.getValue().allowed()).map(Map.Entry::getKey).toList();
                    if (!allowed.isEmpty()) {
                        return Mono.just(allowed);
                    }
                    Decision refusal = decided.stream().filter(e -> e.getKey().accountId().equals(session.accountId())).map(Map.Entry::getValue)
                            .findFirst().orElse(decided.get(0).getValue());
                    return Mono.error(new DeniedException(refusal.denial()));
                });
    }

    private Mono<Insights> compute(Session session, Period period, InsightsScope scope, List<AccountMembership> members) {
        // Not truncated: a request started earlier in this second is in the window.
        Instant to = clock.instant();
        Instant from = to.minus(period.length()).truncatedTo(ChronoUnit.MINUTES);
        List<String> ids = members.stream().map(AccountMembership::accountId).toList();
        Mono<List<Insights.Account>> accounts = Flux.fromIterable(members).flatMapSequential(m -> account(session, m), properties.concurrency())
                .collectList();
        Mono<Insights.Managed> managed = managed(session, ids, from);
        Mono<List<RequestRecord>> requests = Flux.fromIterable(members).flatMap(m -> requestsOf(session, m, from, to), Math.min(4, properties.concurrency()))
                .collectList().map(lists -> lists.stream().flatMap(List::stream).toList());
        Mono<DriftNumbers> driftNumbers = Flux.fromIterable(members)
                .flatMap(m -> drift.findStats(scopeOf(session, m), from, to).defaultIfEmpty(DriftNumbers.NONE).onErrorResume(e -> {
                    log.warn("the drift service did not answer for account {}: the executive home's drift numbers leave it out: {}", m.accountId(),
                            e.toString());
                    return Mono.just(DriftNumbers.UNANSWERED);
                }), properties.concurrency())
                .reduce(DriftNumbers.NONE, DriftNumbers::plus);
        return Mono.zip(accounts, managed, requests, driftNumbers).map(t -> {
            RequestNumbers.Result r = RequestNumbers.of(t.getT3(), from, to, properties.tickets());
            return new Insights(period.id(), from, to, to, scope.id(), t.getT1(), t.getT2(), r.requests(), r.leadTime(), r.hoursSaved(),
                    t.getT4().view(), r.adoption());
        });
    }

    // ---- the accounts

    /** The account as finance and its owners know it: the identity service's record, the cost centre else from the tag guardrails. */
    private Mono<Insights.Account> account(Session session, AccountMembership m) {
        return profiles.findProfile(scopeOf(session, m)).defaultIfEmpty(AccountProfile.UNKNOWN)
                .onErrorResume(e -> {
                    log.warn("the identity service's record of account {} is unavailable for the executive home: {}", m.accountId(), e.toString());
                    return Mono.just(AccountProfile.UNKNOWN);
                })
                .map(profile -> {
                    String costCentre = profile.costCentre() != null ? profile.costCentre() : configuredCostCentre(m.accountId(), profile.owningTeam());
                    return new Insights.Account(m.accountId(), profile.name() != null ? profile.name() : m.name(), profile.owningTeam(), costCentre);
                });
    }

    private String configuredCostCentre(String accountId, String owningTeam) {
        for (RequiredTag tag : tags.policy(accountId, owningTeam).required()) {
            if (COST_CENTRE_TAG.equals(tag.key()) && RequiredTag.FROM_ACCOUNT.equals(tag.defaultFrom())) {
                return tag.defaultValue();
            }
        }
        return null;
    }

    // ---- resources managed

    private Mono<Insights.Managed> managed(Session session, List<String> ids, Instant from) {
        String at = TERM_TIME.format(from);
        return Mono.zip(
                counts.countAcross(session, ids, Map.of(), "service"),
                counts.countAcross(session, ids, Map.of("q", "created:>=" + at), null),
                counts.countAcross(session, ids, Map.of("state", "deleted", "q", "created:<" + at + " updated:>=" + at), null))
                .map(t -> {
                    long live = t.getT1().total();
                    List<Insights.ServiceCount> services = t.getT1().groups().stream().filter(g -> g.total() > 0)
                            .sorted(Comparator.comparingLong(Counted.Group::total).reversed().thenComparing(Counted.Group::key))
                            .map(g -> new Insights.ServiceCount(g.key(), titleOf(g.key()), g.total())).toList();
                    return new Insights.Managed(live, Math.max(0, live - t.getT2().total() + t.getT3().total()), services);
                });
    }

    /** A service's name for people: {@code object-storage} is Object storage. */
    static String titleOf(String service) {
        if (service == null || service.isBlank()) {
            return service;
        }
        String words = service.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    // ---- requests

    /** The account's console requests started in the window, oldest change first, at most {@code max-jobs-per-account}. */
    private Mono<List<RequestRecord>> requestsOf(Session session, AccountMembership m, Instant from, Instant to) {
        long fromNanos = from.getEpochSecond() * 1_000_000_000L + from.getNano();
        JobFilter first = JobFilter.ofAccount(m.accountId()).withUpdatedAfter(fromNanos).withLimit(JOB_PAGE);
        int max = properties.maxJobsPerAccount();
        return clients.forAccount(scopeOf(session, m)).flatMap(p -> p.listJobs(first)
                .expand(page -> page.hasNext() ? p.listJobs(first.withAfter(page.next())) : Mono.empty())
                .concatMapIterable(PagedList::items)
                .filter(job -> isRequest(job, m.accountId(), from, to))
                .take(max + 1L)
                .collectList()
                .flatMap(jobs -> {
                    if (jobs.size() > max) {
                        log.warn("account {} started more than {} requests since {}: the executive home counts the first {}", m.accountId(), max, from, max);
                        jobs = jobs.subList(0, max);
                    }
                    return Flux.fromIterable(jobs).flatMapSequential(job -> record(p, m.accountId(), job), properties.concurrency()).collectList();
                }));
    }

    /** A top-level job of the account a person started through the console in the window. */
    static boolean isRequest(JobSummary job, String accountId, Instant from, Instant to) {
        return job.parentId() == null && job.context() != null && accountId.equals(job.context().accountId()) && job.onBehalfOf() != null
                && job.onBehalfOf().id() != null && job.startedAt() != null && !job.startedAt().isBefore(from) && !job.startedAt().isAfter(to);
    }

    private Mono<RequestRecord> record(PlatformPort p, String accountId, JobSummary job) {
        RequestRecord.Outcome outcome = outcomeOf(job.status());
        if (outcome != RequestRecord.Outcome.SUCCEEDED || isConsoleRetry(job)) {
            return Mono.just(recordOf(accountId, job, outcome, false));
        }
        return p.getEvidenceRecord(job.id())
                .map(evidence -> recordOf(accountId, job, outcome, !steppedIn(evidence)))
                .onErrorResume(PlatformException.class, e -> e.isNotFound() || e.isForbidden()
                        ? Mono.just(recordOf(accountId, job, outcome, false)) : Mono.error(e));
    }

    private static RequestRecord recordOf(String accountId, JobSummary job, RequestRecord.Outcome outcome, boolean selfServed) {
        return new RequestRecord(accountId, job.id(), job.workflow(), job.onBehalfOf().id(), outcome, job.startedAt(), job.durationMs(), selfServed);
    }

    static RequestRecord.Outcome outcomeOf(JobStatus status) {
        if (status == JobStatus.SUCCEEDED) {
            return RequestRecord.Outcome.SUCCEEDED;
        }
        if (status == JobStatus.FAILED || status == JobStatus.ROLLED_BACK) {
            return RequestRecord.Outcome.FAILED;
        }
        return RequestRecord.Outcome.OTHER;
    }

    static boolean isConsoleRetry(JobSummary job) {
        return job.context() != null && job.context().labels() != null && job.context().labels().containsKey(JobRetries.RETRY_OF_LABEL);
    }

    /** A person decided on a failed step: retried it, skipped it or gave it a new input. */
    static boolean steppedIn(EvidenceRecord evidence) {
        return evidence.entries() != null && evidence.entries().stream()
                .anyMatch(entry -> DECISION.equals(entry.kind()) && entry.actor() != null && !SYSTEM.equals(entry.actor()));
    }

    // ---- scopes

    private static String regionOf(Session session, AccountMembership m) {
        return m.defaultRegion() != null && !m.defaultRegion().isBlank() ? m.defaultRegion() : session.region();
    }

    private static AccountScope scopeOf(Session session, AccountMembership m) {
        return AccountScope.of(m.accountId(), regionOf(session, m), session.user().userId());
    }
}
