package com.iaf.console.bff.drift.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.drift.domain.DriftCommands;
import com.iaf.console.bff.drift.domain.DriftQueries;
import com.iaf.console.bff.drift.domain.DriftResults;
import com.iaf.console.bff.drift.domain.Recommendation;
import com.iaf.console.bff.drift.domain.RecommendationsSummary;
import com.iaf.console.bff.drift.ports.outbound.DriftPort;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * Rightsizing (WP 9.22) through the drift service: idle and oversized resources (a recommendation
 * on a type the person may not list is left out), their summary, one recommendation, and the two
 * decisions. Applying runs the type's workflow for the person, so it is guarded like the verb it
 * runs: a resize needs {@code <service>:update}; a delete needs {@code <service>:delete}, the
 * fresh sign-in the step-up policy asks for, and {@code confirm: true}, as a destructive verb does.
 * Dismissing needs {@code <service>:update}.
 */
public class Rightsizing {

    private final DriftPort drift;
    private final DriftPolicies policies;
    private final Trust trust;
    private final Clock clock;

    public Rightsizing(DriftPort drift, DriftPolicies policies, Trust trust, Clock clock) {
        this.drift = drift;
        this.policies = policies;
        this.trust = trust;
        this.clock = clock;
    }

    /** One page of recommendations, open ones by default, the largest saving first. */
    public Mono<Page<Recommendation>> list(Session session, String type, String kind, String status, String wfrn, Integer limit, String after) {
        return Mono.defer(() -> {
            String named = DriftInputs.blankToNull(wfrn);
            if (named != null && DriftPolicies.parse(named) == null) {
                throw DriftInputs.invalid("format", "wfrn", "wfrn is a resource name, wfrn:<partition>:<service>:<region>:<account>:<type>/<id>");
            }
            DriftQueries.Recommendations query = new DriftQueries.Recommendations(DriftInputs.blankToNull(type),
                    DriftInputs.oneOf("kind", kind, DriftInputs.KINDS), DriftInputs.oneOf("status", status, DriftInputs.RECOMMENDATION_STATUSES), named,
                    CursorPaging.pageSize(limit), DriftInputs.blankToNull(after));
            Mono<?> typed = query.type() == null ? Mono.empty() : policies.listableType(session, query.type());
            return typed.then(Mono.defer(() -> drift.listRecommendations(DriftPolicies.scope(session), DriftPolicies.caller(session), query)))
                    .flatMap(page -> decided(session, page.items(), true).map(items -> new Page<>(items, page.next())));
        });
    }

    /** The open recommendations counted by kind and what they would save; any member may read it. */
    public Mono<RecommendationsSummary> summary(Session session) {
        return drift.getRecommendationsSummary(DriftPolicies.scope(session), DriftPolicies.caller(session));
    }

    /** One recommendation, as {@code <service>:get} on its wfrn allows. */
    public Mono<Recommendation> get(Session session, String recommendationId) {
        return Mono.defer(() -> read(session, DriftInputs.id("recommendationId", recommendationId)))
                .flatMap(found -> policies.require(session, found.wfrn().service(), DriftPolicies.GET, found.recommendation().wfrn())
                        .then(Mono.defer(() -> decided(session, List.of(found.recommendation()), false))).map(items -> items.get(0)));
    }

