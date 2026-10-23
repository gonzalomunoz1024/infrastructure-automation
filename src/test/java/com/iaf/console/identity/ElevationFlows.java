package com.iaf.console.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.identity.elevations.application.ElevationExpiry;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.outbox.OutboxPort;

/**
 * Just-in-time elevation and break-glass access end to end over HTTP, with the expiry sweep run at
 * chosen instants: the same tests run on the in-memory stores and on MongoDB. As the tests share
 * their context, each reads its own accounts and elevations only.
 */
abstract class ElevationFlows extends WithService {

    static final String SYSTEM = "svc:console-identity-service";
    static final String RULE = "rule:operators-admin-two-hours";

    @Autowired
    OutboxPort outbox;
    @Autowired
    ElevationExpiry expiry;
    @Autowired
    Clock clock;

    /** An account made for one test: its id, first owner and the owner's email. */
    record Seeded(String accountId, String ownerId, String ownerEmail) {

        String owner() {
            return bearer(ownerId, accountId, List.of("owners"));
        }

        String base() {
            return "/v1/accounts/" + accountId;
        }

        String elevations() {
            return base() + "/elevations";
        }

        String elevation(String elevationId) {
            return elevations() + "/" + elevationId;
        }

        String users() {
            return base() + "/users";
        }

        String any(String kind) {
            return "wfrn:wf:iam::" + accountId + ":" + kind + "/*";
        }
    }

    record Person(String userId, String email, String token) {
    }

    Seeded newAccount() {
        int n = next();
        String email = "owner-e" + n + "-" + RUN + "@example.com";
        JsonNode account = call(POST, "/v1/accounts", internal(), Map.of("name", "Elevated " + n + " " + RUN, "owningTeam", "team-e" + n + "-" + RUN,
                "ownerEmail", email), CREATED);
        String accountId = account.get("accountId").asText();
        JsonNode users = call(GET, "/v1/accounts/" + accountId + "/users", internal(), null, OK);
        return new Seeded(accountId, users.get("items").get(0).get("userId").asText(), email);
    }

    Person addUser(Seeded s, String shortName, String group) {
        String email = shortName + "-" + next() + "-" + RUN + "@example.com";
        JsonNode user = call(POST, s.users(), s.owner(), Map.of("email", email, "name", shortName, "groups", List.of(group)), CREATED);
        String userId = user.get("userId").asText();
        return new Person(userId, email, bearer(userId, s.accountId(), List.of(group)));
    }

    JsonNode decide(Seeded s, String userId, String action, String resource) {
        return call(POST, "/v1/authorize", internal(), Map.of("principal", Map.of("userId", userId, "accountId", s.accountId()), "checks",
                List.of(Map.of("action", action, "resource", resource))), OK).get("decisions").get(0);
    }

    List<EventEnvelope> events(Seeded s, String elevationId) {
        List<EventEnvelope> events = outbox.after(0, 100_000).filter(e -> s.accountId().equals(e.partitionKey())).map(OutboxEntry::event)
                .filter(e -> "ElevationChanged".equals(e.type()) && elevationId.equals(e.aggregateId())).collectList().block();
        assertThat(events).isNotNull();
        return events;
    }

    static List<String> actions(List<EventEnvelope> events) {
        return events.stream().map(e -> e.data().get("action").asText()).toList();
    }

