package com.iaf.console.identity.demo;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.identity.accessrequests.application.AccessRequestCommands;
import com.iaf.console.identity.accounts.application.AccountCommands;
import com.iaf.console.identity.accounts.application.CreateAccount;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.application.GroupCommands;
import com.iaf.console.identity.iam.application.GroupInput;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.application.PolicyCommands;
import com.iaf.console.identity.iam.application.PolicyInput;
import com.iaf.console.identity.iam.application.RoleCommands;
import com.iaf.console.identity.iam.application.RoleInput;
import com.iaf.console.identity.iam.domain.ManagedIam;
import com.iaf.console.identity.iam.domain.Statement;
import com.iaf.console.identity.principals.application.AddUser;
import com.iaf.console.identity.principals.application.PrincipalCommands;
import com.iaf.console.identity.principals.domain.DirectoryGroup;
import com.iaf.console.identity.principals.domain.User;
import com.iaf.console.identity.principals.ports.outbound.UserStore;
import com.iaf.console.kit.errors.ConflictException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The demo's people and accounts, the ones platform-fake's world, the BFF's demo stand-in and the
 * console's mocks use: account 123456789012 (team-compute) with gonzalo as owner, dana and mateo
 * as operators and mia as viewer; account 210987654321 (team-data) with gonzalo as owner, raj as
 * operator and ana as operator and viewer. Each account also has a custom {@code approver} role
 * (policy {@code approver}: read and decide access requests) held by a group of approvers:
 * {@code compute-approvers} (dana, mia) and {@code data-approvers} (raj), so access requests show
 * approvers beyond the owners. People get the ids {@code user_<name>} and are linked to the demo
 * identity provider by their short name, so the BFF's resolve finds them at first sign-in; noah
 * exists in the identity provider but has no account. The demo directory ({@link #directory()})
 * has each account's owning team hold the people the seed puts in {@code members} there, so the
 * group sync finds the teams the accounts map.
 */
public final class DemoSeed {

    private static final Logger log = LoggerFactory.getLogger(DemoSeed.class);

    public static final String ACCOUNT_COMPUTE = "123456789012";
    public static final String ACCOUNT_DATA = "210987654321";
    public static final String ISSUER = "http://localhost:18081/demo-idp";
    public static final String APPROVER = "approver";

    /** One demo person: short name, full name, the account and the managed groups they are in. */
    public record Person(String name, String fullName, String accountId, List<String> groups) {

        public String email() {
            return name + "@example.com";
        }

        public String userId() {
            return "user_" + name;
        }
    }

    /** A custom group of approvers in an account, with the people in it. */
    public record Approvers(String accountId, String group, List<String> people) {
    }

    public static final List<Person> PEOPLE = List.of(
            new Person("gonzalo", "Gonzalo Munoz", ACCOUNT_COMPUTE, List.of(ManagedIam.OWNERS)),
            new Person("gonzalo", "Gonzalo Munoz", ACCOUNT_DATA, List.of(ManagedIam.OWNERS)),
            new Person("dana", "Dana Reyes", ACCOUNT_COMPUTE, List.of(ManagedIam.MEMBERS)),
            new Person("mateo", "Mateo Silva", ACCOUNT_COMPUTE, List.of(ManagedIam.MEMBERS)),
            new Person("mia", "Mia Chen", ACCOUNT_COMPUTE, List.of(ManagedIam.VIEWERS)),
            new Person("raj", "Raj Patel", ACCOUNT_DATA, List.of(ManagedIam.MEMBERS)),
            new Person("ana", "Ana Costa", ACCOUNT_DATA, List.of(ManagedIam.MEMBERS, ManagedIam.VIEWERS)));

    public static final List<Approvers> APPROVERS = List.of(
            new Approvers(ACCOUNT_COMPUTE, "compute-approvers", List.of("dana", "mia")),
            new Approvers(ACCOUNT_DATA, "data-approvers", List.of("raj")));

    private static final Map<String, String> ACCOUNT_NAMES = Map.of(ACCOUNT_COMPUTE, "Compute", ACCOUNT_DATA, "Data");
    private static final Map<String, String> ACCOUNT_TEAMS = Map.of(ACCOUNT_COMPUTE, "team-compute", ACCOUNT_DATA, "team-data");

    private final AccountCommands accounts;
    private final AccountStore accountStore;
    private final PrincipalCommands principals;
    private final UserStore users;
    private final PolicyCommands policies;
    private final RoleCommands roles;
    private final GroupCommands groups;
    private final IamSupport support;
    private final Clock clock;

    public DemoSeed(AccountCommands accounts, AccountStore accountStore, PrincipalCommands principals, UserStore users, PolicyCommands policies,
            RoleCommands roles, GroupCommands groups, IamSupport support, Clock clock) {
        this.accounts = accounts;
        this.accountStore = accountStore;
        this.principals = principals;
        this.users = users;
        this.policies = policies;
        this.roles = roles;
        this.groups = groups;
        this.support = support;
        this.clock = clock;
    }

    /** Seeds what is missing; safe to run on every start. */
    public Mono<Void> seed() {
        Caller seeder = Caller.internal("console-identity-service", "global");
        List<String> names = PEOPLE.stream().map(Person::name).distinct().toList();
        return Flux.fromIterable(names).concatMap(this::person).then()
                .then(Flux.fromIterable(ACCOUNT_NAMES.keySet().stream().sorted().toList()).concatMap(id -> account(seeder, id)).then())
                .then(Flux.fromIterable(PEOPLE).filter(p -> !p.groups().contains(ManagedIam.OWNERS)).concatMap(p -> member(seeder, p)).then())
                .then(Flux.fromIterable(APPROVERS).concatMap(a -> approvers(seeder, a)).then())
                .doOnSuccess(v -> log.info("demo seeded: accounts {} (team-compute) and {} (team-data); people {}", ACCOUNT_COMPUTE, ACCOUNT_DATA, names));
    }

    /** The person with the id {@code user_<name>}, linked to the demo identity provider (an existing row is linked, never replaced). */
    private Mono<Void> person(String name) {
        String fullName = fullName(name);
        return users.findByEmail(name + "@example.com")
                .switchIfEmpty(Mono.defer(() -> users.insert(User.create("user_" + name, name + "@example.com", fullName, ISSUER, name, clock.instant()))))
                .filter(u -> u.issuer() == null)
                .flatMap(u -> users.save(u.signedIn(ISSUER, name, fullName, clock.instant())))
                .then();
    }

    private Mono<Void> account(Caller seeder, String accountId) {
        return accountStore.findById(accountId)
                .switchIfEmpty(Mono.defer(() -> accounts.create(seeder,
                        new CreateAccount(ACCOUNT_NAMES.get(accountId), ACCOUNT_TEAMS.get(accountId), "gonzalo@example.com", "global", null), accountId)))
                .then();
    }

    private Mono<Void> member(Caller seeder, Person p) {
        return principals.addUser(seeder, p.accountId(), new AddUser(p.email(), p.fullName(), p.groups()))
                .onErrorResume(ConflictException.class, e -> Mono.empty())
                .then();
    }

    /** The approver policy, role and group of an account; each step is skipped when it is already there. */
    private Mono<Void> approvers(Caller seeder, Approvers a) {
        String requests = support.anyResource(a.accountId(), AccessRequestCommands.KIND);
        PolicyInput policy = new PolicyInput(APPROVER, "Reads and decides the account's access requests",
                List.of(Statement.allow("approve", List.of("access-request:get", "access-request:list", "access-request:decide"), List.of(requests))));
        RoleInput role = new RoleInput(APPROVER, "Decides access requests", List.of(APPROVER));
        return policies.create(seeder, a.accountId(), policy).onErrorResume(ConflictException.class, e -> Mono.empty())
                .then(roles.create(seeder, a.accountId(), role).onErrorResume(ConflictException.class, e -> Mono.empty()))
                .then(Flux.fromIterable(a.people()).concatMap(name -> users.findByEmail(name + "@example.com").map(User::userId)).collectList())
                .flatMap(members -> groups.create(seeder, a.accountId(), new GroupInput(a.group(), "Approves access requests", List.of(APPROVER), members, List.of()))
                        .onErrorResume(ConflictException.class, e -> Mono.empty()))
                .then();
    }

    /** The demo directory: each account's owning team, with the people in its {@code members} group (email, and the short name as subject). */
    public static Map<String, List<DirectoryGroup.Member>> directory() {
        Map<String, List<DirectoryGroup.Member>> teams = new TreeMap<>();
        for (String accountId : ACCOUNT_TEAMS.keySet().stream().sorted().toList()) {
            teams.put(ACCOUNT_TEAMS.get(accountId), PEOPLE.stream()
                    .filter(p -> p.accountId().equals(accountId) && p.groups().contains(ManagedIam.MEMBERS))
                    .map(p -> new DirectoryGroup.Member(p.email(), p.name()))
                    .toList());
        }
        return teams;
    }

    private static String fullName(String name) {
        return PEOPLE.stream().filter(p -> p.name().equals(name)).map(Person::fullName).findFirst().orElse(name);
    }
}
