package com.iaf.console.bff.digests.application;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.common.relay.RelayCall;
import com.iaf.console.bff.cost.ports.outbound.CostPort;
import com.iaf.console.bff.digests.domain.DigestAccount;
import com.iaf.console.bff.digests.domain.DigestFacts;
import com.iaf.console.bff.digests.domain.DigestWeeks;
import com.iaf.console.bff.digests.ports.outbound.AccessLedger;
import com.iaf.console.bff.insights.application.ExecutiveHome;
import com.iaf.console.bff.insights.ports.outbound.DriftStatsPort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * Where a digest's facts come from, each read as the console for one account and week (decision
 * 32.4): the requests from the executive home's count of the platform's jobs, the findings from
 * the drift service, the spend of the week and the week before from the cost service, and the
 * grants from the identity events the BFF recorded. A source that does not answer leaves its
 * section unavailable, with the reason; the others are still written.
 */
public class DigestSources {

    private static final Logger log = LoggerFactory.getLogger(DigestSources.class);

    /** The principal the digest's reads name: the BFF itself. */
    static final String CONSOLE = "console-bff";

    static final String NO_DRIFT_SERVICE = "the console runs without a drift service";
    static final String DRIFT_UNANSWERED = "the drift service did not answer";
    static final String JOBS_UNANSWERED = "the platform's jobs could not be read";
    static final String COST_UNANSWERED = "the cost service did not answer";
    static final String NOT_FOLLOWING = "the console does not follow the identity service's events here";
    static final String FOLLOWING_LATER = "the console began following the identity service's events after this week";
    static final String LEDGER_UNREAD = "the record of the identity service's events could not be read";

    private final ExecutiveHome home;
    private final DriftStatsPort drift;
    private final CostPort cost;
    private final AccessLedger ledger;
    private final boolean followsIdentityEvents;
    private final ObjectMapper json;

    public DigestSources(ExecutiveHome home, DriftStatsPort drift, CostPort cost, AccessLedger ledger, boolean followsIdentityEvents, ObjectMapper json) {
        this.home = home;
        this.drift = drift;
        this.cost = cost;
        this.ledger = ledger;
        this.followsIdentityEvents = followsIdentityEvents;
        this.json = json;
    }

    public Mono<DigestFacts> facts(DigestAccount account, LocalDate weekStart) {
        AccountScope scope = AccountScope.of(account.accountId(), account.region(), CONSOLE);
        Instant from = DigestWeeks.start(weekStart);
        Instant to = DigestWeeks.end(weekStart);
        return Mono.zip(changes(scope, from, to), drift(scope, from, to), cost(account.accountId(), weekStart), access(scope, from, to))
                .map(t -> new DigestFacts(account.accountId(), account.name(), weekStart, t.getT1(), t.getT2(), t.getT3(), t.getT4()));
    }

    private Mono<DigestFacts.Changes> changes(AccountScope scope, Instant from, Instant to) {
        return home.requestsIn(scope, from, to)
                .map(records -> DigestFacts.Changes.of(records.stream().filter(r -> r.startedAt().isBefore(to)).toList()))
                .onErrorResume(e -> unavailable("requests", scope, e, DigestFacts.Changes.unavailable(JOBS_UNANSWERED)));
    }

    private Mono<DigestFacts.Drift> drift(AccountScope scope, Instant from, Instant to) {
        return drift.findStats(scope, from, to).map(DigestFacts.Drift::of)
                .defaultIfEmpty(DigestFacts.Drift.unavailable(NO_DRIFT_SERVICE))
                .onErrorResume(e -> unavailable("drift", scope, e, DigestFacts.Drift.unavailable(DRIFT_UNANSWERED)));
    }

    /** {@code GET /v1/cost} for the week, by service, and for the week before, with the BFF's service token. */
    private Mono<DigestFacts.Cost> cost(String accountId, LocalDate weekStart) {
        return Mono.zip(report(accountId, weekStart, true), report(accountId, weekStart.minusWeeks(1), false))
                .map(t -> DigestFacts.Cost.of(t.getT1(), t.getT2()))
                .onErrorResume(e -> {
                    log.warn("the weekly digest of account {} has no cost: {}", accountId, e.toString());
                    return Mono.just(DigestFacts.Cost.unavailable(COST_UNANSWERED));
                });
    }

    private Mono<JsonNode> report(String accountId, LocalDate weekStart, boolean byService) {
        List<Map.Entry<String, String>> query = new ArrayList<>(List.of(Map.entry("accountId", accountId),
                Map.entry("from", weekStart.toString()), Map.entry("to", weekStart.plusWeeks(1).toString()), Map.entry("granularity", "day")));
        if (byService) {
            query.add(Map.entry("groupBy", "service"));
            query.add(Map.entry("top", String.valueOf(DigestFacts.TOP_SPEND)));
        }
        return cost.callAsService(RelayCall.get("/v1/cost", query)).flatMap(answer -> {
            if (!answer.ok()) {
                return Mono.error(new IllegalStateException("the cost service answered " + answer.status()));
            }
            try {
                return Mono.just(json.readTree(answer.body()));
            } catch (IOException e) {
                return Mono.error(e);
            }
        });
    }

    private Mono<DigestFacts.Access> access(AccountScope scope, Instant from, Instant to) {
        if (!followsIdentityEvents) {
            return Mono.just(DigestFacts.Access.unavailable(NOT_FOLLOWING));
        }
        return ledger.followingSince().map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(since -> {
            if (since.isEmpty()) {
                return Mono.just(DigestFacts.Access.unavailable(NOT_FOLLOWING));
            }
            if (!since.get().isBefore(to)) {
                return Mono.just(DigestFacts.Access.unavailable(FOLLOWING_LATER));
            }
            return ledger.list(scope, from, to).map(facts -> DigestFacts.Access.of(since.get(), facts));
        }).onErrorResume(e -> unavailable("access", scope, e, DigestFacts.Access.unavailable(LEDGER_UNREAD)));
    }

    private static <T> Mono<T> unavailable(String what, AccountScope scope, Throwable e, T fallback) {
        log.warn("the weekly digest of account {} has no {}: {}", scope.accountId(), what, e.toString());
        return Mono.just(fallback);
    }
}
