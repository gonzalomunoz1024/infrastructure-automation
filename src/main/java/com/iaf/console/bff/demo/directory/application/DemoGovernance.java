package com.iaf.console.bff.demo.directory.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.bff.access.domain.ManagedRoles;
import com.iaf.console.bff.demo.directory.domain.DemoMembership;
import com.iaf.console.bff.demo.directory.domain.DemoPerson;
import com.iaf.console.bff.session.domain.ActionPatterns;
import com.iaf.console.wfrn.Ulid;

/**
 * The organization's guardrails and the accounts' access reviews of the demo, as the identity
 * service keeps them (decision 34, WP 9.11): a guardrail's deny statement refuses whatever a role
 * allows in the accounts it targets, and when a guardrail of an account has allow statements an
 * action none of them allows is refused; a review takes who holds which group now, one item per
 * person and group. One instance, in memory.
 */
public class DemoGovernance {

    /** A guardrail: its statements are {@code {sid?, effect, actions, resources}} maps, kept as sent. */
    public record Guardrail(String guardrailId, String name, String description, List<Map<String, Object>> statements, List<String> accounts,
            int version, Instant createdAt, Instant updatedAt) {

        public Guardrail {
            statements = List.copyOf(statements);
            accounts = List.copyOf(accounts);
        }

        public boolean appliesTo(String accountId) {
            return accounts.contains("*") || accounts.contains(accountId);
        }
    }

    /** Which guardrail refused an action, and by which statement (null for an action none of its allows covers). */
    public record Refusal(Guardrail guardrail, String statement) {
    }

    /** An access review campaign. */
    public record Review(String reviewId, String accountId, String name, String status, String trigger, String startedBy, Instant startedAt,
            Instant dueAt, Instant closedAt, int version) {

        Review with(String nextStatus, Instant at) {
            return new Review(reviewId, accountId, name, nextStatus, trigger, startedBy, startedAt, dueAt, at, version + 1);
        }

        Review bumped() {
            return new Review(reviewId, accountId, name, status, trigger, startedBy, startedAt, dueAt, closedAt, version + 1);
        }
    }

    /** One person and group a review asks about. */
    public record Item(String itemId, String reviewId, String userId, String email, String name, String groupId, String groupName, List<String> roles,
            String decision, String decidedBy, Instant decidedAt, String comment) {

        public Item {
            roles = List.copyOf(roles);
        }

        Item decided(String nextDecision, String by, String withComment, Instant at) {
            return new Item(itemId, reviewId, userId, email, name, groupId, groupName, roles, nextDecision, by, at, withComment);
        }
    }

    private final DemoDirectory directory;
    private final Clock clock;
    private final Map<String, Guardrail> guardrails = new ConcurrentHashMap<>();
    private final Map<String, Review> reviews = new ConcurrentHashMap<>();
    private final Map<String, Item> items = new ConcurrentHashMap<>();

