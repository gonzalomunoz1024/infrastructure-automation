package com.iaf.console.bff.drift.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.drift.domain.DriftCommands;
import com.iaf.console.bff.drift.domain.DriftQueries;
import com.iaf.console.bff.drift.domain.DriftResults;
import com.iaf.console.bff.drift.domain.Finding;
import com.iaf.console.bff.drift.domain.FindingStats;
import com.iaf.console.bff.drift.domain.FindingsSummary;
import com.iaf.console.bff.drift.ports.outbound.DriftPort;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * Drift findings (WP 5.2) through the drift service, for the session's account and region: the
 * account's list (a finding of a type the person may not list is left out), its counts and the
 * executive numbers, one finding, a resource's Drift tab, and the three decisions. Each decision
 * needs {@code <service>:update} on the finding's wfrn, so the finding is read first; a revert
 * runs the type's update workflow for the person, so it also carries their {@code X-On-Behalf-Of}
 * assertion and is guarded like the update verb (the step-up policy has its say).
 */
public class Findings {

    /** The most findings a resource's Drift tab shows: one page of the drift service's largest. */
    static final int RESOURCE_PAGE = CursorPaging.MAX_PAGE_SIZE;

    private final DriftPort drift;
    private final DriftPolicies policies;
    private final Trust trust;
    private final Clock clock;

    public Findings(DriftPort drift, DriftPolicies policies, Trust trust, Clock clock) {
        this.drift = drift;
        this.policies = policies;
        this.trust = trust;
        this.clock = clock;
    }

    /** One page of the account's findings, open ones by default; a named type the person may not list is refused, as its own list is. */
    public Mono<Page<Finding>> list(Session session, String status, String severity, String type, String wfrn, String openedAfter, Integer limit,
            String after) {
        return Mono.defer(() -> {
            DriftQueries.Findings query = new DriftQueries.Findings(DriftInputs.oneOf("status", status, DriftInputs.FINDING_STATUSES),
                    DriftInputs.oneOf("severity", severity, DriftInputs.SEVERITIES), DriftInputs.blankToNull(type), DriftInputs.blankToNull(wfrn),
                    DriftInputs.instant("openedAfter", openedAfter), CursorPaging.pageSize(limit), DriftInputs.blankToNull(after));
            Mono<?> named = query.type() == null ? Mono.empty() : policies.listableType(session, query.type());
            return named.then(Mono.defer(() -> drift.listFindings(DriftPolicies.scope(session), DriftPolicies.caller(session), query)))
                    .flatMap(page -> decided(session, page.items(), true).map(items -> new Page<>(items, page.next())));
        });
    }

    /** The open findings counted by type and severity; any member of the account may read them. */
    public Mono<FindingsSummary> summary(Session session) {
        return drift.getFindingsSummary(DriftPolicies.scope(session), DriftPolicies.caller(session));
    }

    /** Findings opened and closed in a window (the last 30 days, until now, by default); any member may read them. */
    public Mono<FindingStats> stats(Session session, String since, String until) {
        return Mono.defer(() -> {
            Instant from = DriftInputs.instant("since", since);
            Instant to = DriftInputs.instant("until", until);
            DriftInputs.window("since", from, "until", to, clock.instant());
            return drift.getFindingStats(DriftPolicies.scope(session), DriftPolicies.caller(session), from, to);
        });
    }

    /** One finding, as {@code <service>:get} on its wfrn allows. */
    public Mono<Finding> get(Session session, String findingId) {
        return Mono.defer(() -> read(session, DriftInputs.id("findingId", findingId))).flatMap(found -> policies
                .require(session, found.wfrn().service(), DriftPolicies.GET, found.finding().wfrn())
                .then(Mono.defer(() -> decided(session, List.of(found.finding()), false))).map(items -> items.get(0)));
    }

    /**
     * A resource's findings, open and closed, for its Drift tab: {@code <service>:get} on the wfrn,
     * which must name the path's type in the session's account and region.
     */
    public Mono<List<Finding>> ofResource(Session session, String type, String wfrn) {
        return policies.resource(session, type, wfrn).flatMap(t -> policies.require(session, t.service(), DriftPolicies.GET, wfrn)
                .then(Mono.defer(() -> drift.listFindings(DriftPolicies.scope(session), DriftPolicies.caller(session),
                        new DriftQueries.Findings("all", null, null, wfrn, null, RESOURCE_PAGE, null))))
                .flatMap(page -> decided(session, page.items().stream().filter(f -> wfrn.equals(f.wfrn())).toList(), false)));
    }

