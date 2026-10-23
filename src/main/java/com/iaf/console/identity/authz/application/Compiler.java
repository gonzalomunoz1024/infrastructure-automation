package com.iaf.console.identity.authz.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.authz.domain.Compiled;
import com.iaf.console.identity.authz.domain.CompiledAccount;
import com.iaf.console.identity.authz.ports.outbound.AuthzVersionPort;
import com.iaf.console.identity.elevations.domain.Elevation;
import com.iaf.console.identity.elevations.ports.outbound.ElevationStore;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.domain.Guardrail;
import com.iaf.console.identity.iam.domain.Policy;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.domain.Statement;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.GuardrailStore;
import com.iaf.console.identity.iam.ports.outbound.PolicyStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.domain.User;
import com.iaf.console.identity.principals.domain.UserStatus;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.identity.principals.ports.outbound.UserStore;

import reactor.core.publisher.Mono;

/**
 * Compiles every account's users, groups, roles and policies, and the organization's guardrails,
 * into the data the evaluator and the OPA bundle read. Ids become names where the policy reasons
 * by name (groups, roles); policies and guardrails keep their ids, so a decision can name what
 * decided. Every statement gets a sid. Active elevations become a person's grants, each until its
 * end (the evaluator drops it then): an elevation counts for a member of the account only, so
 * removing someone takes it with their membership, while break-glass access needs no membership.
 */
public final class Compiler {

    private final AccountStore accounts;
    private final UserStore users;
    private final MembershipStore memberships;
    private final GroupStore groups;
    private final RoleStore roles;
    private final PolicyStore policies;
    private final GuardrailStore guardrails;
    private final ElevationStore elevations;
    private final AuthzVersionPort versions;

    public Compiler(AccountStore accounts, UserStore users, MembershipStore memberships, GroupStore groups, RoleStore roles, PolicyStore policies,
            GuardrailStore guardrails, ElevationStore elevations, AuthzVersionPort versions) {
        this.accounts = accounts;
        this.users = users;
        this.memberships = memberships;
        this.groups = groups;
        this.roles = roles;
        this.policies = policies;
        this.guardrails = guardrails;
        this.elevations = elevations;
        this.versions = versions;
    }