    /**
     * Resizes or deletes the resource as recommended, on behalf of the person; 202 with the job.
     * The order is the destructive verb's: the policy, then the fresh sign-in, then the confirmation.
     */
    public Mono<DriftResults.ApplyStarted> apply(Session session, String recommendationId, String reason, Boolean confirm, String requestId,
            String idempotencyKey) {
        return Mono.defer(() -> {
            String id = DriftInputs.id("recommendationId", recommendationId);
            String why = DriftInputs.text("reason", reason, DriftInputs.MAX_REASON);
            return read(session, id).flatMap(found -> {
                Recommendation r = found.recommendation();
                return policies.requireFresh(session, found.wfrn().service(), r.applyVerb(), r.wfrn())
                        .then(Mono.fromRunnable(() -> {
                            if (r.deletes() && !Boolean.TRUE.equals(confirm)) {
                                throw new InvalidInputException("confirm delete: it is destructive",
                                        List.of(Problem.error("confirm.required", "/confirm", "set confirm to true to run delete")));
                            }
                        }))
                        .then(Mono.defer(() -> drift.applyRecommendation(DriftPolicies.scope(session), DriftPolicies.caller(session), id,
                                new DriftCommands.Decision(why, session.user().userId(), null),
                                new DriftCommands.Meta(idempotencyKey, requestId, OnBehalfOf.assertion(trust, session)))));
            });
        });
    }

    /** Sets a recommendation aside, for good or until {@code until}. */
    public Mono<Recommendation> dismiss(Session session, String recommendationId, String reason, String until, String requestId,
            String idempotencyKey) {
        return Mono.defer(() -> {
            String id = DriftInputs.id("recommendationId", recommendationId);
            String why = DriftInputs.text("reason", reason, DriftInputs.MAX_REASON);
            Instant end = DriftInputs.instant("until", until);
            if (end != null && !end.isAfter(clock.instant())) {
                throw DriftInputs.invalid("range", "until", "until is in the future");
            }
            return read(session, id).flatMap(found -> policies.require(session, found.wfrn().service(), DriftPolicies.UPDATE,
                    found.recommendation().wfrn())
                    .then(Mono.defer(() -> drift.dismissRecommendation(DriftPolicies.scope(session), DriftPolicies.caller(session), id,
                            new DriftCommands.Decision(why, session.user().userId(), end), new DriftCommands.Meta(idempotencyKey, requestId, null))))
                    .flatMap(after -> decided(session, List.of(after), false).map(items -> items.isEmpty() ? after.withCan(List.of()) : items.get(0))));
        });
    }

    // ---- pieces

    /** A recommendation the drift service answered, with its wfrn checked to be the session's. */
    private record Found(Recommendation recommendation, Wfrn wfrn) {
    }

    private Mono<Found> read(Session session, String recommendationId) {
        return drift.getRecommendation(DriftPolicies.scope(session), DriftPolicies.caller(session), recommendationId)
                .map(r -> new Found(r, DriftPolicies.ownedBy(session, "recommendation", recommendationId, r.wfrn())));
    }

    /**
     * The recommendations the person may see, each with what they may do: one outside the
     * session's account and region is left out, and in a list ({@code listing}) one on a type they
     * may not list; {@code apply} follows the verb applying needs (update for a resize, delete for
     * a delete), {@code dismiss} the update. One batch of questions for them all.
     */
    private Mono<List<Recommendation>> decided(Session session, List<Recommendation> rows, boolean listing) {
        List<AccessCheck> checks = new ArrayList<>();
        List<Wfrn> names = new ArrayList<>(rows.size());
        for (Recommendation row : rows) {
            Wfrn w = DriftPolicies.inScope(session, row.wfrn());
            names.add(w);
            if (w != null) {
                if (listing) {
                    checks.add(policies.list(session, w.service(), w.type()));
                }
                checks.add(DriftPolicies.onResource(w, row.applyVerb(), row.wfrn()));
                checks.add(DriftPolicies.onResource(w, DriftPolicies.UPDATE, row.wfrn()));
            }
        }
        return policies.ask(session, checks).map(answers -> {
            List<Recommendation> kept = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                Wfrn w = names.get(i);
                Recommendation row = rows.get(i);
                if (w != null && (!listing || answers.allowed(policies.list(session, w.service(), w.type())))) {
                    kept.add(row.decidable(answers.allowed(DriftPolicies.onResource(w, row.applyVerb(), row.wfrn())),
                            answers.allowed(DriftPolicies.onResource(w, DriftPolicies.UPDATE, row.wfrn()))));
                }
            }
            return kept;
        });
    }
}