    /** Runs the type's update workflow with the desired values, on behalf of the person; 202 with the job. */
    public Mono<DriftResults.RevertStarted> revert(Session session, String findingId, String reason, String requestId, String idempotencyKey) {
        return Mono.defer(() -> {
            String id = DriftInputs.id("findingId", findingId);
            String why = DriftInputs.text("reason", reason, DriftInputs.MAX_REASON);
            return read(session, id).flatMap(found -> policies.requireFresh(session, found.wfrn().service(), DriftPolicies.UPDATE, found.finding().wfrn())
                    .then(Mono.defer(() -> drift.revertFinding(DriftPolicies.scope(session), DriftPolicies.caller(session), id,
                            new DriftCommands.Decision(why, session.user().userId(), null),
                            new DriftCommands.Meta(idempotencyKey, requestId, OnBehalfOf.assertion(trust, session))))));
        });
    }

    /** Makes the observed values the desired ones; the finding closes as accepted. */
    public Mono<Finding> accept(Session session, String findingId, String reason, String requestId, String idempotencyKey) {
        return Mono.defer(() -> {
            String id = DriftInputs.id("findingId", findingId);
            String why = DriftInputs.text("reason", reason, DriftInputs.MAX_REASON);
            return decide(session, id, f -> drift.acceptFinding(DriftPolicies.scope(session), DriftPolicies.caller(session), id,
                    new DriftCommands.Decision(why, session.user().userId(), null), new DriftCommands.Meta(idempotencyKey, requestId, null)));
        });
    }

    /** Silences the drifted fields until the desired state changes, or until {@code until}; the finding closes as ignored. */
    public Mono<Finding> ignore(Session session, String findingId, String reason, String until, String requestId, String idempotencyKey) {
        return Mono.defer(() -> {
            String id = DriftInputs.id("findingId", findingId);
            String why = DriftInputs.text("reason", reason, DriftInputs.MAX_REASON);
            Instant end = DriftInputs.instant("until", until);
            if (end != null && !end.isAfter(clock.instant())) {
                throw DriftInputs.invalid("range", "until", "until is in the future");
            }
            return decide(session, id, f -> drift.ignoreFinding(DriftPolicies.scope(session), DriftPolicies.caller(session), id,
                    new DriftCommands.Decision(why, session.user().userId(), end), new DriftCommands.Meta(idempotencyKey, requestId, null)));
        });
    }

    // ---- pieces

    /** A finding the drift service answered, with its wfrn checked to be the session's. */
    private record Found(Finding finding, Wfrn wfrn) {
    }

    private Mono<Found> read(Session session, String findingId) {
        return drift.getFinding(DriftPolicies.scope(session), DriftPolicies.caller(session), findingId)
                .map(f -> new Found(f, DriftPolicies.ownedBy(session, "finding", findingId, f.wfrn())));
    }

    /** Accept and ignore: {@code <service>:update} on the finding's wfrn, then the decision; the answer says what the person may still do. */
    private Mono<Finding> decide(Session session, String findingId, Function<Finding, Mono<Finding>> decision) {
        return read(session, findingId).flatMap(found -> policies.require(session, found.wfrn().service(), DriftPolicies.UPDATE, found.finding().wfrn())
                .then(Mono.defer(() -> decision.apply(found.finding())))
                .map(after -> after.decidable(true)));
    }

    /**
     * The findings the person may see, each with what they may do: a finding outside the session's
     * account and region is left out, and in a list ({@code listing}) one of a type they may not
     * list; {@code can} follows {@code <service>:update} on its wfrn. One batch of questions for them all.
     */
    private Mono<List<Finding>> decided(Session session, List<Finding> rows, boolean listing) {
        List<AccessCheck> checks = new ArrayList<>();
        List<Wfrn> names = new ArrayList<>(rows.size());
        for (Finding row : rows) {
            Wfrn w = DriftPolicies.inScope(session, row.wfrn());
            names.add(w);
            if (w != null) {
                if (listing) {
                    checks.add(policies.list(session, w.service(), w.type()));
                }
                checks.add(DriftPolicies.onResource(w, DriftPolicies.UPDATE, row.wfrn()));
            }
        }
        return policies.ask(session, checks).map(answers -> {
            List<Finding> kept = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                Wfrn w = names.get(i);
                if (w != null && (!listing || answers.allowed(policies.list(session, w.service(), w.type())))) {
                    kept.add(rows.get(i).decidable(answers.allowed(DriftPolicies.onResource(w, DriftPolicies.UPDATE, rows.get(i).wfrn()))));
                }
            }
            return kept;
        });
    }
}