    @Test
    void theRulesAreServedToAnyoneSignedIn() {
        Seeded s = newAccount();
        JsonNode rules = call(GET, "/v1/elevation-rules", s.owner(), null, OK);
        assertThat(rules.get("defaultMinutes").asInt()).isEqualTo(60);
        assertThat(rules.get("maxMinutes").asInt()).isEqualTo(480);
        assertThat(rules.get("pendingFor").asText()).isEqualTo("PT24H");
        assertThat(rules.get("breakGlassMaxMinutes").asInt()).isEqualTo(240);
        JsonNode rule = rules.get("items").get(0);
        assertThat(rule.get("name").asText()).isEqualTo("operators-admin-two-hours");
        assertThat(strings(rule.get("roles"))).containsExactly("account-admin");
        assertThat(strings(rule.get("requesterRoles"))).containsExactly("operator");
        assertThat(rule.get("maxMinutes").asInt()).isEqualTo(120);
        web.get().uri("/v1/elevation-rules").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anOperatorIsAdminForTwoHoursByRuleAndEndsItEarly() {
        Seeded s = newAccount();
        Person dana = addUser(s, "dana", "members");
        String users = s.any("user");
        assertThat(decide(s, dana.userId(), "iam:create", users).get("allowed").asBoolean()).isFalse();

        JsonNode e = call(POST, s.elevations(), dana.token(), Map.of("role", "admin", "reason", "rotate the production keys", "durationMinutes", 120), CREATED);
        String id = e.get("elevationId").asText();
        assertThat(id).startsWith("elev_");
        assertThat(e.get("accountId").asText()).isEqualTo(s.accountId());
        assertThat(e.get("kind").asText()).isEqualTo("elevation");
        assertThat(e.get("role").asText()).as("admin reads as account-admin").isEqualTo("account-admin");
        assertThat(e.get("status").asText()).isEqualTo("active");
        assertThat(e.get("decidedBy").asText()).isEqualTo(RULE);
        assertThat(e.get("user").get("userId").asText()).isEqualTo(dana.userId());
        assertThat(e.get("user").get("email").asText()).isEqualTo(dana.email());
        assertThat(e.get("requestedBy").asText()).isEqualTo(dana.userId());
        assertThat(e.get("durationMinutes").asInt()).isEqualTo(120);
        assertThat(e.has("approvers")).isFalse();
        assertThat(e.has("version")).isFalse();
        Instant startsAt = Instant.parse(e.get("startsAt").asText());
        assertThat(Instant.parse(e.get("until").asText())).isEqualTo(startsAt.plus(Duration.ofMinutes(120)));

        // the role decides for her now, marked as held for a while
        JsonNode allowed = decide(s, dana.userId(), "iam:create", users);
        assertThat(allowed.get("allowed").asBoolean()).isTrue();
        assertThat(allowed.get("role").asText()).isEqualTo("account-admin");
        assertThat(allowed.get("elevated").asBoolean()).isTrue();
        assertThat(decide(s, dana.userId(), "compute:restart", "wfrn:wf:compute:global:" + s.accountId() + ":vm/vm-1").has("elevated"))
                .as("what her own group allows is not elevated").isFalse();

        // sign-in lists it with the role
        JsonNode signedIn = call(POST, "/v1/principals:resolve", internal(), Map.of("issuer", "https://idp.example.com/" + RUN, "subject", "dana-" + id,
                "email", dana.email()), OK);
        JsonNode membership = signedIn.get("accounts").get(0);
        assertThat(strings(membership.get("roles"))).containsExactlyInAnyOrder("operator", "account-admin");
        assertThat(strings(membership.get("groups"))).containsExactly("members");
        JsonNode held = membership.get("elevations").get(0);
        assertThat(held.get("elevationId").asText()).isEqualTo(id);
        assertThat(held.get("role").asText()).isEqualTo("account-admin");
        assertThat(held.get("kind").asText()).isEqualTo("elevation");
        assertThat(held.get("until").asText()).isEqualTo(e.get("until").asText());

        // one open elevation per person and role
        JsonNode again = call(POST, s.elevations(), dana.token(), Map.of("role", "account-admin", "reason", "again", "durationMinutes", 30), CONFLICT);
        assertThat(again.get("elevationId").asText()).isEqualTo(id);

        // she ends it herself, and it stops deciding at once
        JsonNode ended = call(POST, s.elevation(id) + ":revoke", dana.token(), Map.of("comment", "done early"), OK);
        assertThat(ended.get("status").asText()).isEqualTo("revoked");
        assertThat(ended.get("endedBy").asText()).isEqualTo(dana.userId());
        assertThat(ended.get("endComment").asText()).isEqualTo("done early");
        assertThat(decide(s, dana.userId(), "iam:create", users).get("allowed").asBoolean()).isFalse();
        call(POST, s.elevation(id) + ":revoke", dana.token(), null, CONFLICT);
        JsonNode after = call(POST, "/v1/principals:resolve", internal(), Map.of("issuer", "https://idp.example.com/" + RUN, "subject", "dana-" + id,
                "email", dana.email()), OK).get("accounts").get(0);
        assertThat(strings(after.get("roles"))).containsExactly("operator");
        assertThat(after.has("elevations")).isFalse();

        List<EventEnvelope> events = events(s, id);
        assertThat(actions(events)).containsExactly("created", "revoked");
        assertThat(events.get(0).actor()).isEqualTo(dana.userId());
        assertThat(events.get(0).data().get("snapshot").get("status").asText()).isEqualTo("active");
        assertThat(events.get(0).aggregate()).isEqualTo("elevation");
        assertThat(events.get(1).data().get("version").asLong()).isEqualTo(2);

        // longer than the rule covers: it waits for a person
        JsonNode longer = call(POST, s.elevations(), dana.token(), Map.of("role", "account-admin", "reason", "the migration runs all afternoon",
                "durationMinutes", 240), CREATED);
        assertThat(longer.get("status").asText()).isEqualTo("pending");
        assertThat(strings(longer.get("approvers"))).containsExactly(s.ownerId());
        assertThat(longer.has("until")).isFalse();
    }

    @Test
    void anAskNoRuleCoversWaitsForAnAdminWhoIsNeverThePerson() {
        Seeded s = newAccount();
        Person mia = addUser(s, "mia", "viewers");
        Person dana = addUser(s, "dana", "members");
        Person ada = addUser(s, "ada", "auditors");
        String users = s.any("user");

        JsonNode ask = call(POST, s.elevations(), mia.token(), Map.of("role", "account-admin", "reason", "I approve the quarter's access requests"), CREATED);
        String id = ask.get("elevationId").asText();
        assertThat(ask.get("status").asText()).isEqualTo("pending");
        assertThat(ask.get("durationMinutes").asInt()).as("an hour by default").isEqualTo(60);
        assertThat(strings(ask.get("approvers"))).containsExactly(s.ownerId());
        assertThat(ask.has("decidedBy")).isFalse();
        assertThat(decide(s, mia.userId(), "iam:create", users).get("allowed").asBoolean()).as("pending gives nothing").isFalse();

        // an operator may not decide it, and an admin never decides their own
        JsonNode refused = call(POST, s.elevation(id) + ":decide", dana.token(), Map.of("decision", "approve"), FORBIDDEN).get("denied");
        assertThat(refused.get("action").asText()).isEqualTo("elevation:decide");
        assertThat(refused.get("suggestedRole").asText()).isEqualTo("account-admin");
        JsonNode own = call(POST, s.elevations(), s.owner(), Map.of("role", "auditor", "reason", "I read the trail as auditors see it"), CREATED);
        assertThat(own.get("status").asText()).isEqualTo("pending");
        assertThat(own.has("approvers")).as("the owner is the only admin, and not their own approver").isFalse();
        JsonNode self = call(POST, s.elevation(own.get("elevationId").asText()) + ":decide", s.owner(), Map.of("decision", "approve"), FORBIDDEN);
        assertThat(self.get("denied").get("reason").asText()).isEqualTo("nobody decides their own elevation");
        call(POST, s.elevation(id) + ":decide", s.owner(), Map.of("decision", "maybe"), BAD_REQUEST);

        assertThat(texts(call(GET, s.elevations(), mia.token(), null, OK).get("items"), "elevationId")).as("a viewer reads her own").containsExactly(id);

        // the owner approves: it starts now
        JsonNode approved = call(POST, s.elevation(id) + ":decide", s.owner(), Map.of("decision", "approve", "comment", "for this quarter's queue"), OK);
        assertThat(approved.get("status").asText()).isEqualTo("active");
        assertThat(approved.get("decidedBy").asText()).isEqualTo(s.ownerId());
        assertThat(approved.get("comment").asText()).isEqualTo("for this quarter's queue");
        assertThat(approved.has("approvers")).isFalse();
        Instant startsAt = Instant.parse(approved.get("startsAt").asText());
        assertThat(Instant.parse(approved.get("until").asText())).isEqualTo(startsAt.plus(Duration.ofMinutes(60)));
        JsonNode now = decide(s, mia.userId(), "iam:create", users);
        assertThat(now.get("allowed").asBoolean()).isTrue();
        assertThat(now.get("elevated").asBoolean()).isTrue();
        call(POST, s.elevation(id) + ":decide", s.owner(), Map.of("decision", "reject"), CONFLICT);

        // a person sees their own; admins and auditors the account's
        assertThat(texts(call(GET, s.elevations(), mia.token(), null, OK).get("items"), "elevationId"))
                .as("as account-admin for now, she reads the account's").containsExactly(own.get("elevationId").asText(), id);
        assertThat(call(GET, s.elevations(), dana.token(), null, OK).get("items")).isEmpty();
        assertThat(texts(call(GET, s.elevations(), ada.token(), null, OK).get("items"), "elevationId"))
                .as("the newest first").containsExactly(own.get("elevationId").asText(), id);
        assertThat(texts(call(GET, s.elevations() + "?userId=" + mia.userId(), s.owner(), null, OK).get("items"), "elevationId")).containsExactly(id);
        assertThat(texts(call(GET, s.elevations() + "?status=pending", s.owner(), null, OK).get("items"), "elevationId"))
                .containsExactly(own.get("elevationId").asText());
        call(GET, s.elevations() + "?status=granted", s.owner(), null, BAD_REQUEST);
        call(GET, s.elevations() + "?userId=" + mia.userId(), dana.token(), null, FORBIDDEN);
        assertThat(call(GET, s.elevation(id), mia.token(), null, OK).get("status").asText()).isEqualTo("active");
        assertThat(call(GET, s.elevation(id), ada.token(), null, OK).get("status").asText()).isEqualTo("active");
        assertThat(call(GET, s.elevation(id), dana.token(), null, FORBIDDEN).get("denied").get("action").asText()).isEqualTo("elevation:get");
        call(GET, s.elevation("elev_01jbx9r2m4k9d3c8f5r6t1w0yg"), s.owner(), null, NOT_FOUND);
        Seeded other = newAccount();
        call(GET, other.elevation(id), other.owner(), null, NOT_FOUND);

        // an operator may not revoke another's; an admin may
        assertThat(call(POST, s.elevation(id) + ":revoke", dana.token(), null, FORBIDDEN).get("denied").get("action").asText()).isEqualTo("elevation:revoke");
        JsonNode revoked = call(POST, s.elevation(id) + ":revoke", s.owner(), Map.of("comment", "the queue is empty"), OK);
        assertThat(revoked.get("status").asText()).isEqualTo("revoked");
        assertThat(revoked.get("endedBy").asText()).isEqualTo(s.ownerId());
        assertThat(decide(s, mia.userId(), "iam:create", users).get("allowed").asBoolean()).isFalse();
        assertThat(actions(events(s, id))).containsExactly("created", "approved", "revoked");

        // a pending one the person withdraws is cancelled; a rejected one stays rejected
        String auditorAsk = own.get("elevationId").asText();
        JsonNode cancelled = call(POST, s.elevation(auditorAsk) + ":revoke", s.owner(), null, OK);
        assertThat(cancelled.get("status").asText()).isEqualTo("cancelled");
        assertThat(actions(events(s, auditorAsk))).containsExactly("created", "cancelled");
        JsonNode second = call(POST, s.elevations(), mia.token(), Map.of("role", "operator", "reason", "I restart the batch machines"), CREATED);
        JsonNode rejected = call(POST, s.elevation(second.get("elevationId").asText()) + ":decide", s.owner(), Map.of("decision", "reject", "comment",
                "ask the owning team"), OK);
        assertThat(rejected.get("status").asText()).isEqualTo("rejected");
        assertThat(rejected.has("until")).isFalse();
        call(POST, s.elevation(second.get("elevationId").asText()) + ":revoke", mia.token(), null, CONFLICT);
    }

    @Test
    void anAskNobodyDecidesExpiresAndAnElevationEndsAtItsUntil() {
        Seeded s = newAccount();
        Person mia = addUser(s, "mia", "viewers");
        Person dana = addUser(s, "dana", "members");
        String users = s.any("user");
        String pending = call(POST, s.elevations(), mia.token(), Map.of("role", "operator", "reason", "the release needs a hand"), CREATED)
                .get("elevationId").asText();
        JsonNode byRule = call(POST, s.elevations(), dana.token(), Map.of("role", "account-admin", "reason", "the certificate rotates tonight"), CREATED);
        String active = byRule.get("elevationId").asText();
        assertThat(byRule.get("status").asText()).isEqualTo("active");
        assertThat(decide(s, dana.userId(), "iam:create", users).get("allowed").asBoolean()).isTrue();

        // an hour later the elevation is over; the ask still waits
        Instant now = clock.instant();
        expiry.sweep(now.plus(Duration.ofMinutes(61))).block();
        JsonNode over = call(GET, s.elevation(active), s.owner(), null, OK);
        assertThat(over.get("status").asText()).isEqualTo("expired");
        assertThat(decide(s, dana.userId(), "iam:create", users).get("allowed").asBoolean()).isFalse();
        assertThat(actions(events(s, active))).containsExactly("created", "expired");
        assertThat(events(s, active).get(1).actor()).isEqualTo(SYSTEM);
        assertThat(call(GET, s.elevation(pending), s.owner(), null, OK).get("status").asText()).isEqualTo("pending");
        call(POST, s.elevation(active) + ":revoke", s.owner(), null, CONFLICT);

        // a day later nobody decided the ask: it expires, and can no longer be decided
        expiry.sweep(now.plus(Duration.ofHours(24)).plusSeconds(60)).block();
        JsonNode stale = call(GET, s.elevation(pending), s.owner(), null, OK);
        assertThat(stale.get("status").asText()).isEqualTo("expired");
        assertThat(stale.has("approvers")).isFalse();
        assertThat(actions(events(s, pending))).containsExactly("created", "expired");
        call(POST, s.elevation(pending) + ":decide", s.owner(), Map.of("decision", "approve"), CONFLICT);
        expiry.sweep(now.plus(Duration.ofHours(25))).block();
        assertThat(actions(events(s, pending))).as("a sweep stores an expiry once").containsExactly("created", "expired");

        // what has expired no longer counts as open: the same ask can be made again
        assertThat(call(POST, s.elevations(), dana.token(), Map.of("role", "account-admin", "reason", "the rotation failed"), CREATED).get("status")
                .asText()).isEqualTo("active");
    }

    @Test
    void breakGlassGivesSomeoneOutsideTheAccountSupportAccessOnItsTrail() {
        Seeded s = newAccount();
        Seeded support = newAccount();
        String bg = s.base() + "/break-glass";
        String sam = addUser(support, "sam", "members").userId();
        String lead = support.ownerId();
        String vm = "wfrn:wf:compute:global:" + s.accountId() + ":vm/vm-1";
        assertThat(decide(s, sam, "compute:delete", vm).get("reason").asText()).isEqualTo("not a member of the account");

        call(POST, bg, s.owner(), Map.of("userId", sam, "reason", "INC-1234", "approvedBy", lead), NOT_FOUND);
        call(POST, bg, internal(), Map.of("userId", sam, "reason", "INC-1234", "approvedBy", lead, "durationMinutes", 241), BAD_REQUEST);
        assertThat(call(POST, bg, internal(), Map.of("userId", sam, "reason", "INC-1234", "approvedBy", sam), BAD_REQUEST).get("problems").get(0)
                .get("field").asText()).isEqualTo("approvedBy");
        call(POST, bg, internal(), Map.of("userId", sam, "approvedBy", lead), BAD_REQUEST);
        call(POST, bg, internal(), Map.of("userId", "user_nobody", "reason", "INC-1234", "approvedBy", lead), NOT_FOUND);
        call(POST, bg, internal(), Map.of("userId", sam, "reason", "INC-1234", "approvedBy", "user_nobody"), BAD_REQUEST);
        call(POST, bg, internal(), Map.of("userId", sam, "reason", "INC-1234", "approvedBy", lead, "role", "nonexistent"), BAD_REQUEST);

        JsonNode glass = call(POST, bg, internal(), Map.of("userId", sam, "reason", "INC-1234: the database will not start", "approvedBy", lead,
                "ticket", "INC-1234", "durationMinutes", 90), CREATED);
        String id = glass.get("elevationId").asText();
        assertThat(glass.get("kind").asText()).isEqualTo("break-glass");
        assertThat(glass.get("role").asText()).isEqualTo("account-admin");
        assertThat(glass.get("status").asText()).isEqualTo("active");
        assertThat(glass.get("decidedBy").asText()).isEqualTo(lead);
        assertThat(glass.get("requestedBy").asText()).isEqualTo("svc:console-bff");
        assertThat(glass.get("ticket").asText()).isEqualTo("INC-1234");
        assertThat(Instant.parse(glass.get("until").asText())).isEqualTo(Instant.parse(glass.get("startsAt").asText()).plus(Duration.ofMinutes(90)));
        assertThat(call(POST, bg, internal(), Map.of("userId", sam, "reason", "again", "approvedBy", lead), CONFLICT).get("elevationId").asText())
                .isEqualTo(id);

        // it decides inside the account, though sam is no member of it
        JsonNode allowed = decide(s, sam, "compute:delete", vm);
        assertThat(allowed.get("allowed").asBoolean()).isTrue();
        assertThat(allowed.get("role").asText()).isEqualTo("account-admin");
        assertThat(allowed.get("elevated").asBoolean()).isTrue();

        // sign-in offers the account with no groups; sam reads his own access there
        JsonNode samUser = call(GET, support.users() + "/" + sam, internal(), null, OK);
        JsonNode signedIn = call(POST, "/v1/principals:resolve", internal(), Map.of("issuer", "https://idp.example.com/" + RUN, "subject", "sam-" + id,
                "email", samUser.get("email").asText()), OK);
        assertThat(texts(signedIn.get("accounts"), "accountId")).containsExactlyInAnyOrder(support.accountId(), s.accountId());
        for (JsonNode a : signedIn.get("accounts")) {
            if (a.get("accountId").asText().equals(s.accountId())) {
                assertThat(a.get("groups")).isEmpty();
                assertThat(strings(a.get("roles"))).containsExactly("account-admin");
                assertThat(a.get("elevations").get(0).get("kind").asText()).isEqualTo("break-glass");
            } else {
                assertThat(a.has("elevations")).isFalse();
            }
        }
        String samHere = bearer(sam, s.accountId(), List.of());
        assertThat(texts(call(GET, s.elevations(), samHere, null, OK).get("items"), "elevationId")).containsExactly(id);
        assertThat(texts(call(GET, s.elevations() + "?kind=break-glass", s.owner(), null, OK).get("items"), "elevationId")).containsExactly(id);
        assertThat(call(GET, s.elevations() + "?kind=elevation", s.owner(), null, OK).get("items")).isEmpty();
        call(GET, s.elevations() + "?kind=glass", s.owner(), null, BAD_REQUEST);

        // on the account's own trail
        List<EventEnvelope> events = events(s, id);
        assertThat(actions(events)).containsExactly("created");
        assertThat(events.get(0).actor()).isEqualTo("svc:console-bff");
        assertThat(events.get(0).data().get("snapshot").get("kind").asText()).isEqualTo("break-glass");

        // the account's admin ends it
        JsonNode ended = call(POST, s.elevation(id) + ":revoke", s.owner(), Map.of("comment", "the database is back"), OK);
        assertThat(ended.get("status").asText()).isEqualTo("revoked");
        assertThat(decide(s, sam, "compute:delete", vm).get("reason").asText()).isEqualTo("not a member of the account");
        assertThat(actions(events(s, id))).containsExactly("created", "revoked");
    }

    @Test
    void leavingTheAccountEndsAPersonsElevationsAndClosingItEndsTheRest() {
        Seeded s = newAccount();
        Seeded support = newAccount();
        Person dana = addUser(s, "dana", "members");
        String sam = addUser(support, "sam", "members").userId();
        String danas = call(POST, s.elevations(), dana.token(), Map.of("role", "account-admin", "reason", "the release"), CREATED).get("elevationId").asText();
        String glass = call(POST, s.base() + "/break-glass", internal(), Map.of("userId", sam, "reason", "INC-9", "approvedBy", support.ownerId()), CREATED)
                .get("elevationId").asText();

        send(DELETE, s.users() + "/" + dana.userId(), s.owner(), null, Map.of()).expectStatus().isNoContent();
        JsonNode left = call(GET, s.elevation(danas), s.owner(), null, OK);
        assertThat(left.get("status").asText()).isEqualTo("revoked");
        assertThat(left.get("endedBy").asText()).isEqualTo(SYSTEM);
        assertThat(left.get("endComment").asText()).isEqualTo("the person left the account");
        assertThat(call(GET, s.elevation(glass), s.owner(), null, OK).get("status").asText()).as("break-glass needs no membership").isEqualTo("active");

        call(POST, s.base() + ":close", internal(), Map.of("reason", "the team left"), ACCEPTED);
        JsonNode closed = call(GET, s.elevation(glass), internal(), null, OK);
        assertThat(closed.get("status").asText()).isEqualTo("revoked");
        assertThat(closed.get("endComment").asText()).isEqualTo("the account closed");
        assertThat(actions(events(s, glass))).containsExactly("created", "revoked");
        call(POST, s.base() + "/break-glass", internal(), Map.of("userId", sam, "reason", "INC-10", "approvedBy", support.ownerId()), CONFLICT);
    }

    @Test
    void anAskIsAPersonsForARoleOfTheAccountTheyDoNotHold() {
        Seeded s = newAccount();
        Person dana = addUser(s, "dana", "members");
        Person ada = addUser(s, "ada", "auditors");
        call(POST, s.elevations(), dana.token(), Map.of("role", "nonexistent", "reason", "why not"), BAD_REQUEST);
        assertThat(call(POST, s.elevations(), dana.token(), Map.of("role", "operator", "reason", "I am one"), BAD_REQUEST).get("problems").get(0)
                .get("message").asText()).isEqualTo("you hold operator through a group already");
        call(POST, s.elevations(), dana.token(), Map.of("role", "account-admin"), BAD_REQUEST);
        call(POST, s.elevations(), dana.token(), Map.of("role", "account-admin", "reason", " "), BAD_REQUEST);
        call(POST, s.elevations(), dana.token(), Map.of("role", "account-admin", "reason", "x".repeat(2001)), BAD_REQUEST);
        call(POST, s.elevations(), dana.token(), Map.of("role", "account-admin", "reason", "short", "durationMinutes", 4), BAD_REQUEST);
        call(POST, s.elevations(), dana.token(), Map.of("role", "account-admin", "reason", "long", "durationMinutes", 481), BAD_REQUEST);
        call(POST, s.elevations(), internal(), Map.of("role", "account-admin", "reason", "a service"), BAD_REQUEST);
        JsonNode auditor = call(POST, s.elevations(), ada.token(), Map.of("role", "account-admin", "reason", "an auditor"), FORBIDDEN).get("denied");
        assertThat(auditor.get("action").asText()).isEqualTo("elevation:create");
        Seeded other = newAccount();
        call(POST, other.elevations(), dana.token(), Map.of("role", "account-admin", "reason", "another account"), NOT_FOUND);
    }
}