    /**
     * The revision and the account versions are read before the data they label: a change landing
     * meanwhile leaves the labels behind the data, so the next check sees the revision move and
     * compiles again. Read after, they would mark data from before the change as current.
     */
    public Mono<Compiled> compile() {
        return versions.getRevision().flatMap(revision -> accounts.findAll()
                .concatMap(account -> versions.getVersion(account.accountId()).map(version -> Map.entry(account.accountId(), version)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .flatMap(versionOf -> Mono.zip(accounts.findAll().collectList(), users.findAll().collectMap(User::userId),
                                memberships.findEverything().collectList(), groups.findEverything().collectList(), roles.findEverything().collectList(),
                                policies.findEverything().collectList(), guardrails.findAll().collectList(), elevations.findActive().collectList())
                        .map(t -> {
                            List<Guardrail> rails = t.getT7();
                            Map<String, List<Membership>> membershipsByAccount = groupBy(t.getT3(), Membership::accountId);
                            Map<String, List<Group>> groupsByAccount = groupBy(t.getT4(), Group::accountId);
                            Map<String, List<Role>> rolesByAccount = groupBy(t.getT5(), Role::accountId);
                            Map<String, List<Policy>> policiesByAccount = groupBy(t.getT6(), Policy::accountId);
                            Map<String, List<Elevation>> elevationsByAccount = groupBy(t.getT8(), Elevation::accountId);
                            Map<String, Compiled.CompiledGuardrail> compiledRails = new TreeMap<>();
                            for (Guardrail rail : rails) {
                                compiledRails.put(rail.guardrailId(), new Compiled.CompiledGuardrail(rail.name(), statements(rail.statements())));
                            }
                            Map<String, CompiledAccount> compiled = new TreeMap<>();
                            for (Account account : t.getT1()) {
                                String id = account.accountId();
                                // an account created after its version was read starts at 0, behind its data like the rest
                                compiled.put(id, compile(account, versionOf.getOrDefault(id, 0L), t.getT2(), membershipsByAccount.getOrDefault(id, List.of()),
                                        groupsByAccount.getOrDefault(id, List.of()), rolesByAccount.getOrDefault(id, List.of()),
                                        policiesByAccount.getOrDefault(id, List.of()),
                                        rails.stream().filter(r -> r.appliesTo(id)).map(Guardrail::guardrailId).sorted().toList(),
                                        elevationsByAccount.getOrDefault(id, List.of())));
                            }
                            return new Compiled(compiled, compiledRails, revision);
                        })));
    }

    public static CompiledAccount compile(Account account, long version, Map<String, User> allUsers, List<Membership> memberships, List<Group> groups,
            List<Role> roles, List<Policy> policies) {
        return compile(account, version, allUsers, memberships, groups, roles, policies, List.of());
    }

    public static CompiledAccount compile(Account account, long version, Map<String, User> allUsers, List<Membership> memberships, List<Group> groups,
            List<Role> roles, List<Policy> policies, List<String> guardrailIds) {
        return compile(account, version, allUsers, memberships, groups, roles, policies, guardrailIds, List.of());
    }

    public static CompiledAccount compile(Account account, long version, Map<String, User> allUsers, List<Membership> memberships, List<Group> groups,
            List<Role> roles, List<Policy> policies, List<String> guardrailIds, List<Elevation> activeElevations) {
        Map<String, String> groupNames = new HashMap<>();
        Map<String, CompiledAccount.CompiledGroup> compiledGroups = new TreeMap<>();
        Map<String, String> roleNames = new HashMap<>();
        for (Role role : roles) {
            roleNames.put(role.roleId(), role.name());
        }
        for (Group group : groups) {
            groupNames.put(group.groupId(), group.name());
            List<String> names = new ArrayList<>();
            for (String roleId : group.roles()) {
                String name = roleNames.get(roleId);
                if (name != null) {
                    names.add(name);
                }
            }
            compiledGroups.put(group.name(), new CompiledAccount.CompiledGroup(List.copyOf(names)));
        }
        Map<String, CompiledAccount.CompiledRole> compiledRoles = new TreeMap<>();
        for (Role role : roles) {
            compiledRoles.put(role.name(), new CompiledAccount.CompiledRole(role.policies(), role.boundary()));
        }
        Map<String, CompiledAccount.CompiledPolicy> compiledPolicies = new TreeMap<>();
        for (Policy policy : policies) {
            compiledPolicies.put(policy.policyId(), new CompiledAccount.CompiledPolicy(policy.name(), statements(policy.statements())));
        }
        Map<String, CompiledAccount.CompiledUser> compiledUsers = new TreeMap<>();
        for (Membership m : memberships) {
            User user = allUsers.get(m.userId());
            UserStatus status = user != null && user.status() == UserStatus.suspended ? UserStatus.suspended : m.status();
            List<String> names = new ArrayList<>();
            for (String groupId : m.groupIds()) {
                String name = groupNames.get(groupId);
                if (name != null) {
                    names.add(name);
                }
            }
            compiledUsers.put(m.userId(), new CompiledAccount.CompiledUser(status.name(), List.copyOf(names), grants(activeElevations, m.userId(), true)));
        }
        // break-glass access opens the account to a person who is no member of it: no groups, only the grant
        for (Elevation e : activeElevations) {
            String userId = e.user().userId();
            if (e.kind() == Elevation.Kind.BREAK_GLASS && !compiledUsers.containsKey(userId)) {
                User user = allUsers.get(userId);
                UserStatus status = user == null || user.status() == UserStatus.suspended ? UserStatus.suspended : UserStatus.active;
                compiledUsers.put(userId, new CompiledAccount.CompiledUser(status.name(), List.of(), grants(activeElevations, userId, false)));
            }
        }
        return new CompiledAccount(account.status().name(), compiledUsers, compiledGroups, compiledRoles, compiledPolicies, guardrailIds, version);
    }

    /** A person's active elevations as grants: break-glass ones always, the others only for a member. */
    private static List<CompiledAccount.CompiledGrant> grants(List<Elevation> activeElevations, String userId, boolean member) {
        List<CompiledAccount.CompiledGrant> grants = new ArrayList<>();
        for (Elevation e : activeElevations) {
            if (e.user().userId().equals(userId) && e.status() == Elevation.Status.active && e.until() != null
                    && (member || e.kind() == Elevation.Kind.BREAK_GLASS)) {
                grants.add(new CompiledAccount.CompiledGrant(e.role(), e.until().getEpochSecond(), e.kind().wire()));
            }
        }
        return grants;
    }

    /** A document's statements as the data holds them: every sid set ({@code s<index>} when the document has none). */
    public static List<CompiledAccount.CompiledStatement> statements(List<Statement> statements) {
        List<CompiledAccount.CompiledStatement> compiled = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
            String sid = s.sid() == null || s.sid().isBlank() ? "s" + i : s.sid();
            compiled.add(new CompiledAccount.CompiledStatement(sid, s.effect().name(), s.actions(), s.notActions(), s.resources(), s.conditions()));
        }
        return List.copyOf(compiled);
    }

    private static <T> Map<String, List<T>> groupBy(List<T> items, java.util.function.Function<T, String> key) {
        Map<String, List<T>> by = new LinkedHashMap<>();
        for (T item : items) {
            by.computeIfAbsent(key.apply(item), k -> new ArrayList<>()).add(item);
        }
        return by;
    }
}
