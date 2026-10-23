package com.iaf.console.identity.authz.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.iaf.console.identity.authz.domain.Check;
import com.iaf.console.identity.authz.domain.Compiled;
import com.iaf.console.identity.authz.domain.CompiledAccount;
import com.iaf.console.identity.authz.domain.Evaluator;
import com.iaf.console.identity.authz.domain.Principal;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.domain.Actions;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.domain.ManagedIam;
import com.iaf.console.identity.iam.domain.PolicyValidation;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.ports.outbound.ActionCatalogPort;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The policy simulator: "can this person, role or set of groups do this action on this wfrn, and
 * what decided?", answered by the same evaluator over the account's current data, with unsaved
 * policies and a draft boundary laid over it and nothing written. Simulating a person or a role
 * needs {@code iam:get} on it, a set of groups {@code iam:list}.
 */
public final class PolicySimulations {

    /** Probe names use a character no real name may hold, so they never shadow one. */
    static final String PROBE_USER = "simulated:principal";
    static final String PROBE_GROUP = "simulated:groups";
    static final String DRAFT_ROLE = "simulated:draft";
    static final String DRAFT_BOUNDARY = "simulated:boundary";

    private final Snapshots snapshots;
    private final RoleStore roles;
    private final GroupStore groups;
    private final ActionCatalogPort catalog;
    private final AccessGuard guard;
    private final IamSupport support;
    private final Clock clock;

    public PolicySimulations(Snapshots snapshots, RoleStore roles, GroupStore groups, ActionCatalogPort catalog, AccessGuard guard, IamSupport support,
            Clock clock) {
        this.snapshots = snapshots;
        this.roles = roles;
        this.groups = groups;
        this.catalog = catalog;
        this.guard = guard;
        this.support = support;
        this.clock = clock;
    }

