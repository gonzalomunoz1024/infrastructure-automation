package com.iaf.console.bff.demo.directory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.iaf.console.bff.access.domain.ManagedRoles;
import com.iaf.console.bff.demo.directory.domain.AccessRequest;
import com.iaf.console.bff.demo.directory.domain.DemoAccount;
import com.iaf.console.bff.demo.directory.domain.DemoMembership;
import com.iaf.console.bff.demo.directory.domain.DemoPerson;
import com.iaf.console.wfrn.Ulid;

/**
 * The people, accounts and memberships of the demo, shared by the demo identity provider (who can
 * sign in) and the demo identity stand-in (what they may do). Mutable where the console mutates
 * it: adding users, granting roles, access requests. One instance, in memory.
 */
public class DemoDirectory {

    public static final String ACCOUNT_COMPUTE = "123456789012";
    public static final String ACCOUNT_DATA = "210987654321";

    private final Map<String, DemoPerson> people = new LinkedHashMap<>();
    private final Map<String, DemoAccount> accounts = new LinkedHashMap<>();
    private final Map<String, Map<String, DemoMembership>> memberships = new ConcurrentHashMap<>();
    private final Map<String, AccessRequest> accessRequests = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> versions = new ConcurrentHashMap<>();
    private final List<DenyStatement> denies = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Clock clock;

    public DemoDirectory(Clock clock) {
        this.clock = clock;
        accounts.put(ACCOUNT_COMPUTE, new DemoAccount(ACCOUNT_COMPUTE, "Compute", "team-compute", "global"));
        accounts.put(ACCOUNT_DATA, new DemoAccount(ACCOUNT_DATA, "Data", "team-data", "global"));
        person("gonzalo", "Gonzalo Munoz", List.of("team-compute", "team-platform", "admin"));
        person("dana", "Dana Reyes", List.of("team-compute", "developer"));
        person("mateo", "Mateo Silva", List.of("team-compute", "ops"));
        person("raj", "Raj Patel", List.of("team-data", "data-approvers"));
        person("ana", "Ana Costa", List.of("team-data", "developer", "dba"));
        person("mia", "Mia Chen", List.of("team-platform", "compute-approvers", "platform-approvers"));
        person("noah", "Noah Berg", List.of("team-network"));
        join("gonzalo", ACCOUNT_COMPUTE, List.of("owners"), List.of(ManagedRoles.ADMIN));
        join("gonzalo", ACCOUNT_DATA, List.of("owners"), List.of(ManagedRoles.ADMIN));
        join("dana", ACCOUNT_COMPUTE, List.of("members", "compute-approvers"), List.of(ManagedRoles.OPERATOR));
        join("mateo", ACCOUNT_COMPUTE, List.of("members"), List.of(ManagedRoles.OPERATOR));
        join("raj", ACCOUNT_DATA, List.of("members", "data-approvers"), List.of(ManagedRoles.OPERATOR));
        join("ana", ACCOUNT_DATA, List.of("members", "viewers"), List.of(ManagedRoles.OPERATOR, ManagedRoles.VIEWER));
        join("mia", ACCOUNT_COMPUTE, List.of("viewers", "compute-approvers"), List.of(ManagedRoles.VIEWER));
    }

    private void person(String subject, String name, List<String> groups) {
        people.put(subject, new DemoPerson(subject, name, subject + "@example.com", groups));
    }

    private void join(String subject, String accountId, List<String> groups, List<String> roles) {
        memberships.computeIfAbsent(subject, s -> new ConcurrentHashMap<>()).put(accountId, new DemoMembership(accountId, groups, roles));
    }

    public List<DemoPerson> people() {
        return List.copyOf(people.values());
    }

    public Optional<DemoPerson> person(String subject) {
        return Optional.ofNullable(people.get(subject));
    }

    /** The person behind a console user id ({@code user_<subject>}). */
    public Optional<DemoPerson> byUserId(String userId) {
        return userId == null || !userId.startsWith("user_") ? Optional.empty() : person(userId.substring(5));
    }

