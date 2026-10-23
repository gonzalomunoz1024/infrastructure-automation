package com.iaf.console.bff.drift.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.drift.domain.DiscoveryRun;
import com.iaf.console.bff.drift.domain.DriftCommands;
import com.iaf.console.bff.drift.domain.DriftQueries;
import com.iaf.console.bff.drift.domain.DriftResults;
import com.iaf.console.bff.drift.domain.UnmanagedObject;
import com.iaf.console.bff.drift.ports.outbound.DriftPort;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * The shadow-infrastructure finder (WP 5.3) through the drift service: discovery runs over a
 * type's provider ({@code <service>:observe} on the type's pattern), the objects no account
 * manages (an object of a type the person may not list is left out) and the two things to do with
 * one, import it or hand it to another team, each {@code <service>:create} on the type's pattern.
 */
public class Unmanaged {

    private final DriftPort drift;
    private final DriftPolicies policies;

    public Unmanaged(DriftPort drift, DriftPolicies policies) {
        this.drift = drift;
        this.policies = policies;
    }

    /** Starts a scan of the type's provider in the session's region; one run per type and region at a time. */
    public Mono<DiscoveryRun> discover(Session session, String type, String requestId, String idempotencyKey) {
        return Mono.defer(() -> {
            String named = DriftInputs.required("type", type);
            return policies.type(session, named).flatMap(t -> policies.require(session, t.service(), DriftPolicies.OBSERVE,
                    policies.pattern(session, t.service(), t.type()))
                    .then(Mono.defer(() -> drift.startDiscovery(DriftPolicies.scope(session), DriftPolicies.caller(session), t.type(),
                            new DriftCommands.Meta(idempotencyKey, requestId, null)))));
        });
    }

    /** A run's progress; a run of another region of the account is not this session's to see. */
    public Mono<DiscoveryRun> run(Session session, String runId) {
        return Mono.defer(() -> {
            String id = DriftInputs.id("runId", runId);
            return drift.getDiscovery(DriftPolicies.scope(session), DriftPolicies.caller(session), id).map(run -> {
                if (run.region() != null && !run.region().equals(session.region())) {
                    throw NotFoundException.of("discovery run", id);
                }
                return run;
            });
        });
    }

    /** One page of the objects this account's runs found, and the ones another account handed to it. */
    public Mono<Page<UnmanagedObject>> list(Session session, String type, String owner, Integer limit, String after) {
        return Mono.defer(() -> {
            DriftQueries.Unmanaged query = new DriftQueries.Unmanaged(DriftInputs.blankToNull(type), DriftInputs.oneOf("owner", owner, DriftInputs.OWNERS),
                    CursorPaging.pageSize(limit), DriftInputs.blankToNull(after));
            Mono<?> named = query.type() == null ? Mono.empty() : policies.listableType(session, query.type());
            return named.then(Mono.defer(() -> drift.listUnmanaged(DriftPolicies.scope(session), DriftPolicies.caller(session), query)))
                    .flatMap(page -> decided(session, page.items()).map(items -> new Page<>(items, page.next())));
        });
    }

    /** Takes an object under management: it gets a wfrn and leaves both accounts' lists. */
    public Mono<DriftResults.Imported> importObject(Session session, String type, String providerId, String name, String requestId,
            String idempotencyKey) {
        return Mono.defer(() -> {
            String named = DriftInputs.required("type", type);
            String id = DriftInputs.text("providerId", DriftInputs.required("providerId", providerId), 1024);
            String called = DriftInputs.text("name", name, 128);
            return creatable(session, named).flatMap(t -> drift.importUnmanaged(DriftPolicies.scope(session), DriftPolicies.caller(session),
                    new DriftCommands.Import(t.type(), id, called), new DriftCommands.Meta(idempotencyKey, requestId, null)));
        });
    }

    /** Hands an object to another team's account; it shows in both lists until either imports it. */
    public Mono<UnmanagedObject> assign(Session session, String type, String providerId, String accountId, String note, String requestId,
            String idempotencyKey) {
        return Mono.defer(() -> {
            String named = DriftInputs.required("type", type);
            String id = DriftInputs.text("providerId", DriftInputs.required("providerId", providerId), 1024);
            String account = DriftInputs.required("accountId", accountId);
            if (!DriftInputs.ACCOUNT_ID.matcher(account).matches()) {
                throw DriftInputs.invalid("format", "accountId", "accountId is a console account id, 12 digits");
            }
            String why = DriftInputs.text("note", note, DriftInputs.MAX_NOTE);
            return creatable(session, named).flatMap(t -> drift.assignUnmanaged(DriftPolicies.scope(session), DriftPolicies.caller(session),
                    new DriftCommands.Assign(t.type(), id, account, why, session.user().userId()), new DriftCommands.Meta(idempotencyKey, requestId, null))
                    .map(object -> object.withCan(UnmanagedObject.ACTIONS)));
        });
    }

    // ---- pieces

    /** The type of an import or an assignment, once {@code <service>:create} on its pattern allows it. */
    private Mono<ConsoleType> creatable(Session session, String type) {
        return policies.type(session, type).flatMap(t -> policies.require(session, t.service(), DriftPolicies.CREATE, policies.pattern(session,
                t.service(), t.type())).thenReturn(t));
    }

    /**
     * The objects the person may see, each with what they may do: an object of a type the region
     * does not know, of a type the person may not list, or of another region is left out;
     * {@code can} follows {@code <service>:create} on the type's pattern. One batch for them all.
     */
    private Mono<List<UnmanagedObject>> decided(Session session, List<UnmanagedObject> rows) {
        if (rows.isEmpty()) {
            return Mono.just(List.of());
        }
        return policies.typesByName(session).flatMap(byName -> {
            List<AccessCheck> checks = new ArrayList<>();
            for (UnmanagedObject row : rows) {
                ConsoleType t = typeOf(session, row, byName);
                if (t != null) {
                    checks.add(policies.onType(session, t, DriftPolicies.LIST));
                    checks.add(policies.onType(session, t, DriftPolicies.CREATE));
                }
            }
            return policies.ask(session, checks).map(answers -> {
                List<UnmanagedObject> kept = new ArrayList<>(rows.size());
                for (UnmanagedObject row : rows) {
                    ConsoleType t = typeOf(session, row, byName);
                    if (t != null && answers.allowed(policies.onType(session, t, DriftPolicies.LIST))) {
                        kept.add(row.withCan(answers.allowed(policies.onType(session, t, DriftPolicies.CREATE)) ? UnmanagedObject.ACTIONS : List.of()));
                    }
                }
                return kept;
            });
        });
    }

    private static ConsoleType typeOf(Session session, UnmanagedObject row, Map<String, ConsoleType> byName) {
        if (row.type() == null || (row.region() != null && !row.region().equals(session.region()))) {
            return null;
        }
        return byName.get(row.type());
    }
}
