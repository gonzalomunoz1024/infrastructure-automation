package com.iaf.console.identity.principals.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.application.Memberships;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.principals.domain.DirectoryGroup;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.ports.outbound.DirectoryPort;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The identity provider group sync, nightly and on the internal {@code group-sync:run}: for every
 * active account, the groups that map identity provider groups are compared with the directory's
 * members, matched by lowercased email, and every membership of the account is synced by the
 * identity provider's rule ({@link Memberships#sync}): what a person's provider groups no longer
 * give them goes, what they newly give them comes, an admin's groups and exclusions stay. People
 * of the provider groups without a membership in the account get none here; sign-in creates it.
 * Each directory group is read once per run. An account whose owning team the directory lacks is
 * marked {@code owningTeamMissing} (and the mark cleared once it is back), with
 * {@code AccountChanged updated}; every membership change has its {@code UserChanged}. An account
 * the run cannot finish (the directory failing, say) is left as it was and reported with the error.
 */
public final class GroupSync {

    static final String LAST_OWNER = "the account's last active owner keeps the owners group";

    private final AccountStore accounts;
    private final GroupStore groups;
    private final MembershipStore memberships;
    private final DirectoryPort directory;
    private final Memberships membership;
    private final IdentityEvents events;
    private final Clock clock;

    public GroupSync(AccountStore accounts, GroupStore groups, MembershipStore memberships, DirectoryPort directory, Memberships membership,
            IdentityEvents events, Clock clock) {
        this.accounts = accounts;
        this.groups = groups;
        this.memberships = memberships;
        this.directory = directory;
        this.membership = membership;
        this.events = events;
        this.clock = clock;
    }

    /** The internal {@code POST /internal/v1/group-sync:run}; anyone else gets 404. */
    public Mono<GroupSyncReport> run(Caller caller) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException("no such operation"));
        }
        return run(caller.actor());
    }

    public Mono<GroupSyncReport> run(String actor) {
        return Mono.defer(() -> {
            Instant started = clock.instant();
            Map<String, Mono<Optional<DirectoryGroup>>> read = new ConcurrentHashMap<>();
            return accounts.findAll()
                    .filter(Account::isActive)
                    .concatMap(account -> sync(account, read, actor)
                            .onErrorResume(e -> Mono.just(GroupSyncReport.AccountReport.failed(account.accountId(), account.owningTeam(), describe(e)))))
                    .collectList()
                    .map(reports -> new GroupSyncReport(started, clock.instant(), reports));
        });
    }

    private Mono<GroupSyncReport.AccountReport> sync(Account account, Map<String, Mono<Optional<DirectoryGroup>>> read, String actor) {
        AccountScope scope = AccountScope.of(account.accountId(), account.defaultRegion() == null ? "global" : account.defaultRegion(), actor);
        return groups.findAll(scope).collectList().flatMap(all -> {
            List<Group> mapping = all.stream().filter(g -> !g.idpGroups().isEmpty()).toList();
            Set<String> idpGroups = mapping.stream().flatMap(g -> g.idpGroups().stream()).collect(Collectors.toCollection(TreeSet::new));
            Set<String> wanted = new TreeSet<>(idpGroups);
            wanted.add(account.owningTeam());
            return Flux.fromIterable(wanted)
                    .concatMap(name -> read.computeIfAbsent(name, n -> directory.findGroup(n).map(Optional::of).defaultIfEmpty(Optional.empty()).cache())
                            .map(found -> Map.entry(name, found)))
                    .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                    .flatMap(found -> {
                        Map<String, String> names = new LinkedHashMap<>();
                        all.forEach(g -> names.put(g.groupId(), g.name()));
                        List<GroupSyncReport.Change> added = new ArrayList<>();
                        List<GroupSyncReport.Change> removed = new ArrayList<>();
                        List<GroupSyncReport.Change> skipped = new ArrayList<>();
                        boolean teamMissing = found.get(account.owningTeam()).isEmpty();
                        List<String> missing = idpGroups.stream().filter(n -> found.get(n).isEmpty()).toList();
                        return memberships.findAll(scope)
                                .concatMap(m -> membership.sync(scope, m, mappedNow(m, mapping, found), actor).doOnNext(synced -> {
                                    synced.added().forEach(g -> added.add(new GroupSyncReport.Change(m.userId(), m.email(), names.getOrDefault(g, g), null)));
                                    synced.removed().forEach(g -> removed.add(new GroupSyncReport.Change(m.userId(), m.email(), names.getOrDefault(g, g), null)));
                                    synced.kept().forEach(g -> skipped.add(new GroupSyncReport.Change(m.userId(), m.email(), names.getOrDefault(g, g), LAST_OWNER)));
                                }))
                                .then(mark(account, teamMissing, actor))
                                .then(Mono.fromSupplier(() -> new GroupSyncReport.AccountReport(account.accountId(), account.owningTeam(), teamMissing, added,
                                        removed, skipped, missing, null)));
                    });
        });
    }

    /** The account's groups that map an identity provider group the directory lists the person in. */
    private static Set<String> mappedNow(Membership m, List<Group> mapping, Map<String, Optional<DirectoryGroup>> found) {
        return mapping.stream()
                .filter(g -> g.idpGroups().stream().anyMatch(n -> found.getOrDefault(n, Optional.empty()).map(dg -> dg.hasEmail(m.email())).orElse(false)))
                .map(Group::groupId)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Marks or clears {@code owningTeamMissing} when it changes, reading the account again if it moved on meanwhile. */
    private Mono<Void> mark(Account account, boolean missing, String actor) {
        return mark(account, missing, actor, 3);
    }

    private Mono<Void> mark(Account account, boolean missing, String actor, int attempts) {
        if (!account.isActive() || account.owningTeamIsMissing() == missing) {
            return Mono.empty();
        }
        return accounts.update(account.withOwningTeamMissing(missing, clock.instant()), account.version())
                .flatMap(stored -> events.changed(IdentityEvents.Subject.ACCOUNT, stored.accountId(), stored.accountId(), actor, stored.version(), "updated",
                        stored).thenReturn(stored))
                .switchIfEmpty(Mono.defer(() -> attempts <= 1 ? Mono.<Account>empty()
                        : accounts.findById(account.accountId()).flatMap(again -> mark(again, missing, actor, attempts - 1)).then(Mono.<Account>empty())))
                .then();
    }

    private static String describe(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