    public List<DemoAccount> accounts() {
        return List.copyOf(accounts.values());
    }

    public Optional<DemoAccount> account(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public List<DemoMembership> membershipsOf(String subject) {
        Map<String, DemoMembership> m = memberships.get(subject);
        return m == null ? List.of() : List.copyOf(m.values());
    }

    public Optional<DemoMembership> membership(String subject, String accountId) {
        Map<String, DemoMembership> m = memberships.get(subject);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(accountId));
    }

    /** The people in an account, with their membership. */
    public List<Map.Entry<DemoPerson, DemoMembership>> members(String accountId) {
        List<Map.Entry<DemoPerson, DemoMembership>> out = new ArrayList<>();
        for (DemoPerson p : people.values()) {
            membership(p.subject(), accountId).ifPresent(m -> out.add(Map.entry(p, m)));
        }
        return out;
    }

    public List<String> rolesOf(String subject, String accountId) {
        return membership(subject, accountId).map(DemoMembership::roles).orElse(List.of());
    }

    public boolean isAdmin(String subject, String accountId) {
        return rolesOf(subject, accountId).contains(ManagedRoles.ADMIN);
    }

    /**
     * Whether a person may decide the account's access requests: an admin, or a member of an
     * approver group, whose custom {@code approver} role the identity service's demo grants
     * {@code access-request:get}, {@code :list} and {@code :decide} (compute-approvers, data-approvers).
     */
    public boolean decidesAccessRequests(String subject, String accountId) {
        return membership(subject, accountId)
                .map(m -> m.roles().contains(ManagedRoles.ADMIN) || m.groups().stream().anyMatch(g -> g.endsWith("-approvers")))
                .orElse(false);
    }

    /** Adds a person to an account (creating the person when new) and bumps the account's authorization version. */
    public synchronized DemoMembership addMember(String accountId, String subject, String email, String name, List<String> groups, List<String> roles) {
        people.computeIfAbsent(subject, s -> new DemoPerson(s, name == null ? s : name, email == null ? s + "@example.com" : email, List.of()));
        List<String> effectiveRoles = roles == null || roles.isEmpty() ? List.of(ManagedRoles.VIEWER) : roles;
        DemoMembership m = new DemoMembership(accountId, groups == null ? List.of(ManagedRoles.groupOf(effectiveRoles.get(0))) : groups, effectiveRoles);
        memberships.computeIfAbsent(subject, s -> new ConcurrentHashMap<>()).put(accountId, m);
        bump(accountId);
        return m;
    }