    public DemoGovernance(DemoDirectory directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    // guardrails

    /** Every guardrail, in id order (the order the evaluator reads them in). */
    public List<Guardrail> guardrails() {
        return guardrails.values().stream().sorted(Comparator.comparing(Guardrail::guardrailId)).toList();
    }

    public List<Guardrail> guardrailsOf(String accountId) {
        return guardrails().stream().filter(g -> g.appliesTo(accountId)).toList();
    }

    public Optional<Guardrail> guardrail(String guardrailId) {
        return Optional.ofNullable(guardrails.get(guardrailId));
    }

    public Optional<Guardrail> guardrailNamed(String name) {
        return guardrails.values().stream().filter(g -> g.name().equals(name)).findFirst();
    }

    public synchronized Guardrail saveGuardrail(String guardrailId, String name, String description, List<Map<String, Object>> statements,
            List<String> accounts) {
        Instant now = clock.instant();
        Guardrail previous = guardrailId == null ? null : guardrails.get(guardrailId);
        Guardrail saved = previous == null
                ? new Guardrail("gr_" + Ulid.next().toLowerCase(), name, description, statements, accounts, 1, now, now)
                : new Guardrail(previous.guardrailId(), name, description, statements, accounts, previous.version() + 1, previous.createdAt(), now);
        guardrails.put(saved.guardrailId(), saved);
        return saved;
    }

    public synchronized boolean deleteGuardrail(String guardrailId) {
        return guardrails.remove(guardrailId) != null;
    }

    /** The guardrail that refuses an action on a resource in an account, if any. */
    public Optional<Refusal> refusal(String accountId, String action, String resource) {
        List<Guardrail> applying = guardrailsOf(accountId);
        for (Guardrail g : applying) {
            for (Map<String, Object> s : g.statements()) {
                if ("deny".equals(s.get("effect")) && matches(s, action, resource)) {
                    return Optional.of(new Refusal(g, s.get("sid") == null ? null : String.valueOf(s.get("sid"))));
                }
            }
        }
        for (Guardrail g : applying) {
            boolean hasAllows = g.statements().stream().anyMatch(s -> "allow".equals(s.get("effect")));
            boolean allows = g.statements().stream().anyMatch(s -> "allow".equals(s.get("effect")) && matches(s, action, resource));
            if (hasAllows && !allows) {
                return Optional.of(new Refusal(g, null));
            }
        }
        return Optional.empty();
    }

    private static boolean matches(Map<String, Object> statement, String action, String resource) {
        return anyMatches(statement.get("actions"), action) && (resource == null || anyMatches(statement.get("resources"), resource));
    }

    private static boolean anyMatches(Object patterns, String value) {
        if (patterns instanceof List<?> list) {
            for (Object p : list) {
                if (ActionPatterns.matches(String.valueOf(p), value)) {
                    return true;
                }
            }
        }
        return false;
    }

    // access reviews

    /** The account's campaigns, the one started last first. */
    public List<Review> reviews(String accountId) {
        return reviews.values().stream().filter(r -> r.accountId().equals(accountId)).sorted(Comparator.comparing(Review::reviewId).reversed()).toList();
    }

    public Optional<Review> review(String accountId, String reviewId) {
        return Optional.ofNullable(reviews.get(reviewId)).filter(r -> r.accountId().equals(accountId));
    }

    public Optional<Review> openReview(String accountId) {
        return reviews(accountId).stream().filter(r -> "open".equals(r.status())).findFirst();
    }

    /** Starts a campaign over the account's memberships now; the caller checked that none is open. */
    public synchronized Review startReview(String accountId, String name, int dueInDays, String startedBy) {
        Instant now = clock.instant();
        String reviewId = "arev_" + Ulid.next().toLowerCase();
        List<Item> made = new ArrayList<>();
        for (Map.Entry<DemoPerson, DemoMembership> e : directory.members(accountId)) {
            for (String group : e.getValue().groups()) {
                DemoPerson p = e.getKey();
                made.add(new Item("arevi_" + Ulid.next().toLowerCase(), reviewId, p.userId(), p.email(), p.name(), "grp_" + group, group, rolesOf(group),
                        "pending", null, null, null));
            }
        }
        Review review = new Review(reviewId, accountId, name == null || name.isBlank() ? "Access review " + now.toString().substring(0, 10) : name,
                made.isEmpty() ? "closed" : "open", "manual", startedBy, now, now.plus(Duration.ofDays(dueInDays)), made.isEmpty() ? now : null, 1);
        reviews.put(reviewId, review);
        made.forEach(i -> items.put(i.itemId(), i));
        return review;
    }

    /** A campaign's items in the order they were made, of one decision when one is named. */
    public List<Item> items(String reviewId, String decision) {
        return items.values().stream().filter(i -> i.reviewId().equals(reviewId) && (decision == null || decision.equals(i.decision())))
                .sorted(Comparator.comparing(Item::itemId)).toList();
    }

    public Optional<Item> item(String reviewId, String itemId) {
        return Optional.ofNullable(items.get(itemId)).filter(i -> i.reviewId().equals(reviewId));
    }

    /** Records a decision (the caller checked it may be made); a revoke takes the group off the person; the last decision closes the campaign. */
    public synchronized Item decide(Review review, Item item, String decision, String by, String comment) {
        Instant now = clock.instant();
        Item decided = item.decided(decision, by, comment, now);
        items.put(item.itemId(), decided);
        if ("revoke".equals(decision)) {
            directory.byUserId(item.userId()).ifPresent(p -> directory.removeGroup(p.subject(), review.accountId(), item.groupName()));
        }
        boolean done = items(review.reviewId(), "pending").isEmpty();
        reviews.put(review.reviewId(), done ? review.with("closed", now) : review.bumped());
        return decided;
    }

    public synchronized Review cancel(Review review) {
        Review cancelled = review.with("cancelled", clock.instant());
        reviews.put(review.reviewId(), cancelled);
        return cancelled;
    }

    /** Whether a person is the account's last owner, whose owners group a review never takes. */
    public boolean lastOwner(String accountId, String userId) {
        List<String> owners = directory.members(accountId).stream().filter(e -> e.getValue().groups().contains("owners")).map(e -> e.getKey().userId())
                .toList();
        return owners.size() == 1 && owners.get(0).equals(userId);
    }

    /** The counts of a campaign's items by decision. */
    public Map<String, Integer> counts(String reviewId) {
        List<Item> all = items(reviewId, null);
        return Map.of("total", all.size(), "kept", count(all, "keep"), "revoked", count(all, "revoke"), "expired", count(all, "expired"), "pending",
                count(all, "pending"));
    }

    private static int count(List<Item> all, String decision) {
        return (int) all.stream().filter(i -> decision.equals(i.decision())).count();
    }

    private static List<String> rolesOf(String group) {
        return ManagedRoles.ALL.stream().filter(r -> ManagedRoles.groupOf(r).equals(group)).toList();
    }
}
