package com.iaf.console.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.OK;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.identity.accessreviews.adapters.AccessReviewProperties;
import com.iaf.console.identity.accessreviews.application.AccessReviewCampaigns;
import com.iaf.console.identity.accessreviews.application.AccessReviewSweep;
import com.iaf.console.identity.accessreviews.domain.AccessReview;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewStore;
import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Schedulers;

/**
 * Access reviews end to end over HTTP, with the sweep run at chosen instants: the same tests run on
 * the in-memory stores and on MongoDB. The schedule is pushed out of the way so the tests' sweeps
 * are the only ones; as the tests share their context, a sweep looks no further than a couple of
 * days ahead, an account is made old by moving its creation back, and each test reads its own
 * accounts only.
 */
abstract class AccessReviewFlows extends WithService {

    static final String SYSTEM = "system:access-review";

    @DynamicPropertySource
    static void reviewProperties(DynamicPropertyRegistry registry) {
        registry.add("console.identity.access-reviews.sweep-every", () -> "3650d");
    }

    @Autowired
    OutboxPort outbox;
    @Autowired
    AccessReviewSweep sweep;
    @Autowired
    AccessReviewCampaigns campaigns;
    @Autowired
    AccessReviewStore reviewStore;
    @Autowired
    AccountStore accountStore;
    @Autowired
    Clock clock;
    @Autowired
    AccessReviewProperties properties;

    /** An account made for one test: its id and first owner. */
    record Seeded(String accountId, String ownerId) {

        String owner() {
            return bearer(ownerId, accountId, List.of("owners"));
        }

        String base() {
            return "/v1/accounts/" + accountId;
        }

        String users() {
            return base() + "/users";
        }

        String reviews() {
            return base() + "/access-reviews";
        }

        String review(String reviewId) {
            return reviews() + "/" + reviewId;
        }
    }

    Seeded newAccount() {
        int n = next();
        JsonNode account = call(POST, "/v1/accounts", internal(), Map.of("name", "Reviewed " + n + " " + RUN, "owningTeam", "team-r" + n + "-" + RUN,
                "ownerEmail", "owner-r" + n + "-" + RUN + "@example.com"), CREATED);
        String accountId = account.get("accountId").asText();
        JsonNode users = call(GET, "/v1/accounts/" + accountId + "/users", internal(), null, OK);
        return new Seeded(accountId, users.get("items").get(0).get("userId").asText());
    }

    String addUser(Seeded s, String shortName, List<String> groups) {
        JsonNode user = call(POST, s.users(), s.owner(), Map.of("email", shortName + "-" + next() + "-" + RUN + "@example.com", "name", shortName, "groups",
                groups), CREATED);
        return user.get("userId").asText();
    }

    List<String> groupsOf(Seeded s, String userId) {
        return strings(call(GET, s.users() + "/" + userId, internal(), null, OK).get("groups"));
    }

    JsonNode items(Seeded s, String reviewId) {
        return call(GET, s.review(reviewId) + "/items", internal(), null, OK).get("items");
    }

    static String decide(String review, String itemId) {
        return review + "/items/" + itemId + ":decide";
    }

    /** The id of the item that reviews this person's group. */
    static String itemOf(JsonNode items, String userId, String groupName) {
        for (JsonNode item : items) {
            if (userId.equals(item.get("userId").asText()) && groupName.equals(item.get("groupName").asText())) {
                return item.get("itemId").asText();
            }
        }
        throw new AssertionError("no item of " + userId + " in " + groupName + ": " + items);
    }

    static JsonNode item(JsonNode items, String itemId) {
        for (JsonNode item : items) {
            if (itemId.equals(item.get("itemId").asText())) {
                return item;
            }
        }
        throw new AssertionError("no item " + itemId + " in " + items);
    }

    static void assertCounts(JsonNode review, int total, int kept, int revoked, int expired, int pending) {
        JsonNode counts = review.get("counts");
        assertThat(List.of(counts.get("total").asInt(), counts.get("kept").asInt(), counts.get("revoked").asInt(), counts.get("expired").asInt(),
                counts.get("pending").asInt())).as("total, kept, revoked, expired, pending of %s", review).containsExactly(total, kept, revoked, expired, pending);
    }

    List<EventEnvelope> events(Seeded s, String type, String subjectId) {
        List<EventEnvelope> events = outbox.after(0, 100_000).filter(e -> s.accountId().equals(e.partitionKey())).map(OutboxEntry::event)
                .filter(e -> type.equals(e.type()) && subjectId.equals(e.aggregateId())).collectList().block();
        assertThat(events).isNotNull();
        return events;
    }

    static List<String> actions(List<EventEnvelope> events) {
        return events.stream().map(e -> e.data().get("action").asText()).toList();
    }

