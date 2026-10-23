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
        join("gonzalo", ACCOUNT_COMPUTE, List.of("account-admins"), List.of(ManagedRoles.ADMIN));
        join("gonzalo", ACCOUNT_DATA, List.of("account-admins"), List.of(ManagedRoles.ADMIN));
        join("dana", ACCOUNT_COMPUTE, List.of("operators", "compute-approvers"), List.of(ManagedRoles.OPERATOR));
        join("mateo", ACCOUNT_COMPUTE, List.of("operators"), List.of(ManagedRoles.OPERATOR));
        join("raj", ACCOUNT_DATA, List.of("operators", "data-approvers"), List.of(ManagedRoles.OPERATOR));
        join("ana", ACCOUNT_DATA, List.of("operators"), List.of(ManagedRoles.OPERATOR));
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

    /** Adds a person to an account (creating the person when new) and bumps the account's authorization version. */
    public synchronized DemoMembership addMember(String accountId, String subject, String email, String name, List<String> groups, List<String> roles) {
        people.computeIfAbsent(subject, s -> new DemoPerson(s, name == null ? s : name, email == null ? s + "@example.com" : email, List.of()));
        List<String> effectiveRoles = roles == null || roles.isEmpty() ? List.of(ManagedRoles.VIEWER) : roles;
        DemoMembership m = new DemoMembership(accountId, groups == null ? List.of(effectiveRoles.get(0) + "s") : groups, effectiveRoles);
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

    public long version(String accountId) {
        return versions.computeIfAbsent(accountId, a -> new AtomicLong(1)).get();
    }

    private void bump(String accountId) {
        versions.computeIfAbsent(accountId, a -> new AtomicLong(1)).incrementAndGet();
    }

    // access requests

    public synchronized AccessRequest requestAccess(String accountId, DemoPerson requester, String action, String resource, String role, String reason) {
        for (AccessRequest existing : accessRequests.values()) {
            if (existing.open() && existing.accountId().equals(accountId) && existing.requester().userId().equals(requester.userId())
                    && existing.action().equals(action) && existing.role().equals(role)) {
                return existing;
            }
        }
        List<String> approvers = members(accountId).stream().filter(e -> e.getValue().roles().contains(ManagedRoles.ADMIN)).map(e -> e.getKey().userId()).toList();
        AccessRequest request = new AccessRequest("areq_" + Ulid.next().toLowerCase(), accountId,
                new AccessRequest.Requester(requester.userId(), requester.email(), requester.name()), action, resource, role, reason, "pending",
                clock.instant(), null, null, null, approvers);
        accessRequests.put(request.requestId(), request);
        return request;
    }

    public Optional<AccessRequest> accessRequest(String requestId) {
        return Optional.ofNullable(accessRequests.get(requestId));
    }

    public List<AccessRequest> accessRequests(String accountId, String requesterUserId, String status) {
        List<AccessRequest> out = new ArrayList<>();
        for (AccessRequest r : accessRequests.values()) {
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

    /** Decides a request; approving grants the role. Empty when the request is unknown; the same request when already decided. */
    public synchronized Optional<AccessRequest> decide(String requestId, String decision, String byUserId, String comment) {
        AccessRequest request = accessRequests.get(requestId);
        if (request == null) {
            return Optional.empty();
        }
        if (!request.open()) {
            return Optional.of(request);
        }
        AccessRequest decided = request.decided(decision, byUserId, comment, clock.instant());
        accessRequests.put(requestId, decided);
        if ("approved".equals(decided.status())) {
            byUserId(request.requester().userId()).ifPresent(p -> grantRole(request.accountId(), p.subject(), request.role()));
        }
        return Optional.of(decided);
    }

    /** True when an open request with the same shape exists already. */
    public boolean hasOpenRequest(String accountId, String requesterUserId, String action, String role) {
        for (AccessRequest r : accessRequests.values()) {
            if (r.open() && r.accountId().equals(accountId) && r.requester().userId().equals(requesterUserId) && r.action().equals(action)
                    && r.role().equals(role)) {
                return true;
            }
        }
        return false;
    }

    public Instant now() {
        return clock.instant();
    }
}