    public Mono<Simulation> simulate(Caller caller, String accountId, SimulationRequest request) {
        SimulationRequest.Subject subject = request.principal();
        Names.Check shape = new Names.Check();
        int given = subject == null ? 0 : (subject.userId() != null ? 1 : 0) + (subject.role() != null ? 1 : 0) + (subject.groups() != null ? 1 : 0);
        shape.require(given == 1, "principal", "principal names exactly one of userId, role or groups");
        shape.require(request.boundary() == null || subject != null && subject.role() != null, "boundary", "a draft boundary is for a role");
        List<Check> checks = request.checks();
        shape.require(checks != null && !checks.isEmpty() && checks.size() <= AuthorizeRequest.MAX_CHECKS, "checks", "1 to 100 checks");
        if (checks != null) {
            for (int i = 0; i < checks.size(); i++) {
                Check c = checks.get(i);
                shape.require(c != null && Actions.isValid(c.action()), "checks[" + i + "].action", "action is <service>:<verb>");
                shape.require(c != null && PolicyValidation.isPattern(c.resource()), "checks[" + i + "].resource", "resource is a wfrn or a wfrn pattern");
            }
        }
        List<SimulationRequest.Draft> drafts = request.policies() == null ? List.of() : request.policies();
        shape.require(drafts.size() <= 10, "policies", "at most 10 unsaved policies");
        shape.done("the simulation");
        String action = subject.groups() != null ? "iam:list" : "iam:get";
        String resource = subject.userId() != null ? support.resource(accountId, "user", subject.userId())
                : subject.role() != null ? support.anyResource(accountId, "role") : support.anyResource(accountId, "group");
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, action, resource).thenReturn(scope))
                .flatMap(scope -> catalog.getCatalog().flatMap(actions -> {
                    for (int i = 0; i < drafts.size(); i++) {
                        SimulationRequest.Draft d = drafts.get(i);
                        PolicyValidation.check(d.name() == null ? "draft-" + i : d.name(), d.statements(), actions).done("policies[" + i + "]");
                    }
                    if (request.boundary() != null) {
                        SimulationRequest.Draft b = request.boundary();
                        PolicyValidation.check(b.name() == null ? "draft-boundary" : b.name(), b.statements(), actions).done("the boundary");
                    }
                    return Mono.zip(snapshots.get(), roles.findAll(scope).collectList(), groups.findAll(scope).collectList())
                            .map(t -> decide(scope, t.getT1(), t.getT2(), t.getT3(), request, drafts, checks));
                }));
    }

    private Simulation decide(AccountScope scope, Compiled compiled, List<Role> allRoles, List<Group> allGroups, SimulationRequest request,
            List<SimulationRequest.Draft> drafts, List<Check> checks) {
        String accountId = scope.accountId();
        CompiledAccount account = compiled.accounts().get(accountId);
        if (account == null) {
            throw NotFoundException.of("account", accountId);
        }
        SimulationRequest.Subject subject = request.principal();
        CompiledAccount world = account;
        List<String> extraGroups = new ArrayList<>();
        if (!drafts.isEmpty()) {
            Map<String, CompiledAccount.CompiledPolicy> policies = new HashMap<>();
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < drafts.size(); i++) {
                SimulationRequest.Draft d = drafts.get(i);
                String id = "simulated:policy-" + i;
                ids.add(id);
                policies.put(id, new CompiledAccount.CompiledPolicy(d.name() == null ? "draft-" + i : d.name(), Compiler.statements(d.statements())));
            }
            world = world.withRole(DRAFT_ROLE, new CompiledAccount.CompiledRole(ids), policies).withGroup(DRAFT_ROLE,
                    new CompiledAccount.CompiledGroup(List.of(DRAFT_ROLE)));
            extraGroups.add(DRAFT_ROLE);
        }
        String userId;
        CompiledAccount.CompiledUser user;
        if (subject.userId() != null) {
            CompiledAccount.CompiledUser held = account.users().get(subject.userId());
            if (held == null) {
                throw NotFoundException.of("user", subject.userId());
            }
            userId = subject.userId();
            List<String> all = new ArrayList<>(held.groups());
            all.addAll(extraGroups);
            user = new CompiledAccount.CompiledUser(held.status(), all, held.grants());
        } else if (subject.role() != null) {
            String roleName = IamSupport.one(subject.role(), allRoles, Role::roleId, Role::name, "principal.role", "role").name();
            if (request.boundary() != null) {
                if (ManagedIam.ACCOUNT_ADMIN.equals(roleName)) {
                    throw new ConflictException("the account-admin role takes no permission boundary");
                }
                SimulationRequest.Draft b = request.boundary();
                CompiledAccount.CompiledRole role = world.roles().get(roleName);
                world = world.withRole(roleName, new CompiledAccount.CompiledRole(role.policies(), DRAFT_BOUNDARY), Map.of(DRAFT_BOUNDARY,
                        new CompiledAccount.CompiledPolicy(b.name() == null ? "draft-boundary" : b.name(), Compiler.statements(b.statements()))));
            }
            world = world.withGroup(PROBE_GROUP, new CompiledAccount.CompiledGroup(List.of(roleName)));
            userId = PROBE_USER;
            List<String> all = new ArrayList<>(List.of(PROBE_GROUP));
            all.addAll(extraGroups);
            user = new CompiledAccount.CompiledUser("active", all);
        } else {
            List<String> ids = IamSupport.resolve(subject.groups(), allGroups, Group::groupId, Group::name, "principal.groups", "group");
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (String id : ids) {
                allGroups.stream().filter(g -> g.groupId().equals(id)).findFirst().ifPresent(g -> names.add(g.name()));
            }
            userId = PROBE_USER;
            List<String> all = new ArrayList<>(names);
            all.addAll(extraGroups);
            user = new CompiledAccount.CompiledUser("active", all);
        }
        Compiled probe = compiled.only(accountId, world.withUser(userId, user));
        Principal principal = new Principal(userId, accountId, null, null);
        Instant now = clock.instant();
        List<Simulation.Result> results = new ArrayList<>(checks.size());
        for (Check check : checks) {
            results.add(new Simulation.Result(check.action(), check.resource(), Evaluator.decide(probe, principal, check, now)));
        }
        return new Simulation(List.copyOf(results), account.version());
    }
}