    /** Moves the account's creation back, as if it were made that long ago. */
    void backdate(Seeded s, Duration age) {
        Account a = accountStore.findById(s.accountId()).block();
        assertThat(a).isNotNull();
        Account older = new Account(a.accountId(), a.name(), a.status(), a.owningTeam(), a.defaultRegion(), a.platformServiceAccountId(), a.version() + 1,
                a.createdAt().minus(age), a.updatedAt(), a.costCentre(), a.closedAt(), a.closeReason(), a.owningTeamMissing());
        assertThat(accountStore.update(older, a.version()).block()).isNotNull();
    }

    @Test
    void theSettingsHaveTheirDefaultsAndRefuseNonsense() {
        assertThat(properties.sweepEvery()).isEqualTo(Duration.ofDays(3650));
        assertThat(properties.scheduled().enabled()).isTrue();
        assertThat(properties.scheduled().cadence()).isEqualTo(Duration.ofDays(90));
        assertThat(properties.scheduled().dueInDays()).isEqualTo(14);
        assertThat(new AccessReviewProperties(null, null).sweepEvery()).isEqualTo(Duration.ofMinutes(15));
        assertThatThrownBy(() -> new AccessReviewProperties(Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccessReviewProperties.Scheduled(true, Duration.ofDays(-1), 14)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccessReviewProperties.Scheduled(true, null, 91)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCampaignReviewsEachActivePersonAndGroupOnce() {
        Seeded s = newAccount();
        String dana = addUser(s, "dana", List.of("members"));
        String sam = addUser(s, "sam", List.of("members", "viewers"));
        String gone = addUser(s, "gone", List.of("viewers"));
        call(PATCH, s.users() + "/" + gone, s.owner(), Map.of("status", "suspended"), OK);

        call(POST, s.reviews(), s.owner(), Map.of("dueInDays", 0), BAD_REQUEST);
        call(POST, s.reviews(), s.owner(), Map.of("dueInDays", 91), BAD_REQUEST);
        call(POST, s.reviews(), s.owner(), Map.of("name", " "), BAD_REQUEST);
        call(POST, s.reviews(), s.owner(), Map.of("name", "x".repeat(201)), BAD_REQUEST);

        JsonNode review = call(POST, s.reviews(), s.owner(), Map.of("name", "Q4 review", "dueInDays", 7), CREATED);
        String id = review.get("reviewId").asText();
        assertThat(id).startsWith("arev_");
        assertThat(review.get("name").asText()).isEqualTo("Q4 review");
        assertThat(review.get("status").asText()).isEqualTo("open");
        assertThat(review.get("trigger").asText()).isEqualTo("manual");
        assertThat(review.get("startedBy").asText()).isEqualTo(s.ownerId());
        assertThat(review.get("version").asLong()).isEqualTo(1);
        assertThat(review.has("closedAt")).isFalse();
        assertThat(Duration.between(Instant.parse(review.get("startedAt").asText()), Instant.parse(review.get("dueAt").asText()))).isEqualTo(Duration.ofDays(7));
        // the owner in owners, dana in members, sam in both; the suspended person holds no access to review
        assertCounts(review, 4, 0, 0, 0, 4);

        JsonNode second = call(POST, s.reviews(), s.owner(), Map.of(), CONFLICT);
        assertThat(second.get("error").asText()).contains(id);
        assertThat(second.get("detail").get("reviewId").asText()).as("the 409 names the open campaign").isEqualTo(id);
        call(POST, s.reviews(), internal(), Map.of("name", "again"), CONFLICT);

        assertThat(call(GET, s.review(id), s.owner(), null, OK)).isEqualTo(review);
        JsonNode items = items(s, id);
        assertThat(items).hasSize(4);
        assertThat(texts(items, "userId")).containsOnly(s.ownerId(), dana, sam).doesNotContain(gone);
        assertThat(texts(items, "itemId")).allMatch(i -> i.startsWith("arevi_")).doesNotHaveDuplicates();
        assertThat(texts(items, "reviewId")).containsOnly(id);
        assertThat(texts(items, "decision")).containsOnly("pending");
        JsonNode owner = item(items, itemOf(items, s.ownerId(), "owners"));
        assertThat(strings(owner.get("roles"))).containsExactly("account-admin");
        assertThat(owner.get("email").asText()).startsWith("owner-r");
        JsonNode samViewer = item(items, itemOf(items, sam, "viewers"));
        assertThat(strings(samViewer.get("roles"))).containsExactly("viewer");
        assertThat(samViewer.get("name").asText()).isEqualTo("sam");
        assertThat(samViewer.get("groupId").asText()).startsWith("grp_");
        assertThat(samViewer.has("decidedBy")).isFalse();
        assertThat(strings(item(items, itemOf(items, sam, "members")).get("roles"))).containsExactly("operator");
        assertThat(item(items, itemOf(items, dana, "members")).get("groupName").asText()).isEqualTo("members");

        // items page in the order they were made, and filter by decision
        JsonNode page1 = call(GET, s.review(id) + "/items?limit=3", s.owner(), null, OK);
        assertThat(page1.get("items")).hasSize(3);
        JsonNode page2 = call(GET, s.review(id) + "/items?limit=3&after=" + page1.get("next").asText(), s.owner(), null, OK);
        assertThat(page2.get("items")).hasSize(1);
        assertThat(page2.has("next")).isFalse();
        List<String> paged = new ArrayList<>(texts(page1.get("items"), "itemId"));
        paged.addAll(texts(page2.get("items"), "itemId"));
        assertThat(paged).isEqualTo(texts(items, "itemId")).isSorted();
        assertThat(call(GET, s.review(id) + "/items?decision=pending", s.owner(), null, OK).get("items")).hasSize(4);
        assertThat(call(GET, s.review(id) + "/items?decision=keep", s.owner(), null, OK).get("items")).isEmpty();
        call(GET, s.review(id) + "/items?decision=weird", s.owner(), null, BAD_REQUEST);
        call(GET, s.review(id) + "/items?after=nonsense", s.owner(), null, BAD_REQUEST);

        // the campaigns page started last first
        JsonNode cancelled = call(POST, s.review(id) + ":cancel", s.owner(), null, OK);
        JsonNode next = call(POST, s.reviews(), s.owner(), null, CREATED);
        assertThat(next.get("name").asText()).as("a default name").startsWith("Access review 20");
        assertThat(Duration.between(Instant.parse(next.get("startedAt").asText()), Instant.parse(next.get("dueAt").asText()))).isEqualTo(Duration.ofDays(14));
        JsonNode first = call(GET, s.reviews() + "?limit=1", s.owner(), null, OK);
        assertThat(texts(first.get("items"), "reviewId")).containsExactly(next.get("reviewId").asText());
        JsonNode rest = call(GET, s.reviews() + "?limit=1&after=" + first.get("next").asText(), s.owner(), null, OK);
        assertThat(texts(rest.get("items"), "reviewId")).containsExactly(id);
        assertThat(rest.get("items").get(0)).isEqualTo(cancelled);
        assertThat(rest.has("next")).isFalse();
    }

    @Test
    void adminsKeepOrRevokeAndTheLastDecisionClosesTheCampaign() {
        Seeded s = newAccount();
        String olga = addUser(s, "olga", List.of("owners"));
        String dana = addUser(s, "dana", List.of("members"));
        String mia = addUser(s, "mia", List.of("viewers"));
        String audrey = addUser(s, "audrey", List.of("auditors"));
        String olgaToken = bearer(olga, s.accountId(), List.of("owners"));
        String operator = bearer(dana, s.accountId(), List.of("members"));
        String viewer = bearer(mia, s.accountId(), List.of("viewers"));
        String auditor = bearer(audrey, s.accountId(), List.of("auditors"));

        // the reviews are the admins'; auditors read them; viewers and operators have them carved out of their policies
        JsonNode refused = call(GET, s.reviews(), viewer, null, FORBIDDEN);
        assertThat(refused.get("denied").get("action").asText()).isEqualTo("access-review:list");
        assertThat(refused.get("denied").get("suggestedRole").asText()).isEqualTo("auditor");
        call(GET, s.reviews(), operator, null, FORBIDDEN);
        assertThat(call(POST, s.reviews(), operator, Map.of(), FORBIDDEN).get("denied").get("suggestedRole").asText()).isEqualTo("account-admin");
        call(POST, s.reviews(), auditor, Map.of(), FORBIDDEN);

        JsonNode review = call(POST, s.reviews(), olgaToken, Map.of("name", "Who holds what"), CREATED);
        String id = review.get("reviewId").asText();
        String one = s.review(id);
        assertCounts(review, 5, 0, 0, 0, 5);
        call(GET, one, viewer, null, FORBIDDEN);
        call(GET, one, operator, null, FORBIDDEN);
        call(GET, one + "/items", operator, null, FORBIDDEN);
        call(POST, one + ":cancel", operator, null, FORBIDDEN);
        assertThat(texts(call(GET, s.reviews(), auditor, null, OK).get("items"), "reviewId")).containsExactly(id);
        assertThat(call(GET, one, auditor, null, OK).get("reviewId").asText()).isEqualTo(id);
        JsonNode items = call(GET, one + "/items", auditor, null, OK).get("items");
        String ownerItem = itemOf(items, s.ownerId(), "owners");
        String olgaItem = itemOf(items, olga, "owners");
        String danaItem = itemOf(items, dana, "members");
        String miaItem = itemOf(items, mia, "viewers");
        String audreyItem = itemOf(items, audrey, "auditors");

        // nobody reviews their own access, and auditors decide nothing
        JsonNode own = call(POST, decide(one, olgaItem), olgaToken, Map.of("decision", "keep"), FORBIDDEN);
        assertThat(own.get("denied").get("layer").asText()).isEqualTo("console-iam");
        assertThat(own.get("denied").get("action").asText()).isEqualTo("access-review:decide");
        assertThat(own.get("denied").get("resource").asText()).isEqualTo("wfrn:wf:iam::" + s.accountId() + ":access-review/" + id);
        assertThat(own.get("denied").get("reason").asText()).isEqualTo("nobody reviews their own access");
        assertThat(own.get("denied").get("requestable").asBoolean()).isFalse();
        call(POST, decide(one, ownerItem), auditor, Map.of("decision", "keep"), FORBIDDEN);
        call(POST, decide(one, ownerItem), operator, Map.of("decision", "revoke"), FORBIDDEN);

        call(POST, decide(one, danaItem), olgaToken, Map.of("decision", "maybe"), BAD_REQUEST);
        call(POST, decide(one, danaItem), olgaToken, Map.of("decision", "expired"), BAD_REQUEST);
        call(POST, decide(one, danaItem), olgaToken, Map.of(), BAD_REQUEST);
        call(POST, decide(one, danaItem), olgaToken, Map.of("decision", "keep", "comment", "x".repeat(2001)), BAD_REQUEST);
        call(POST, decide(one, "arevi_nope"), olgaToken, Map.of("decision", "keep"), NOT_FOUND);
        call(POST, decide(s.review("arev_nope"), danaItem), olgaToken, Map.of("decision", "keep"), NOT_FOUND);

        JsonNode kept = call(POST, decide(one, danaItem), olgaToken, Map.of("decision", "keep", "comment", "runs the deploys"), OK);
        assertThat(kept.get("itemId").asText()).isEqualTo(danaItem);
        assertThat(kept.get("decision").asText()).isEqualTo("keep");
        assertThat(kept.get("decidedBy").asText()).isEqualTo(olga);
        assertThat(kept.get("comment").asText()).isEqualTo("runs the deploys");
        assertThat(kept.has("decidedAt")).isTrue();
        assertThat(groupsOf(s, dana)).containsExactly("members");
        JsonNode decided = call(POST, decide(one, danaItem), s.owner(), Map.of("decision", "revoke"), CONFLICT);
        assertThat(decided.get("error").asText()).as("a decision is final").contains("keep");

        JsonNode revoked = call(POST, decide(one, miaItem), s.owner(), Map.of("decision", "revoke", "comment", "left the team"), OK);
        assertThat(revoked.get("decision").asText()).isEqualTo("revoke");
        assertThat(revoked.get("decidedBy").asText()).isEqualTo(s.ownerId());
        assertThat(groupsOf(s, mia)).as("the revoke takes the group off").isEmpty();
        EventEnvelope miaChanged = events(s, "UserChanged", mia).getLast();
        assertThat(miaChanged.actor()).isEqualTo(s.ownerId());
        assertThat(miaChanged.data().get("snapshot").get("groupIds")).isEmpty();

        call(POST, decide(one, olgaItem), s.owner(), Map.of("decision", "keep"), OK);
        call(POST, decide(one, ownerItem), olgaToken, Map.of("decision", "keep"), OK);
        JsonNode midway = call(GET, one, auditor, null, OK);
        assertThat(midway.get("status").asText()).isEqualTo("open");
        assertCounts(midway, 5, 3, 1, 0, 1);
        assertThat(call(GET, one + "/items?decision=keep", olgaToken, null, OK).get("items")).hasSize(3);
        assertThat(texts(call(GET, one + "/items?decision=revoke", olgaToken, null, OK).get("items"), "itemId")).containsExactly(miaItem);

        call(POST, decide(one, audreyItem), olgaToken, Map.of("decision", "revoke", "comment", "the audit is over"), OK);
        JsonNode closed = call(GET, one, olgaToken, null, OK);
        assertThat(closed.get("status").asText()).isEqualTo("closed");
        assertThat(closed.has("closedAt")).isTrue();
        assertCounts(closed, 5, 3, 2, 0, 0);
        assertThat(closed.get("version").asLong()).isEqualTo(6);
        assertThat(groupsOf(s, audrey)).isEmpty();
        call(POST, one + ":cancel", olgaToken, null, CONFLICT);

        List<EventEnvelope> events = events(s, "AccessReviewChanged", id);
        assertThat(actions(events)).containsExactly("created", "updated", "updated", "updated", "updated", "updated");
        assertThat(events).extracting(e -> e.data().get("version").asLong()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        EventEnvelope created = events.get(0);
        assertThat(created.aggregate()).isEqualTo("access-review");
        assertThat(created.key()).isEqualTo("AccessReviewChanged:" + s.accountId() + ":" + id + ":1");
        assertThat(created.context().accountId()).isEqualTo(s.accountId());
        assertThat(created.actor()).isEqualTo(olga);
        assertThat(created.data().get("snapshot").get("reviewId").asText()).isEqualTo(id);
        assertThat(created.data().get("snapshot").get("counts").get("pending").asInt()).isEqualTo(5);
        assertThat(created.data().get("snapshot").has("item")).isFalse();
        EventEnvelope miaRevoked = events.get(2);
        assertThat(miaRevoked.actor()).isEqualTo(s.ownerId());
        assertThat(miaRevoked.data().get("snapshot").get("item").get("itemId").asText()).isEqualTo(miaItem);
        assertThat(miaRevoked.data().get("snapshot").get("item").get("decision").asText()).isEqualTo("revoke");
        assertThat(miaRevoked.data().get("snapshot").get("counts").get("revoked").asInt()).isEqualTo(1);
        EventEnvelope last = events.getLast();
        assertThat(last.data().get("snapshot").get("status").asText()).isEqualTo("closed");
        assertThat(last.data().get("snapshot").get("counts").get("pending").asInt()).isZero();

        // a closed campaign leaves room for the next; audrey, no auditor anymore, reads none of it
        call(POST, s.reviews(), s.owner(), Map.of("name", "Next"), CREATED);
        assertThat(texts(call(GET, s.reviews(), olgaToken, null, OK).get("items"), "name")).containsExactly("Next", "Who holds what");
        call(GET, s.reviews(), auditor, null, FORBIDDEN);
    }

    @Test
    void theLastActiveOwnerKeepsTheOwnersGroupAndAGroupLeftMeanwhileChangesNothing() {
        Seeded s = newAccount();
        String olga = addUser(s, "olga", List.of("owners"));
        String dana = addUser(s, "dana", List.of("members"));
        String olgaToken = bearer(olga, s.accountId(), List.of("owners"));
        String id = call(POST, s.reviews(), olgaToken, Map.of(), CREATED).get("reviewId").asText();
        String one = s.review(id);
        JsonNode items = items(s, id);
        String olgaItem = itemOf(items, olga, "owners");
        String danaItem = itemOf(items, dana, "members");

        // olga takes the first owner's owners group: she is the account's last active owner then
        call(POST, decide(one, itemOf(items, s.ownerId(), "owners")), olgaToken, Map.of("decision", "revoke"), OK);
        assertThat(groupsOf(s, s.ownerId())).isEmpty();
        call(POST, decide(one, olgaItem), s.owner(), Map.of("decision", "revoke"), FORBIDDEN);
        JsonNode last = call(POST, decide(one, olgaItem), internal(), Map.of("decision", "revoke"), CONFLICT);
        assertThat(last.get("error").asText()).contains("last active owner");
        assertThat(item(items(s, id), olgaItem).get("decision").asText()).as("still pending").isEqualTo("pending");
        assertThat(groupsOf(s, olga)).containsExactly("owners");

        // dana left the members group meanwhile: the revoke is recorded and changes nothing
        call(PATCH, s.users() + "/" + dana, olgaToken, Map.of("groups", List.of("viewers")), OK);
        int danaEvents = events(s, "UserChanged", dana).size();
        JsonNode revoked = call(POST, decide(one, danaItem), olgaToken, Map.of("decision", "revoke"), OK);
        assertThat(revoked.get("decision").asText()).isEqualTo("revoke");
        assertThat(groupsOf(s, dana)).containsExactly("viewers");
        assertThat(events(s, "UserChanged", dana)).hasSize(danaEvents);

        // the internal principal keeps olga's, which closes the campaign
        JsonNode kept = call(POST, decide(one, olgaItem), internal(), Map.of("decision", "keep"), OK);
        assertThat(kept.get("decidedBy").asText()).isEqualTo("svc:console-bff");
        JsonNode closed = call(GET, one, olgaToken, null, OK);
        assertThat(closed.get("status").asText()).isEqualTo("closed");
        assertCounts(closed, 3, 1, 2, 0, 0);
    }

    @Test
    void aCancelledCampaignLeavesItsPendingItemsPendingForGood() {
        Seeded s = newAccount();
        String dana = addUser(s, "dana", List.of("members"));
        String id = call(POST, s.reviews(), s.owner(), Map.of("dueInDays", 1), CREATED).get("reviewId").asText();
        String one = s.review(id);
        String danaItem = itemOf(items(s, id), dana, "members");

        call(POST, one + ":cancel", bearer(dana, s.accountId(), List.of("members")), null, FORBIDDEN);
        call(POST, s.review("arev_nope") + ":cancel", s.owner(), null, NOT_FOUND);
        JsonNode cancelled = call(POST, one + ":cancel", s.owner(), null, OK);
        assertThat(cancelled.get("status").asText()).isEqualTo("cancelled");
        assertThat(cancelled.has("closedAt")).isTrue();
        assertThat(cancelled.get("version").asLong()).isEqualTo(2);
        assertCounts(cancelled, 2, 0, 0, 0, 2);
        call(POST, one + ":cancel", s.owner(), null, CONFLICT);
        assertThat(call(POST, decide(one, danaItem), s.owner(), Map.of("decision", "revoke"), CONFLICT).get("error").asText()).contains("cancelled");

        // past its due date the sweep leaves it as it is
        sweep.sweep(clock.instant().plus(Duration.ofDays(2))).block();
        assertThat(call(GET, one, s.owner(), null, OK)).isEqualTo(cancelled);
        assertThat(texts(items(s, id), "decision")).containsOnly("pending");
        assertThat(groupsOf(s, dana)).containsExactly("members");

        List<EventEnvelope> events = events(s, "AccessReviewChanged", id);
        assertThat(actions(events)).containsExactly("created", "updated");
        assertThat(events.getLast().actor()).isEqualTo(s.ownerId());
        assertThat(events.getLast().data().get("snapshot").get("status").asText()).isEqualTo("cancelled");
        call(POST, s.reviews(), s.owner(), Map.of(), CREATED);
    }

    @Test
    void theDueDateExpiresWhatIsLeftButTheLastOwnersAccess() {
        Seeded s = newAccount();
        String dana = addUser(s, "dana", List.of("members"));
        String mia = addUser(s, "mia", List.of("viewers"));
        String id = call(POST, s.reviews(), s.owner(), Map.of("dueInDays", 1), CREATED).get("reviewId").asText();
        String one = s.review(id);
        JsonNode items = items(s, id);
        String ownerItem = itemOf(items, s.ownerId(), "owners");
        String danaItem = itemOf(items, dana, "members");
        call(POST, decide(one, itemOf(items, mia, "viewers")), s.owner(), Map.of("decision", "keep"), OK);
        Instant now = clock.instant();

        sweep.sweep(now.plus(Duration.ofHours(23))).block();
        assertThat(call(GET, one, s.owner(), null, OK).get("status").asText()).as("not due yet").isEqualTo("open");

        Instant due = now.plus(Duration.ofDays(1)).plusSeconds(60);
        AccessReviewSweep.Result result = sweep.sweep(due).block();
        assertThat(result).isNotNull();
        assertThat(result.closed()).isPositive();
        assertThat(result.expired()).isPositive();
        JsonNode closed = call(GET, one, s.owner(), null, OK);
        assertThat(closed.get("status").asText()).isEqualTo("closed");
        assertThat(Instant.parse(closed.get("closedAt").asText())).isEqualTo(due);
        assertCounts(closed, 3, 2, 0, 1, 0);
        assertThat(closed.get("version").asLong()).isEqualTo(3);

        JsonNode after = items(s, id);
        JsonNode danaRow = item(after, danaItem);
        assertThat(danaRow.get("decision").asText()).isEqualTo("expired");
        assertThat(danaRow.get("decidedBy").asText()).isEqualTo(SYSTEM);
        assertThat(Instant.parse(danaRow.get("decidedAt").asText())).isEqualTo(due);
        assertThat(groupsOf(s, dana)).as("unreviewed access expires").isEmpty();
        assertThat(events(s, "UserChanged", dana).getLast().actor()).isEqualTo(SYSTEM);
        JsonNode ownerRow = item(after, ownerItem);
        assertThat(ownerRow.get("decision").asText()).as("the last active owner keeps the owners group").isEqualTo("keep");
        assertThat(ownerRow.get("decidedBy").asText()).isEqualTo(SYSTEM);
        assertThat(ownerRow.get("comment").asText()).contains("last active owner");
        assertThat(groupsOf(s, s.ownerId())).containsExactly("owners");
        assertThat(groupsOf(s, mia)).containsExactly("viewers");

        List<EventEnvelope> events = events(s, "AccessReviewChanged", id);
        assertThat(actions(events)).containsExactly("created", "updated", "expired");
        EventEnvelope expired = events.getLast();
        assertThat(expired.actor()).isEqualTo(SYSTEM);
        assertThat(expired.key()).isEqualTo("AccessReviewChanged:" + s.accountId() + ":" + id + ":3");
        assertThat(expired.data().get("snapshot").get("status").asText()).isEqualTo("closed");
        assertThat(expired.data().get("snapshot").get("counts").get("expired").asInt()).isEqualTo(1);

        // a later sweep has nothing more to do with it, and the decisions are over
        sweep.sweep(due.plusSeconds(60)).block();
        assertThat(call(GET, one, s.owner(), null, OK)).isEqualTo(closed);
        assertThat(events(s, "AccessReviewChanged", id)).hasSize(3);
        call(POST, decide(one, danaItem), s.owner(), Map.of("decision", "keep"), CONFLICT);
    }

    @Test
    void pastItsDueDateACampaignTakesNoDecisionAndNoCancel() {
        Seeded s = newAccount();
        String dana = addUser(s, "dana", List.of("members"));
        String id = call(POST, s.reviews(), s.owner(), Map.of("dueInDays", 1), CREATED).get("reviewId").asText();
        String danaItem = itemOf(items(s, id), dana, "members");
        // as if it started two days ago: due yesterday, and no sweep since
        AccessReview current = reviewStore.find(AccountScope.of(s.accountId(), "global", "test"), id).block();
        assertThat(current).isNotNull();
        Duration back = Duration.ofDays(2);
        assertThat(reviewStore.update(new AccessReview(id, current.accountId(), current.name(), current.status(), current.trigger(), current.startedBy(),
                current.startedAt().minus(back), current.dueAt().minus(back), null, current.counts(), current.version() + 1), current.version()).block())
                .isNotNull();

        JsonNode late = call(POST, decide(s.review(id), danaItem), s.owner(), Map.of("decision", "keep"), CONFLICT);
        assertThat(late.get("error").asText()).contains("was due at");
        assertThat(call(POST, s.review(id) + ":cancel", s.owner(), null, CONFLICT).get("error").asText()).contains("was due at");
        assertThat(item(items(s, id), danaItem).get("decision").asText()).isEqualTo("pending");
        assertThat(groupsOf(s, dana)).containsExactly("members");

        sweep.sweep(clock.instant()).block();
        assertThat(call(GET, s.review(id), s.owner(), null, OK).get("status").asText()).isEqualTo("closed");
        assertThat(item(items(s, id), danaItem).get("decision").asText()).isEqualTo("expired");
        assertThat(groupsOf(s, dana)).isEmpty();
    }

    @Test
    void quarterlyCampaignsStartOnTheirOwn() {
        Instant now = clock.instant();
        Seeded old = newAccount();
        String dana = addUser(old, "dana", List.of("members"));
        backdate(old, Duration.ofDays(91));
        Seeded young = newAccount();
        Seeded resting = newAccount();
        backdate(resting, Duration.ofDays(91));
        call(PATCH, resting.base(), internal(), Map.of("status", "suspended"), OK);
        Seeded quiet = newAccount();
        backdate(quiet, Duration.ofDays(91));

        // with scheduled campaigns off, the sweep starts none
        new AccessReviewSweep(reviewStore, accountStore, campaigns, false, Duration.ofDays(90), 14).sweep(now).block();
        assertThat(call(GET, quiet.reviews(), internal(), null, OK).get("items")).isEmpty();

        sweep.sweep(now).block();
        JsonNode started = call(GET, old.reviews(), old.owner(), null, OK).get("items");
        assertThat(started).hasSize(1);
        JsonNode scheduled = started.get(0);
        String id = scheduled.get("reviewId").asText();
        assertThat(scheduled.get("trigger").asText()).isEqualTo("scheduled");
        assertThat(scheduled.get("startedBy").asText()).isEqualTo(SYSTEM);
        assertThat(scheduled.get("status").asText()).isEqualTo("open");
        assertThat(scheduled.get("name").asText()).startsWith("Quarterly access review ");
        assertThat(Instant.parse(scheduled.get("startedAt").asText())).isEqualTo(now);
        assertThat(Instant.parse(scheduled.get("dueAt").asText())).isEqualTo(now.plus(Duration.ofDays(14)));
        assertCounts(scheduled, 2, 0, 0, 0, 2);
        assertThat(texts(items(old, id), "userId")).containsExactlyInAnyOrder(old.ownerId(), dana);
        EventEnvelope created = events(old, "AccessReviewChanged", id).getFirst();
        assertThat(created.actor()).isEqualTo(SYSTEM);
        assertThat(created.data().get("action").asText()).isEqualTo("created");
        assertThat(call(GET, young.reviews(), young.owner(), null, OK).get("items")).as("created less than a cadence ago").isEmpty();
        assertThat(call(GET, resting.reviews(), internal(), null, OK).get("items")).as("not active").isEmpty();
        assertThat(call(GET, quiet.reviews(), internal(), null, OK).get("items")).hasSize(1);

        // one open at a time, and the cadence counts from the last campaign's start
        sweep.sweep(now).block();
        assertThat(call(GET, old.reviews(), old.owner(), null, OK).get("items")).hasSize(1);
        call(POST, old.review(id) + ":cancel", old.owner(), null, OK);
        sweep.sweep(now).block();
        assertThat(call(GET, old.reviews(), old.owner(), null, OK).get("items")).as("the last one started now").hasSize(1);
        AccountScope scope = AccountScope.of(old.accountId(), "global", "test");
        AccessReview cancelled = reviewStore.find(scope, id).block();
        assertThat(cancelled).isNotNull();
        Duration back = Duration.ofDays(91);
        assertThat(reviewStore.update(new AccessReview(id, cancelled.accountId(), cancelled.name(), cancelled.status(), cancelled.trigger(), cancelled.startedBy(),
                cancelled.startedAt().minus(back), cancelled.dueAt().minus(back), cancelled.closedAt().minus(back), cancelled.counts(), cancelled.version() + 1),
                cancelled.version()).block()).isNotNull();
        sweep.sweep(now).block();
        JsonNode both = call(GET, old.reviews(), old.owner(), null, OK).get("items");
        assertThat(both).hasSize(2);
        assertThat(both.get(1).get("reviewId").asText()).isEqualTo(id);
        assertThat(both.get(0).get("trigger").asText()).isEqualTo("scheduled");
        assertThat(both.get(0).get("status").asText()).isEqualTo("open");
    }

    @Test
    void twoStartsAtOnceOpenOneCampaign() {
        Seeded s = newAccount();
        addUser(s, "dana", List.of("members"));
        AccountScope scope = AccountScope.of(s.accountId(), "global", s.ownerId());
        Instant now = clock.instant();
        List<Signal<AccessReview>> outcomes = Flux.merge(
                        Mono.defer(() -> campaigns.start(scope, "A", 14, AccessReview.Trigger.manual, s.ownerId(), now)).subscribeOn(Schedulers.parallel())
                                .materialize(),
                        Mono.defer(() -> campaigns.start(scope, "B", 14, AccessReview.Trigger.manual, s.ownerId(), now)).subscribeOn(Schedulers.parallel())
                                .materialize())
                .collectList().block();
        assertThat(outcomes).isNotNull().hasSize(2);
        List<AccessReview> started = outcomes.stream().filter(Signal::isOnNext).map(Signal::get).toList();
        List<Throwable> refused = outcomes.stream().filter(Signal::isOnError).map(Signal::getThrowable).toList();
        assertThat(started).hasSize(1);
        assertThat(refused).singleElement().isInstanceOf(ConflictException.class);
        assertThat(((ConflictException) refused.get(0)).detail()).isEqualTo(Map.of("reviewId", started.get(0).reviewId()));
        assertThat(texts(call(GET, s.reviews(), s.owner(), null, OK).get("items"), "reviewId")).containsExactly(started.get(0).reviewId());
        assertThat(items(s, started.get(0).reviewId())).hasSize(2);
    }

    @Test
    void anotherAccountsCampaignIsNotFoundAndOnlyAnActiveAccountStartsOne() {
        Seeded a = newAccount();
        Seeded b = newAccount();
        String id = call(POST, a.reviews(), a.owner(), Map.of(), CREATED).get("reviewId").asText();
        String item = itemOf(items(a, id), a.ownerId(), "owners");
        call(GET, a.reviews(), b.owner(), null, NOT_FOUND);
        call(GET, b.review(id), b.owner(), null, NOT_FOUND);
        call(GET, b.review(id) + "/items", b.owner(), null, NOT_FOUND);
        call(POST, decide(b.review(id), item), b.owner(), Map.of("decision", "revoke"), NOT_FOUND);
        call(POST, b.review(id) + ":cancel", b.owner(), null, NOT_FOUND);
        call(GET, a.review("arev_nope"), a.owner(), null, NOT_FOUND);
        call(GET, a.review("arev_nope") + "/items", a.owner(), null, NOT_FOUND);
        call(POST, "/v1/accounts/999999999999/access-reviews", internal(), Map.of(), NOT_FOUND);

        call(PATCH, b.base(), internal(), Map.of("status", "suspended"), OK);
        assertThat(call(POST, b.reviews(), internal(), Map.of(), CONFLICT).get("error").asText()).contains("suspended");
    }

    @Test
    void closingTheAccountCancelsItsOpenCampaignSoTheDueDateTakesNothing() {
        Seeded s = newAccount();
        String dana = addUser(s, "dana", List.of("members"));
        String id = call(POST, s.reviews(), s.owner(), Map.of("dueInDays", 1), CREATED).get("reviewId").asText();

        call(POST, s.base() + ":close", internal(), Map.of("reason", "the team moved to another account"), ACCEPTED);
        JsonNode review = call(GET, s.review(id), internal(), null, OK);
        assertThat(review.get("status").asText()).isEqualTo("cancelled");
        assertCounts(review, 2, 0, 0, 0, 2);
        List<EventEnvelope> events = events(s, "AccessReviewChanged", id);
        assertThat(actions(events)).containsExactly("created", "updated");
        assertThat(events.getLast().data().get("snapshot").get("status").asText()).isEqualTo("cancelled");

        sweep.sweep(clock.instant().plus(Duration.ofDays(2))).block();
        assertThat(texts(items(s, id), "decision")).containsOnly("pending");
        assertThat(groupsOf(s, dana)).containsExactly("members");
        call(POST, s.base() + ":close", internal(), Map.of("reason", "again"), CONFLICT);
        assertThat(actions(events(s, "AccessReviewChanged", id))).as("a second closure finds nothing open").containsExactly("created", "updated");
    }
}