    public synchronized Optional<DemoMembership> grantRole(String accountId, String subject, String role) {
        Optional<DemoMembership> current = membership(subject, accountId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        DemoMembership next = current.get().withRole(role);
        memberships.get(subject).put(accountId, next);
        bump(accountId);
        return Optional.of(next);
    }

    public synchronized boolean removeMember(String accountId, String subject) {
        Map<String, DemoMembership> m = memberships.get(subject);
        boolean removed = m != null && m.remove(accountId) != null;
        if (removed) {
            bump(accountId);
        }
        return removed;
    }

    /**
     * An explicit deny of an account's policies, as the identity service evaluates one: the action,
     * and a resource pattern in which {@code *} matches anything, while a {@code *} in the resource
     * asked about is only itself.
     */
    public record DenyStatement(String accountId, String action, String resourcePattern) {

        boolean denies(String account, String askedAction, String resource) {
            if (!accountId.equals(account) || !action.equals(askedAction) || resource == null) {
                return false;
            }
            List<String> parts = new ArrayList<>();
            for (String part : resourcePattern.split("\\*", -1)) {
                parts.add(java.util.regex.Pattern.quote(part));
            }
            return resource.matches(String.join(".*", parts));
        }
    }

    /** Adds a deny statement to an account (the demo has no policy editor; tests use it); the account's version moves. */
    public DenyStatement deny(String accountId, String action, String resourcePattern) {
        DenyStatement statement = new DenyStatement(accountId, action, resourcePattern);
        denies.add(statement);
        bump(accountId);
        return statement;
    }

    public void removeDeny(DenyStatement statement) {
        denies.remove(statement);
        bump(statement.accountId());
    }

    /** The deny statement that refuses the action on the resource, if any. */
    public Optional<DenyStatement> deniedBy(String accountId, String action, String resource) {
        return denies.stream().filter(d -> d.denies(accountId, action, resource)).findFirst();
    }

    public long version(String accountId) {
        return versions.computeIfAbsent(accountId, a -> new AtomicLong(1)).get();
    }

    private void bump(String accountId) {
        versions.computeIfAbsent(accountId, a -> new AtomicLong(1)).incrementAndGet();
    }

    // access requests

    /** Opens a request; the caller has checked that no pending request of the same requester and role exists ({@link #pendingRequest}). */
    public synchronized AccessRequest requestAccess(String accountId, DemoPerson requester, String action, String resource, String role, String reason) {
        // a requester never decides their own request, so they are no approver of it
        List<String> approvers = members(accountId).stream().filter(e -> decidesAccessRequests(e.getKey().subject(), accountId))
                .map(e -> e.getKey().userId()).filter(userId -> !userId.equals(requester.userId())).sorted().toList();
        AccessRequest request = new AccessRequest("areq_" + Ulid.next().toLowerCase(), accountId,
                new AccessRequest.Requester(requester.userId(), requester.email(), requester.name()), action, resource, role, reason, "pending",
                clock.instant(), null, null, null, approvers);
        accessRequests.put(request.requestId(), request);
        return request;
    }

    /** One request as served now. */
    public Optional<AccessRequest> accessRequest(String requestId) {
        return Optional.ofNullable(accessRequests.get(requestId)).map(r -> r.served(clock.instant()));
    }

    /** The account's requests as served now (expired ones included), newest first, of one requester when one is named. */
    public List<AccessRequest> accessRequests(String accountId, String requesterUserId, String status) {
        Instant now = clock.instant();
        List<AccessRequest> out = new ArrayList<>();
        for (AccessRequest stored : accessRequests.values()) {
            AccessRequest r = stored.served(now);
            if (!r.accountId().equals(accountId)) {
                continue;
            }
            if (requesterUserId != null && !r.requester().userId().equals(requesterUserId)) {
                continue;
            }
            if (status != null && !status.isBlank() && !r.status().equals(status)) {
                continue;
            }
            out.add(r);
        }
        out.sort((a, b) -> b.requestedAt().compareTo(a.requestedAt()));
        return out;
    }

    /** Decides a request; approving grants the role. Empty when the request is unknown; the request as it is when no longer pending. */
    public synchronized Optional<AccessRequest> decide(String requestId, String decision, String byUserId, String comment) {
        AccessRequest request = accessRequests.get(requestId);
        if (request == null) {
            return Optional.empty();
        }
        AccessRequest served = request.served(clock.instant());
        if (!served.open()) {
            return Optional.of(served);
        }
        AccessRequest decided = request.decided(decision, byUserId, comment, clock.instant());
        accessRequests.put(requestId, decided);
        if ("approved".equals(decided.status())) {
            byUserId(request.requester().userId()).ifPresent(p -> grantRole(request.accountId(), p.subject(), request.role()));
        }
        return Optional.of(decided);
    }

    /** The requester's pending request for a role in the account, as the identity service keeps one per requester and role. */
    public Optional<AccessRequest> pendingRequest(String accountId, String requesterUserId, String role) {
        Instant now = clock.instant();
        for (AccessRequest stored : accessRequests.values()) {
            AccessRequest r = stored.served(now);
            if (r.open() && r.accountId().equals(accountId) && r.requester().userId().equals(requesterUserId) && r.role().equals(role)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public Instant now() {
        return clock.instant();
    }
}
