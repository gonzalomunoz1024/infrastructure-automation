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
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.domain.Policy;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.domain.Statement;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.PolicyStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.domain.User;
import com.iaf.console.identity.principals.domain.UserStatus;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.identity.principals.ports.outbound.UserStore;

import reactor.core.publisher.Mono;

/**
 * Compiles every account's users, groups, roles and policies into the data the evaluator and the
 * OPA bundle read. Ids become names where the policy reasons by name (groups, roles); policies
 * keep their ids, so a decision can name the policy that decided. Every statement gets a sid.
 */
public final class Compiler {

    private final AccountStore accounts;
    private final UserStore users;
    private final MembershipStore memberships;
    private final GroupStore groups;
    private final RoleStore roles;
    private final PolicyStore policies;
    private final AuthzVersionPort versions;

    public Compiler(AccountStore accounts, UserStore users, MembershipStore memberships, GroupStore groups, RoleStore roles, PolicyStore policies,
            AuthzVersionPort versions) {
        this.accounts = accounts;
        this.users = users;
        this.memberships = memberships;
        this.groups = groups;
        this.roles = roles;
        this.policies = policies;
        this.versions = versions;
    }

    public Mono<Compiled> compile() {
        return Mono.zip(accounts.findAll().collectList(), users.findAll().collectMap(User::userId), memberships.findEverything().collectList(),
                        groups.findEverything().collectList(), roles.findEverything().collectList(), policies.findEverything().collectList())
                .flatMap(t -> versions.getRevision().flatMap(revision -> {
                    Map<String, List<Membership>> membershipsByAccount = groupBy(t.getT3(), Membership::accountId);
                    Map<String, List<Group>> groupsByAccount = groupBy(t.getT4(), Group::accountId);
                    Map<String, List<Role>> rolesByAccount = groupBy(t.getT5(), Role::accountId);
                    Map<String, List<Policy>> policiesByAccount = groupBy(t.getT6(), Policy::accountId);
                    List<Account> all = t.getT1();
                    return reactor.core.publisher.Flux.fromIterable(all)
                            .concatMap(account -> versions.getVersion(account.accountId()).map(version -> Map.entry(account.accountId(),
                                    compile(account, version, t.getT2(), membershipsByAccount.getOrDefault(account.accountId(), List.of()),
                                            groupsByAccount.getOrDefault(account.accountId(), List.of()), rolesByAccount.getOrDefault(account.accountId(), List.of()),
                                            policiesByAccount.getOrDefault(account.accountId(), List.of())))))
                            .collectList()
                            .map(entries -> {
                                Map<String, CompiledAccount> compiled = new TreeMap<>();
                                entries.forEach(e -> compiled.put(e.getKey(), e.getValue()));
                                return new Compiled(compiled, revision);
                            });
                }));
    }

    public static CompiledAccount compile(Account account, long version, Map<String, User> allUsers, List<Membership> memberships, List<Group> groups,
            List<Role> roles, List<Policy> policies) {
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
            compiledRoles.put(role.name(), new CompiledAccount.CompiledRole(role.policies()));
        }
        Map<String, CompiledAccount.CompiledPolicy> compiledPolicies = new TreeMap<>();
        for (Policy policy : policies) {
            List<CompiledAccount.CompiledStatement> statements = new ArrayList<>();
            for (int i = 0; i < policy.statements().size(); i++) {
                Statement s = policy.statements().get(i);
                String sid = s.sid() == null || s.sid().isBlank() ? "s" + i : s.sid();
                statements.add(new CompiledAccount.CompiledStatement(sid, s.effect().name(), s.actions(), s.notActions(), s.resources(), s.conditions()));
            }
            compiledPolicies.put(policy.policyId(), new CompiledAccount.CompiledPolicy(policy.name(), List.copyOf(statements)));
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
            compiledUsers.put(m.userId(), new CompiledAccount.CompiledUser(status.name(), List.copyOf(names)));
        }
        return new CompiledAccount(account.status().name(), compiledUsers, compiledGroups, compiledRoles, compiledPolicies, version);
    }

    private static <T> Map<String, List<T>> groupBy(List<T> items, java.util.function.Function<T, String> key) {
        Map<String, List<T>> by = new LinkedHashMap<>();
        for (T item : items) {
            by.computeIfAbsent(key.apply(item), k -> new ArrayList<>()).add(item);
        }
        return by;
    }
}
