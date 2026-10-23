package com.iaf.console.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.NO_CONTENT;
import static org.springframework.http.HttpStatus.OK;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;

import com.iaf.console.identity.accesskeys.application.AccessKeyCommands;
import com.iaf.console.identity.accessrequests.application.AccessRequestExpiry;
import com.iaf.console.identity.accessrequests.domain.AccessRequest;
import com.iaf.console.identity.accessrequests.ports.outbound.AccessRequestStore;
import com.iaf.console.identity.accounts.application.AccountCommands;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.authz.Tars;
import com.iaf.console.identity.demo.DemoSeed;
import com.iaf.console.identity.iam.application.GroupCommands;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.application.PolicyCommands;
import com.iaf.console.identity.iam.application.RoleCommands;
import com.iaf.console.identity.principals.application.PrincipalCommands;
import com.iaf.console.identity.principals.ports.outbound.UserStore;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.outbox.OutboxPort;

/** The flows of the contract, end to end over HTTP: the same tests run on the in-memory stores and on MongoDB. */
abstract class IdentityServiceFlows extends WithService {

    @Autowired
    OutboxPort outbox;
    @Autowired
    AccessKeyCommands accessKeys;
    @Autowired
    AccountCommands accountCommands;
    @Autowired
    AccountStore accountStore;
    @Autowired
    PrincipalCommands principalCommands;
    @Autowired
    UserStore userStore;
    @Autowired
    AccessRequestStore accessRequestStore;
    @Autowired
    AccessRequestExpiry expiry;
    @Autowired
    PolicyCommands policyCommands;
    @Autowired
    RoleCommands roleCommands;
    @Autowired
    GroupCommands groupCommands;
    @Autowired
    IamSupport iamSupport;
    @Autowired
    Clock clock;

    /** An account made for one test: its id, name, first owner and the owner's email. */
    record Seeded(String accountId, String name, String ownerId, String ownerEmail) {

        String owner() {
            return bearer(ownerId, accountId, List.of("owners"));
        }

        String base() {
            return "/v1/accounts/" + accountId;
        }

        String users() {
            return base() + "/users";
        }

        String vm(String id) {
            return "wfrn:wf:compute:global:" + accountId + ":vm/" + id;
        }
    }

    Seeded newAccount() {
        int n = next();
        String email = "owner" + n + "-" + RUN + "@example.com";
        String name = "Account " + n + " " + RUN;
        JsonNode account = call(POST, "/v1/accounts", internal(), Map.of("name", name, "owningTeam", "team-" + n + "-" + RUN, "ownerEmail", email), CREATED);
        String accountId = account.get("accountId").asText();
        JsonNode users = call(GET, "/v1/accounts/" + accountId + "/users", internal(), null, OK);
        assertThat(users.get("items")).hasSize(1);
        return new Seeded(accountId, name, users.get("items").get(0).get("userId").asText(), email);
    }

    String addUser(Seeded s, String shortName, String group) {
        JsonNode user = call(POST, s.users(), s.owner(), Map.of("email", shortName + "-" + next() + "-" + RUN + "@example.com", "name", shortName, "groups",
                List.of(group)), CREATED);
        assertThat(strings(user.get("groups"))).containsExactly(group);
        return user.get("userId").asText();
    }

    static Map<String, Object> check(String action, String resource) {
        return Map.of("action", action, "resource", resource);
    }

    static Map<String, Object> check(String action, String resource, Map<String, Object> context) {
        return Map.of("action", action, "resource", resource, "context", context);
    }

    static Map<String, Object> authorize(String userId, String accountId, List<Map<String, Object>> checks) {
        return Map.of("principal", Map.of("userId", userId, "accountId", accountId), "checks", checks);
    }

    @Test
    void createsAnAccountWithItsManagedIamAndFirstOwner() {
        Seeded s = newAccount();
        JsonNode account = call(GET, s.base(), s.owner(), null, OK);
        assertThat(account.get("accountId").asText()).matches("^[1-9][0-9]{11}$");
        assertThat(account.get("status").asText()).isEqualTo("active");
        assertThat(account.get("version").asLong()).isEqualTo(1);
        assertThat(account.get("defaultRegion").asText()).isEqualTo("global");
        assertThat(account.get("platformServiceAccountId").asText()).isNotBlank();
        assertThat(account.has("token")).isFalse();

        JsonNode roles = call(GET, s.base() + "/roles", s.owner(), null, OK).get("items");
        assertThat(texts(roles, "name")).containsExactlyInAnyOrder("viewer", "operator", "account-admin", "auditor");
        assertThat(roles).allMatch(r -> r.get("builtIn").asBoolean());
        JsonNode groups = call(GET, s.base() + "/groups", s.owner(), null, OK).get("items");
        assertThat(texts(groups, "name")).containsExactlyInAnyOrder("owners", "members", "viewers", "auditors");
        JsonNode owners = groups.get(texts(groups, "name").indexOf("owners"));
        assertThat(owners.get("members").get(0).asText()).isEqualTo(s.ownerId());
        assertThat(owners.get("roles").get(0).asText()).isEqualTo(idOf(roles, "name", "account-admin", "roleId"));
        JsonNode members = groups.get(texts(groups, "name").indexOf("members"));
        assertThat(members.get("idpGroups").get(0).asText()).isEqualTo(account.get("owningTeam").asText());
        JsonNode policies = call(GET, s.base() + "/policies", s.owner(), null, OK).get("items");
        assertThat(texts(policies, "name")).containsExactlyInAnyOrder("managed-viewer", "managed-operator", "managed-account-admin", "managed-auditor");
        JsonNode users = call(GET, s.users(), s.owner(), null, OK).get("items");
        assertThat(users).hasSize(1);
        assertThat(users.get(0).get("email").asText()).isEqualTo(s.ownerEmail());
        assertThat(users.get(0).get("groups").get(0).asText()).isEqualTo("owners");
        assertThat(users.get(0).get("status").asText()).isEqualTo("active");

        JsonNode credentials = call(GET, s.base() + "/platform-credentials", internal(), null, OK);
        assertThat(credentials.get("source").asText()).isEqualTo("local");
        assertThat(credentials.get("token").asText()).isNotBlank();
        assertThat(credentials.get("platformServiceAccountId").asText()).isEqualTo(account.get("platformServiceAccountId").asText());
        call(GET, s.base() + "/platform-credentials", s.owner(), null, NOT_FOUND);

        JsonNode mine = call(GET, "/v1/accounts", s.owner(), null, OK).get("items");
        assertThat(texts(mine, "accountId")).containsExactly(s.accountId());
        JsonNode all = call(GET, "/v1/accounts?limit=200", internal(), null, OK).get("items");
        assertThat(texts(all, "accountId")).contains(s.accountId());

        call(POST, "/v1/accounts", internal(), Map.of("name", s.name(), "owningTeam", "team-x", "ownerEmail", "x@example.com"), CONFLICT);
        call(POST, "/v1/accounts", s.owner(), Map.of("name", "Nope " + RUN, "owningTeam", "team-x", "ownerEmail", "x@example.com"), NOT_FOUND);
        JsonNode bad = call(POST, "/v1/accounts", internal(), Map.of("name", "", "owningTeam", "x", "ownerEmail", "nope"), BAD_REQUEST);
        assertThat(bad.get("problems")).isNotEmpty();
        assertThat(bad.get("error").asText()).isNotBlank();
    }

    @Test
    void anotherAccountIsNotFoundNeverForbidden() {
        Seeded a = newAccount();
        Seeded b = newAccount();
        call(GET, b.base(), a.owner(), null, NOT_FOUND);
        call(GET, b.users(), a.owner(), null, NOT_FOUND);
        call(GET, b.base() + "/quotas", a.owner(), null, NOT_FOUND);
        call(POST, b.base() + "/access-keys", a.owner(), Map.of("userId", a.ownerId()), NOT_FOUND);
        call(POST, "/v1/authorize", a.owner(), authorize(a.ownerId(), b.accountId(), List.of(check("compute:get", b.vm("vm-1")))), BAD_REQUEST);
        call(GET, "/v1/accounts/999999999999", internal(), null, NOT_FOUND);
    }

    @Test
    void aRefusalCarriesTheDenialBlock() {
        Seeded s = newAccount();
        String mia = addUser(s, "mia", "viewers");
        String viewer = bearer(mia, s.accountId(), List.of("viewers"));
        JsonNode denied = call(POST, s.users(), viewer, Map.of("email", "x-" + RUN + "@example.com"), FORBIDDEN);
        assertThat(denied.get("error").asText()).isNotBlank();
        assertThat(denied.get("correlationId").asText()).isNotBlank();
        JsonNode d = denied.get("denied");
        assertThat(d.get("layer").asText()).isEqualTo("console-iam");
        assertThat(d.get("action").asText()).isEqualTo("iam:create");
        assertThat(d.get("resource").asText()).isEqualTo("wfrn:wf:iam::" + s.accountId() + ":user/*");
        assertThat(d.get("reason").asText()).startsWith("no policy allows");
        assertThat(d.get("requestable").asBoolean()).isTrue();
        assertThat(d.get("suggestedRole").asText()).isEqualTo("account-admin");
        call(GET, s.users(), viewer, null, OK);
        call(DELETE, s.users() + "/" + s.ownerId(), viewer, null, FORBIDDEN);
    }

    @Test
    void ifMatchAndIdempotencyKeysGuardTheWrites() {
        Seeded s = newAccount();
        String dana = addUser(s, "dana", "members");
        String user = s.users() + "/" + dana;
        JsonNode renamed = body(send(PATCH, user, s.owner(), Map.of("name", "Dana R"), Map.of("If-Match", "1")).expectStatus().isOk());
        assertThat(renamed.get("name").asText()).isEqualTo("Dana R");
        assertThat(renamed.get("version").asLong()).isEqualTo(2);
        send(PATCH, user, s.owner(), Map.of("name", "Dana S"), Map.of("If-Match", "1")).expectStatus().isEqualTo(CONFLICT);
        send(PATCH, user, s.owner(), Map.of("name", "Dana S"), Map.of("If-Match", "\"2\"")).expectStatus().isOk();
        send(PATCH, user, s.owner(), Map.of("name", "Dana T"), Map.of("If-Match", "abc")).expectStatus().isBadRequest();

        Map<String, Object> body = Map.of("email", "idem-" + RUN + "@example.com", "name", "Idem", "groups", List.of("viewers"));
        String key = "k-" + UUID.randomUUID();
        JsonNode first = body(send(POST, s.users(), s.owner(), body, Map.of("Idempotency-Key", key)).expectStatus().isCreated());
        JsonNode again = body(send(POST, s.users(), s.owner(), body, Map.of("Idempotency-Key", key)).expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replay", "true"));
        assertThat(again.get("userId")).isEqualTo(first.get("userId"));
        send(POST, s.users(), s.owner(), Map.of("email", "other-" + RUN + "@example.com"), Map.of("Idempotency-Key", key)).expectStatus().isEqualTo(CONFLICT);
        assertThat(call(GET, s.users() + "?q=idem-" + RUN, s.owner(), null, OK).get("items")).hasSize(1);

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ids.add(addUser(s, "page" + i, "viewers"));
        }
        JsonNode page1 = call(GET, s.users() + "?limit=3", s.owner(), null, OK);
        assertThat(page1.get("items")).hasSize(3);
        assertThat(page1.get("next").asText()).isNotBlank();
        JsonNode page2 = call(GET, s.users() + "?limit=3&after=" + page1.get("next").asText(), s.owner(), null, OK);
        assertThat(page2.get("items")).hasSize(2);
        assertThat(page2.has("next")).isFalse();
        List<String> seen = new ArrayList<>(texts(page1.get("items"), "userId"));
        seen.addAll(texts(page2.get("items"), "userId"));
        assertThat(seen).doesNotHaveDuplicates().contains(s.ownerId(), dana).containsAll(ids);
    }

    @Test
    void resolveLinksTheIdentityAndAnswersTheAccounts() {
        Seeded s = newAccount();
        String issuer = "https://idp.example.com/" + RUN;
        String resolve = "/v1/principals:resolve";
        JsonNode r = call(POST, resolve, internal(), Map.of("issuer", issuer, "subject", "owner-" + s.accountId(), "email", s.ownerEmail(), "name", "Owner One",
                "groups", List.of("something-unrelated")), OK);
        assertThat(r.get("user").get("userId").asText()).isEqualTo(s.ownerId());
        assertThat(r.get("user").get("issuer").asText()).isEqualTo(issuer);
        assertThat(r.get("user").get("name").asText()).isEqualTo("Owner One");
        assertThat(r.get("accounts")).hasSize(1);
        JsonNode membership = r.get("accounts").get(0);
        assertThat(membership.get("accountId").asText()).isEqualTo(s.accountId());
        assertThat(membership.get("name").asText()).isEqualTo(s.name());
        assertThat(membership.get("groups").get(0).asText()).isEqualTo("owners");
        assertThat(membership.get("roles").get(0).asText()).isEqualTo("account-admin");
        assertThat(membership.get("defaultRegion").asText()).isEqualTo("global");

        JsonNode again = call(POST, resolve, internal(), Map.of("issuer", issuer, "subject", "owner-" + s.accountId()), OK);
        assertThat(again.get("user").get("userId").asText()).isEqualTo(s.ownerId());
        call(POST, resolve, internal(), Map.of("issuer", issuer, "subject", "nobody-" + RUN, "email", "nobody-" + RUN + "@example.com"), NOT_FOUND);
        call(POST, resolve, internal(), Map.of("issuer", issuer, "subject", "other-" + RUN, "email", s.ownerEmail()), CONFLICT);
        call(POST, resolve, s.owner(), Map.of("issuer", issuer, "subject", "x"), NOT_FOUND);
        call(POST, resolve, internal(), Map.of("subject", "x"), BAD_REQUEST);

        String team = call(GET, s.base(), internal(), null, OK).get("owningTeam").asText();
        JsonNode joined = call(POST, resolve, internal(), Map.of("issuer", issuer, "subject", "new-" + s.accountId(), "email", "new-" + s.accountId()
                + "@example.com", "name", "New Person", "groups", List.of(team, "other")), OK);
        assertThat(joined.get("user").get("userId").asText()).startsWith("user_");
        assertThat(joined.get("accounts").get(0).get("groups").get(0).asText()).isEqualTo("members");
        assertThat(joined.get("accounts").get(0).get("roles").get(0).asText()).isEqualTo("operator");
        JsonNode listed = call(GET, s.users() + "?q=new-" + s.accountId(), s.owner(), null, OK).get("items");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("lastSignInAt").asText()).isNotBlank();
    }

    @Test
    void accessKeysAreMintedRotatedAndRevoked() {
        Seeded s = newAccount();
        String mia = addUser(s, "mia", "viewers");
        String viewer = bearer(mia, s.accountId(), List.of("viewers"));
        String keys = s.base() + "/access-keys";

        JsonNode k1 = call(POST, keys, s.owner(), Map.of("userId", s.ownerId(), "description", "laptop"), CREATED);
        assertThat(k1.get("keyId").asText()).startsWith("ck_");
        assertThat(k1.get("secretPart").asText()).hasSize(43);
        assertThat(k1.get("status").asText()).isEqualTo("active");
        assertThat(k1.has("graceUntil")).isFalse();
        JsonNode k2 = call(POST, keys, s.owner(), Map.of("userId", s.ownerId()), CREATED);
        call(POST, keys, s.owner(), Map.of("userId", s.ownerId()), CONFLICT);
        call(POST, keys, viewer, Map.of("userId", s.ownerId()), FORBIDDEN);
        call(POST, keys, s.owner(), Map.of("userId", "usr_nobody"), NOT_FOUND);
        call(POST, keys, s.owner(), Map.of("userId", s.ownerId(), "expiresAt", "2000-01-01T00:00:00Z"), BAD_REQUEST);
        JsonNode own = call(POST, keys, viewer, Map.of("userId", mia, "description", "ci"), CREATED);
        String k1Id = k1.get("keyId").asText();
        String k2Id = k2.get("keyId").asText();

        assertThat(accessKeys.verify(k2Id, k2.get("secretPart").asText()).block()).isNotNull();
        assertThat(accessKeys.verify(k2Id, "wrong").block()).isNull();
        assertThat(accessKeys.verify("ck_nope", "wrong").block()).isNull();

        JsonNode rotated = call(POST, keys + "/" + k1Id + ":rotate", s.owner(), null, CREATED);
        assertThat(rotated.get("keyId").asText()).isEqualTo(k1Id);
        assertThat(rotated.get("secretPart").asText()).isNotEqualTo(k1.get("secretPart").asText());
        assertThat(rotated.get("graceUntil").asText()).isNotBlank();
        assertThat(rotated.get("status").asText()).isEqualTo("rotating");
        assertThat(accessKeys.verify(k1Id, k1.get("secretPart").asText()).block()).as("the old secret works within the grace").isNotNull();
        assertThat(accessKeys.verify(k1Id, rotated.get("secretPart").asText()).block()).isNotNull();

        JsonNode view = call(GET, keys + "/" + k1Id, s.owner(), null, OK);
        assertThat(view.has("secretPart")).isFalse();
        assertThat(view.get("status").asText()).isEqualTo("rotating");
        assertThat(view.get("lastUsedAt").asText()).isNotBlank();
        assertThat(call(GET, keys + "/" + k1Id, viewer, null, OK).has("secretPart")).as("a viewer reads key metadata, never a secret").isFalse();
        call(POST, keys + "/" + k1Id + ":rotate", viewer, null, FORBIDDEN);
        call(GET, keys + "/" + own.get("keyId").asText(), viewer, null, OK);
        assertThat(call(GET, keys + "?userId=" + s.ownerId(), s.owner(), null, OK).get("items")).hasSize(2);
        assertThat(call(GET, keys, viewer, null, OK).get("items")).hasSize(3);

        send(DELETE, keys + "/" + k1Id, s.owner(), null, Map.of()).expectStatus().isNoContent();
        send(DELETE, keys + "/" + k1Id, s.owner(), null, Map.of()).expectStatus().isNoContent();
        assertThat(call(GET, keys + "/" + k1Id, s.owner(), null, OK).get("status").asText()).isEqualTo("revoked");
        assertThat(accessKeys.verify(k1Id, rotated.get("secretPart").asText()).block()).isNull();
        call(POST, keys + "/" + k1Id + ":rotate", s.owner(), null, CONFLICT);
        send(DELETE, keys + "/" + own.get("keyId").asText(), viewer, null, Map.of()).expectStatus().isNoContent();

        JsonNode quota = call(GET, s.base() + "/quotas/access-keys", s.owner(), null, OK);
        assertThat(quota.get("limit").asInt()).isEqualTo(50);
        assertThat(quota.get("used").asInt()).isEqualTo(1);
        assertThat(quota.get("source").asText()).isEqualTo("default");
    }

    @Test
    void quotasReadThroughThePlatformAndTrackRequests() {
        Seeded s = newAccount();
        String mia = addUser(s, "mia", "viewers");
        String viewer = bearer(mia, s.accountId(), List.of("viewers"));
        String quotas = s.base() + "/quotas";

        JsonNode items = call(GET, quotas, s.owner(), null, OK).get("items");
        assertThat(texts(items, "key")).contains("jobs-per-minute", "access-keys", "resources.vm", "resources.database", "resources.image")
                .as("resources.<type> by the platform's type names, not by service or team").doesNotContain("resources.compute", "resources.platform");
        JsonNode jobs = items.get(texts(items, "key").indexOf("jobs-per-minute"));
        assertThat(jobs.get("source").asText()).isEqualTo("platform");
        assertThat(jobs.get("limit").asInt()).isGreaterThan(0);

        call(PUT, quotas + "/jobs-per-minute", s.owner(), Map.of("limit", 120), NOT_FOUND);
        JsonNode set = call(PUT, quotas + "/jobs-per-minute", internal(), Map.of("limit", 120, "burst", 240), OK);
        assertThat(set.get("limit").asInt()).isEqualTo(120);
        assertThat(set.get("burst").asInt()).isEqualTo(240);
        assertThat(set.get("source").asText()).isEqualTo("override");
        JsonNode bucket = call(GET, quotas + "/jobs-per-minute", s.owner(), null, OK);
        assertThat(bucket.get("limit").asInt()).as("the platform's bucket moved too").isEqualTo(120);
        call(PUT, quotas + "/jobs-per-minute", internal(), Map.of("limit", -1), BAD_REQUEST);

        JsonNode accepted = call(POST, quotas + "/resources.vm:request-increase", viewer, Map.of("limit", 200, "reason", "the team needs more test machines"),
                ACCEPTED);
        String requestId = accepted.get("requestId").asText();
        assertThat(requestId).startsWith("qreq_");
        assertThat(accepted.get("status").asText()).isEqualTo("open");
        assertThat(accepted.get("quotaKey").asText()).isEqualTo("resources.vm");
        assertThat(accepted.get("currentLimit").asInt()).isEqualTo(100);
        assertThat(accepted.get("requestedLimit").asInt()).isEqualTo(200);
        assertThat(accepted.get("accountId").asText()).isEqualTo(s.accountId());
        assertThat(accepted.get("requestedBy").asText()).isEqualTo(mia);
        assertThat(accepted.get("requestedAt").asText()).isNotBlank();
        JsonNode pending = call(GET, quotas + "/resources.vm", viewer, null, OK);
        assertThat(pending.get("limit").asInt()).isEqualTo(100);
        assertThat(pending.get("used").asInt()).isZero();
        assertThat(pending.get("pendingRequestId").asText()).isEqualTo(requestId);
        call(POST, quotas + "/resources.vm:request-increase", viewer, Map.of("limit", 300, "reason", "even more test machines"), CONFLICT);
        call(POST, quotas + "/resources.vm:request-increase", viewer, Map.of("limit", 0, "reason", "short"), BAD_REQUEST);
        JsonNode request = call(GET, s.base() + "/quota-requests/" + requestId, s.owner(), null, OK);
        assertThat(request.get("status").asText()).isEqualTo("open");
        assertThat(request.get("requestedBy").asText()).isEqualTo(mia);
        assertThat(request.get("requestedLimit").asInt()).isEqualTo(200);

        JsonNode raised = call(PUT, quotas + "/resources.vm", internal(), Map.of("limit", 200), OK);
        assertThat(raised.get("limit").asInt()).isEqualTo(200);
        assertThat(raised.get("source").asText()).isEqualTo("override");
        assertThat(raised.has("pendingRequestId")).isFalse();
        JsonNode moot = call(GET, s.base() + "/quota-requests/" + requestId, s.owner(), null, OK);
        assertThat(moot.get("status").asText()).as("a limit at or above the ask approves the request").isEqualTo("approved");
        assertThat(moot.get("grantedLimit").asInt()).isEqualTo(200);
        assertThat(moot.has("version")).as("the version is never served").isFalse();

        JsonNode approvedView = call(GET, s.base() + "/quota-requests/" + requestId, viewer, null, OK);
        assertThat(approvedView.get("decidedBy").asText()).isEqualTo("svc:console-bff");
        assertThat(approvedView.get("decidedAt").asText()).isNotBlank();

        // an admin withdraws someone else's open request; a decided one is a 409
        JsonNode second = call(POST, quotas + "/resources.db:request-increase", viewer, Map.of("limit", 5, "reason", "need more databases now"), ACCEPTED);
        String secondId = second.get("requestId").asText();
        send(DELETE, s.base() + "/quota-requests/" + secondId, s.owner(), null, Map.of()).expectStatus().isNoContent();
        assertThat(call(GET, s.base() + "/quota-requests/" + secondId, viewer, null, OK).get("status").asText()).isEqualTo("withdrawn");
        call(DELETE, s.base() + "/quota-requests/" + secondId, s.owner(), null, CONFLICT);
        call(DELETE, s.base() + "/quota-requests/" + requestId, internal(), null, CONFLICT);
        // the requester withdraws their own; an operator may not withdraw another person's
        String dana = addUser(s, "dana", "members");
        String operator = bearer(dana, s.accountId(), List.of("members"));
        JsonNode third = call(POST, quotas + "/resources.net:request-increase", viewer, Map.of("limit", 7, "reason", "need more networks now"), ACCEPTED);
        String thirdId = third.get("requestId").asText();
        JsonNode refused = call(DELETE, s.base() + "/quota-requests/" + thirdId, operator, null, FORBIDDEN);
        assertThat(refused.get("denied").get("action").asText()).isEqualTo("quota:set");
        send(DELETE, s.base() + "/quota-requests/" + thirdId, viewer, null, Map.of()).expectStatus().isNoContent();
        assertThat(call(GET, s.base() + "/quota-requests/" + thirdId, s.owner(), null, OK).get("status").asText()).isEqualTo("withdrawn");
        // a limit set below the ask leaves the request open; the platform team rejects it with a comment
        JsonNode fourth = call(POST, quotas + "/resources.db:request-increase", viewer, Map.of("limit", 500, "reason", "need many more databases"), ACCEPTED);
        String fourthId = fourth.get("requestId").asText();
        call(PUT, quotas + "/resources.db", internal(), Map.of("limit", 50), OK);
        assertThat(call(GET, s.base() + "/quota-requests/" + fourthId, viewer, null, OK).get("status").asText()).isEqualTo("open");
        String decideFourth = s.base() + "/quota-requests/" + fourthId + ":decide";
        call(POST, decideFourth, s.owner(), Map.of("decision", "reject"), NOT_FOUND);
        call(POST, decideFourth, internal(), Map.of("decision", "maybe"), BAD_REQUEST);
        call(POST, decideFourth, internal(), Map.of("decision", "approve", "limit", 0), BAD_REQUEST);
        call(POST, decideFourth, internal(), Map.of("decision", "reject", "comment", "x".repeat(2001)), BAD_REQUEST);
        JsonNode rejected = call(POST, decideFourth, internal(), Map.of("decision", "reject", "comment", "500 databases is more than the region holds"),
                ACCEPTED);
        assertThat(rejected.get("status").asText()).isEqualTo("rejected");
        assertThat(rejected.get("comment").asText()).contains("region");
        assertThat(rejected.get("decidedBy").asText()).isEqualTo("svc:console-bff");
        assertThat(rejected.get("decidedAt").asText()).isNotBlank();
        assertThat(rejected.has("grantedLimit")).isFalse();
        assertThat(call(GET, quotas + "/resources.db", viewer, null, OK).get("limit").asInt()).as("a rejection leaves the limit").isEqualTo(50);
        call(POST, decideFourth, internal(), Map.of("decision", "approve"), CONFLICT);
        // approving grants a limit that may differ from the ask, set as PUT sets it
        JsonNode fifth = call(POST, quotas + "/resources.db:request-increase", viewer, Map.of("limit", 400, "reason", "need many more databases"), ACCEPTED);
        String decideFifth = s.base() + "/quota-requests/" + fifth.get("requestId").asText() + ":decide";
        JsonNode granted = call(POST, decideFifth, internal(), Map.of("decision", "approve", "limit", 300, "comment", "300 for now"), ACCEPTED);
        assertThat(granted.get("status").asText()).isEqualTo("approved");
        assertThat(granted.get("grantedLimit").asInt()).isEqualTo(300);
        assertThat(granted.get("requestedLimit").asInt()).isEqualTo(400);
        assertThat(granted.get("comment").asText()).isEqualTo("300 for now");
        JsonNode db = call(GET, quotas + "/resources.db", viewer, null, OK);
        assertThat(db.get("limit").asInt()).isEqualTo(300);
        assertThat(db.has("pendingRequestId")).isFalse();
        call(POST, decideFifth, internal(), Map.of("decision", "reject"), CONFLICT);
        call(DELETE, s.base() + "/quota-requests/" + fifth.get("requestId").asText(), viewer, null, CONFLICT);
        call(POST, s.base() + "/quota-requests/qreq_nope:decide", internal(), Map.of("decision", "reject"), NOT_FOUND);
        // jobs-per-minute: an approval writes the platform's bucket too, and keeps a burst that still covers the limit
        JsonNode jobsAsk = call(POST, quotas + "/jobs-per-minute:request-increase", viewer, Map.of("limit", 200, "reason", "the nightly batch needs more"),
                ACCEPTED);
        assertThat(jobsAsk.get("currentLimit").asInt()).isEqualTo(120);
        call(POST, s.base() + "/quota-requests/" + jobsAsk.get("requestId").asText() + ":decide", internal(), Map.of("decision", "approve"), ACCEPTED);
        JsonNode jobsNow = call(GET, quotas + "/jobs-per-minute", s.owner(), null, OK);
        assertThat(jobsNow.get("limit").asInt()).as("the platform's bucket moved").isEqualTo(200);
        assertThat(jobsNow.get("burst").asInt()).isEqualTo(240);

        // every change is its own QuotaChanged: the quota as served, the request it was about, one version per change
        List<EventEnvelope> quotaEvents = outbox.after(0, 100_000).filter(e -> s.accountId().equals(e.partitionKey())).map(OutboxEntry::event)
                .filter(e -> "QuotaChanged".equals(e.type())).collectList().block();
        assertThat(quotaEvents).isNotNull();
        assertThat(quotaEvents).extracting(EventEnvelope::key).doesNotHaveDuplicates();
        List<EventEnvelope> dbEvents = quotaEvents.stream().filter(e -> "resources.db".equals(e.aggregateId())).toList();
        assertThat(dbEvents).extracting(e -> e.data().get("action").asText())
                .containsExactly("updated", "updated", "updated", "updated", "rejected", "updated", "approved");
        assertThat(dbEvents).extracting(e -> e.data().get("version").asLong()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(dbEvents.get(0).aggregate()).isEqualTo("quota");
        assertThat(dbEvents.get(2).data().get("snapshot").get("pendingRequestId").asText()).isEqualTo(fourthId);
        assertThat(dbEvents.get(3).data().get("snapshot").has("request")).as("a limit set with no decision carries no request").isFalse();
        JsonNode rejectedSnapshot = dbEvents.get(4).data().get("snapshot");
        assertThat(rejectedSnapshot.get("key").asText()).isEqualTo("resources.db");
        assertThat(rejectedSnapshot.get("limit").asInt()).isEqualTo(50);
        assertThat(rejectedSnapshot.has("pendingRequestId")).isFalse();
        assertThat(rejectedSnapshot.get("request").get("status").asText()).isEqualTo("rejected");
        assertThat(rejectedSnapshot.get("request").get("requestedBy").asText()).isEqualTo(mia);
        assertThat(rejectedSnapshot.get("request").has("version")).isFalse();
        JsonNode approvedSnapshot = dbEvents.get(6).data().get("snapshot");
        assertThat(approvedSnapshot.get("limit").asInt()).isEqualTo(300);
        assertThat(approvedSnapshot.get("request").get("grantedLimit").asInt()).isEqualTo(300);
        assertThat(quotaEvents.stream().filter(e -> "resources.vm".equals(e.aggregateId())).map(e -> e.data().get("action").asText()).toList())
                .containsExactly("updated", "approved");
        assertThat(quotaEvents.stream().filter(e -> "jobs-per-minute".equals(e.aggregateId())).map(e -> e.data().get("action").asText()).toList())
                .containsExactly("updated", "updated", "approved");
        call(GET, quotas + "/nonsense", s.owner(), null, NOT_FOUND);
        call(GET, s.base() + "/quota-requests/qreq_nope", s.owner(), null, NOT_FOUND);
        assertThat(texts(call(GET, quotas, s.owner(), null, OK).get("items"), "key")).contains("resources.vm");
    }

    @Test
    void authorizeAnswersEveryCheckAndTracksTheVersion() {
        Seeded s = newAccount();
        String mia = addUser(s, "mia", "viewers");
        String vm = s.vm("vm-1");
        String users = "wfrn:wf:iam::" + s.accountId() + ":user/*";

        JsonNode admin = call(POST, "/v1/authorize", s.owner(), authorize(s.ownerId(), s.accountId(), List.of(check("compute:delete", vm), check("iam:create", users))),
                OK);
        assertThat(admin.get("decisions")).hasSize(2);
        JsonNode first = admin.get("decisions").get(0);
        assertThat(first.get("allowed").asBoolean()).isTrue();
        assertThat(first.get("role").asText()).isEqualTo("account-admin");
        assertThat(first.get("policyId").asText()).startsWith("pol_");
        assertThat(first.get("statement").asText()).isEqualTo("everything");
        assertThat(admin.get("decisions").get(1).get("allowed").asBoolean()).isTrue();
        long version = admin.get("version").asLong();
        assertThat(version).isGreaterThan(0);

        JsonNode viewer = call(POST, "/v1/authorize", bearer(mia, s.accountId(), List.of("viewers")),
                authorize(mia, s.accountId(), List.of(check("compute:delete", vm), check("compute:get", vm), check("compute:list", s.vm("*")))), OK);
        JsonNode denied = viewer.get("decisions").get(0);
        assertThat(denied.get("allowed").asBoolean()).isFalse();
        assertThat(denied.get("requestable").asBoolean()).isTrue();
        assertThat(denied.get("suggestedRole").asText()).isEqualTo("account-admin");
        assertThat(viewer.get("decisions").get(1).get("role").asText()).isEqualTo("viewer");
        assertThat(viewer.get("decisions").get(2).get("allowed").asBoolean()).isTrue();
        assertThat(viewer.get("version").asLong()).isEqualTo(version);

        addUser(s, "dana", "members");
        JsonNode after = call(POST, "/v1/authorize", s.owner(), authorize(s.ownerId(), s.accountId(), List.of(check("compute:get", vm))), OK);
        assertThat(after.get("version").asLong()).isGreaterThan(version);

        JsonNode service = call(POST, "/v1/authorize", internal(), Map.of("principal", Map.of("accountId", s.accountId(), "service", "console-state-service"),
                "checks", List.of(check("compute:delete", vm))), OK);
        assertThat(service.get("decisions").get(0).get("allowed").asBoolean()).isTrue();
        assertThat(service.get("decisions").get(0).get("reason").asText()).isEqualTo("service principal");
        JsonNode unknown = call(POST, "/v1/authorize", internal(), authorize("usr_nobody", s.accountId(), List.of(check("compute:get", vm))), OK);
        assertThat(unknown.get("decisions").get(0).get("reason").asText()).isEqualTo("not a member of the account");

        call(POST, "/v1/authorize", s.owner(), authorize(s.ownerId(), s.accountId(), List.of(check("nonsense", vm))), BAD_REQUEST);
        call(POST, "/v1/authorize", s.owner(), authorize(s.ownerId(), s.accountId(), List.of(check("compute:get", "arn:aws:ec2"))), BAD_REQUEST);
        call(POST, "/v1/authorize", s.owner(), authorize(s.ownerId(), s.accountId(), List.of()), BAD_REQUEST);
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(check("compute:get", vm));
        }
        JsonNode tooMany = call(POST, "/v1/authorize", s.owner(), authorize(s.ownerId(), s.accountId(), many), BAD_REQUEST);
        assertThat(tooMany.get("problems").get(0).get("field").asText()).isEqualTo("checks");
    }

    @Test
    void bundleIsServedToTheInternalPrincipalAndOpa() throws IOException {
        Seeded s = newAccount();
        EntityExchangeResult<byte[]> first = web.get().uri(BUNDLE_PATH).header(HttpHeaders.AUTHORIZATION, internal()).exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/gzip")
                .expectHeader().exists(HttpHeaders.ETAG)
                .expectBody().returnResult();
        String etag = first.getResponseHeaders().getETag();
        assertThat(etag).matches("^\"[0-9]+\"$");
        Map<String, byte[]> entries = Tars.read(first.getResponseBody());
        assertThat(entries).containsOnlyKeys(".manifest", "console/data.json", "console/authz.rego");
        JsonNode data = JSON.readTree(entries.get("console/data.json"));
        JsonNode account = data.get("accounts").get(s.accountId());
        assertThat(account).isNotNull();
        assertThat(account.get("status").asText()).isEqualTo("active");
        assertThat(account.get("users").get(s.ownerId()).get("groups").get(0).asText()).isEqualTo("owners");
        assertThat(account.get("groups").get("owners").get("roles").get(0).asText()).isEqualTo("account-admin");
        assertThat(account.get("roles").get("account-admin").get("policies")).hasSize(1);
        assertThat(JSON.readTree(entries.get(".manifest")).get("revision").asText()).isEqualTo(etag.replace("\"", ""));

        web.get().uri(BUNDLE_PATH).header(HttpHeaders.AUTHORIZATION, internal()).header(HttpHeaders.IF_NONE_MATCH, etag).exchange()
                .expectStatus().isNotModified();
        addUser(s, "dana", "members");
        web.get().uri(BUNDLE_PATH).header(HttpHeaders.AUTHORIZATION, internal()).header(HttpHeaders.IF_NONE_MATCH, etag).exchange()
                .expectStatus().isOk().expectHeader().value(HttpHeaders.ETAG, v -> assertThat(v).isNotEqualTo(etag));

        web.get().uri(BUNDLE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + BUNDLE_TOKEN).exchange().expectStatus().isOk();
        web.get().uri("/v1/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + BUNDLE_TOKEN).exchange().expectStatus().isUnauthorized();
        web.get().uri(BUNDLE_PATH).header(HttpHeaders.AUTHORIZATION, s.owner()).exchange().expectStatus().isNotFound();
        web.get().uri(BUNDLE_PATH).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void accessRequestsGrantARoleWhenApproved() {
        Seeded s = newAccount();
        String mia = addUser(s, "mia", "viewers");
        String viewer = bearer(mia, s.accountId(), List.of("viewers"));
        String requests = s.base() + "/access-requests";
        Map<String, Object> ask = Map.of("action", "compute:create", "resource", s.vm("*"), "role", "operator", "reason", "I deploy the test machines");

        // without a role the request asks for the managed role the denial suggests
        JsonNode opened = call(POST, requests, viewer, Map.of("action", "compute:create", "resource", s.vm("*"), "reason", "I deploy the test machines"),
                ACCEPTED);
        String id = opened.get("requestId").asText();
        assertThat(id).startsWith("areq_");
        assertThat(opened.get("status").asText()).isEqualTo("pending");
        assertThat(opened.get("role").asText()).isEqualTo("operator");
        assertThat(opened.get("requester").get("userId").asText()).isEqualTo(mia);
        assertThat(opened.get("requester").get("email").asText()).contains("@example.com");
        assertThat(opened.get("approvers").get(0).asText()).isEqualTo(s.ownerId());
        JsonNode duplicate = call(POST, requests, viewer, ask, CONFLICT);
        assertThat(duplicate.get("requestId").asText()).as("409 answers the existing request").isEqualTo(id);
        call(POST, requests, internal(), ask, BAD_REQUEST);
        call(POST, requests, viewer, Map.of("action", "compute:create", "role", "nonexistent", "reason", "I deploy the test machines"), BAD_REQUEST);
        call(POST, requests, viewer, Map.of("action", "compute:get", "resource", s.vm("*"), "reason", "already allowed, no role to ask for"), BAD_REQUEST);
        call(POST, requests, viewer, Map.of("action", "audit:export", "role", "auditor", "reason", "x".repeat(2001)), BAD_REQUEST);
        assertThat(call(GET, requests, viewer, null, OK).get("items")).hasSize(1);
        assertThat(call(GET, requests + "?status=pending", s.owner(), null, OK).get("items")).as("an admin sees the account's requests").hasSize(1);
        assertThat(call(GET, requests + "?status=pending", internal(), null, OK).get("items")).hasSize(1);
        assertThat(call(GET, requests + "?requester=" + mia, s.owner(), null, OK).get("items")).hasSize(1);
        assertThat(call(GET, requests + "?requester=" + s.ownerId(), s.owner(), null, OK).get("items")).isEmpty();
        assertThat(call(GET, requests + "?requester=" + mia, viewer, null, OK).get("items")).hasSize(1);
        call(GET, requests + "?requester=" + s.ownerId(), viewer, null, FORBIDDEN);
        assertThat(call(GET, requests + "?status=approved", s.owner(), null, OK).get("items")).isEmpty();
        call(GET, requests + "?status=weird", viewer, null, BAD_REQUEST);

        call(POST, requests + "/" + id + ":decide", viewer, Map.of("decision", "approve"), FORBIDDEN);
        call(POST, requests + "/" + id + ":decide", s.owner(), Map.of("decision", "maybe"), BAD_REQUEST);
        call(POST, requests + "/" + id + ":decide", s.owner(), Map.of("decision", "approve", "comment", "x".repeat(2001)), BAD_REQUEST);
        JsonNode approved = call(POST, requests + "/" + id + ":decide", s.owner(), Map.of("decision", "approve", "comment", "welcome"), ACCEPTED);
        assertThat(approved.get("status").asText()).isEqualTo("approved");
        assertThat(approved.get("decidedBy").asText()).isEqualTo(s.ownerId());
        assertThat(approved.get("comment").asText()).isEqualTo("welcome");
        JsonNode miaNow = call(GET, s.users() + "/" + mia, s.owner(), null, OK);
        assertThat(strings(miaNow.get("groups"))).containsExactlyInAnyOrder("viewers", "members");
        JsonNode allowed = call(POST, "/v1/authorize", viewer, authorize(mia, s.accountId(), List.of(check("compute:create", s.vm("*")))), OK);
        assertThat(allowed.get("decisions").get(0).get("role").asText()).isEqualTo("operator");
        call(POST, requests + "/" + id + ":decide", s.owner(), Map.of("decision", "approve"), CONFLICT);

        JsonNode second = call(POST, requests, viewer, Map.of("action", "audit:export", "role", "auditor", "reason", "quarterly audit evidence"), ACCEPTED);
        JsonNode rejected = call(POST, requests + "/" + second.get("requestId").asText() + ":decide", s.owner(), Map.of("decision", "reject", "comment",
                "ask the auditors"), ACCEPTED);
        assertThat(rejected.get("status").asText()).isEqualTo("rejected");
        assertThat(call(GET, requests + "/" + second.get("requestId").asText(), viewer, null, OK).get("status").asText()).isEqualTo("rejected");
        JsonNode alias = call(POST, requests, viewer, Map.of("action", "iam:create", "role", "admin", "reason", "I need to manage the users"), ACCEPTED);
        assertThat(alias.get("role").asText()).isEqualTo("account-admin");
        assertThat(alias.get("resource").asText()).isEqualTo("wfrn:wf:*:*:" + s.accountId() + ":*");

        List<EventEnvelope> events = outbox.after(0, 100_000).filter(e -> s.accountId().equals(e.partitionKey())).map(OutboxEntry::event)
                .filter(e -> "AccessRequestChanged".equals(e.type())).collectList().block();
        assertThat(events).isNotNull();
        List<String> actions = events.stream().filter(e -> id.equals(e.aggregateId())).map(e -> e.data().get("action").asText()).toList();
        assertThat(actions).containsExactly("created", "approved");
        EventEnvelope approvedEvent = events.stream().filter(e -> id.equals(e.aggregateId()) && "approved".equals(e.data().get("action").asText())).findFirst()
                .orElseThrow();
        assertThat(approvedEvent.aggregate()).isEqualTo("access-request");
        assertThat(approvedEvent.key()).isEqualTo("AccessRequestChanged:" + s.accountId() + ":" + id + ":2");
        assertThat(approvedEvent.data().get("snapshot").get("status").asText()).isEqualTo("approved");
        assertThat(approvedEvent.data().get("snapshot").get("requester").get("userId").asText()).isEqualTo(mia);
        assertThat(events.stream().filter(e -> second.get("requestId").asText().equals(e.aggregateId())).map(e -> e.data().get("action").asText()).toList())
                .containsExactly("created", "rejected");
        assertThat(outbox.after(0, 100_000).filter(e -> s.accountId().equals(e.partitionKey()) && "GroupChanged".equals(e.event().type())
                && "members".equals(e.event().data().get("snapshot").get("name").asText())).count().block()).as("the approval's GroupChanged").isGreaterThan(1);
    }

    @Test
    void pendingAccessRequestsExpireAfterThirtyDays() {
        Seeded s = newAccount();
        String mia = addUser(s, "mia", "viewers");
        Instant old = clock.instant().minus(AccessRequest.PENDING_FOR).minus(Duration.ofDays(1));
        AccessRequest stale = AccessRequest.open("areq_stale_" + s.accountId(), s.accountId(), new AccessRequest.Requester(mia, "mia@example.com", "mia"),
                "compute:create", s.vm("*"), "operator", "forgotten", List.of(s.ownerId()), old);
        accessRequestStore.insert(stale).block();
        JsonNode read = call(GET, s.base() + "/access-requests/" + stale.requestId(), s.owner(), null, OK);
        assertThat(read.get("status").asText()).as("served as expired before the sweep stores it").isEqualTo("expired");
        assertThat(expiry.sweep().block()).isEqualTo(1);
        assertThat(expiry.sweep().block()).isZero();
        assertThat(call(GET, s.base() + "/access-requests/" + stale.requestId(), s.owner(), null, OK).get("status").asText()).isEqualTo("expired");
        assertThat(call(GET, s.base() + "/access-requests?status=expired", s.owner(), null, OK).get("items")).hasSize(1);
        call(POST, s.base() + "/access-requests/" + stale.requestId() + ":decide", s.owner(), Map.of("decision", "approve"), CONFLICT);
        EventEnvelope expired = outbox.after(0, 100_000).filter(e -> stale.requestId().equals(e.event().aggregateId())).map(OutboxEntry::event).blockLast();
        assertThat(expired).isNotNull();
        assertThat(expired.type()).isEqualTo("AccessRequestChanged");
        assertThat(expired.data().get("action").asText()).isEqualTo("expired");
        assertThat(expired.actor()).isEqualTo("svc:console-identity-service");
    }

    @Test
    void groupsRolesAndPoliciesKeepTheirGuardrails() {
        Seeded s = newAccount();
        String mia = addUser(s, "mia", "viewers");
        String b = s.base();
        JsonNode groups = call(GET, b + "/groups", s.owner(), null, OK).get("items");
        JsonNode roles = call(GET, b + "/roles", s.owner(), null, OK).get("items");
        JsonNode policies = call(GET, b + "/policies", s.owner(), null, OK).get("items");
        String ownersId = idOf(groups, "name", "owners", "groupId");
        String adminRoleId = idOf(roles, "name", "account-admin", "roleId");
        String viewerRoleId = idOf(roles, "name", "viewer", "roleId");
        String managedPolicyId = idOf(policies, "name", "managed-viewer", "policyId");

        JsonNode sre = call(POST, b + "/groups", s.owner(), Map.of("name", "sre", "description", "Site reliability", "roles", List.of("operator"), "members",
                List.of(mia), "idpGroups", List.of("corp-sre")), CREATED);
        String sreId = sre.get("groupId").asText();
        assertThat(sre.get("members").get(0).asText()).isEqualTo(mia);
        assertThat(sre.get("roles").get(0).asText()).isEqualTo(idOf(roles, "name", "operator", "roleId"));
        assertThat(sre.get("builtIn").asBoolean()).isFalse();
        assertThat(sre.get("idpGroups").get(0).asText()).isEqualTo("corp-sre");
        assertThat(strings(call(GET, s.users() + "/" + mia, s.owner(), null, OK).get("groups"))).containsExactlyInAnyOrder("viewers", "sre");
        call(POST, b + "/groups", s.owner(), Map.of("name", "sre"), CONFLICT);
        call(POST, b + "/groups", s.owner(), Map.of("name", "ghosts", "roles", List.of("nonexistent")), BAD_REQUEST);
        call(POST, b + "/groups", s.owner(), Map.of("name", "ghosts", "members", List.of("usr_nobody")), BAD_REQUEST);
        call(POST, b + "/groups", s.owner(), Map.of("name", "Bad Name"), BAD_REQUEST);

        send(DELETE, b + "/groups/" + ownersId, s.owner(), null, Map.of()).expectStatus().isEqualTo(CONFLICT);
        send(PATCH, b + "/groups/" + ownersId, s.owner(), Map.of("roles", List.of("viewer")), Map.of()).expectStatus().isEqualTo(CONFLICT);
        send(PATCH, b + "/groups/" + ownersId, s.owner(), Map.of("name", "bosses"), Map.of()).expectStatus().isEqualTo(CONFLICT);
        send(PATCH, b + "/groups/" + ownersId, s.owner(), Map.of("description", "The bosses"), Map.of()).expectStatus().isOk();
        send(DELETE, b + "/roles/" + adminRoleId, s.owner(), null, Map.of()).expectStatus().isEqualTo(CONFLICT);
        send(DELETE, b + "/roles/" + viewerRoleId, s.owner(), null, Map.of()).expectStatus().isEqualTo(CONFLICT);
        send(PATCH, b + "/roles/" + viewerRoleId, s.owner(), Map.of("policies", List.of()), Map.of()).expectStatus().isEqualTo(CONFLICT);
        send(PATCH, b + "/roles/" + viewerRoleId, s.owner(), Map.of("description", "Looks only"), Map.of()).expectStatus().isOk();
        send(PUT, b + "/policies/" + managedPolicyId, s.owner(), Map.of("name", "managed-viewer", "statements", List.of(Map.of("effect", "allow", "actions",
                List.of("*:*"), "resources", List.of("wfrn:wf:*")))), Map.of()).expectStatus().isEqualTo(CONFLICT);
        send(DELETE, b + "/policies/" + managedPolicyId, s.owner(), null, Map.of()).expectStatus().isEqualTo(CONFLICT);

        String everything = "wfrn:wf:*:*:" + s.accountId() + ":*";
        JsonNode policy = call(POST, b + "/policies", s.owner(), Map.of("name", "no-deletes", "statements", List.of(Map.of("sid", "deny-delete", "effect", "deny",
                "actions", List.of("*:delete"), "resources", List.of(everything)))), CREATED);
        String policyId = policy.get("policyId").asText();
        assertThat(policy.get("builtIn").asBoolean()).isFalse();
        JsonNode bad = call(POST, b + "/policies", s.owner(), Map.of("name", "bad", "statements", List.of(Map.of("effect", "allow", "actions",
                List.of("compute:teleport"), "resources", List.of("wfrn:wf:*")))), BAD_REQUEST);
        assertThat(bad.get("problems").toString()).contains("unknown-action");
        call(POST, b + "/policies", s.owner(), Map.of("name", "no-deletes", "statements", List.of(Map.of("effect", "deny", "actions", List.of("*:delete"),
                "resources", List.of(everything)))), CONFLICT);
        JsonNode role = call(POST, b + "/roles", s.owner(), Map.of("name", "careful-operator", "policies", List.of("no-deletes", "managed-operator")), CREATED);
        String roleId = role.get("roleId").asText();
        assertThat(role.get("policies")).hasSize(2);
        send(DELETE, b + "/policies/" + policyId, s.owner(), null, Map.of()).expectStatus().isEqualTo(CONFLICT);
        send(PATCH, b + "/groups/" + sreId, s.owner(), Map.of("roles", List.of("careful-operator")), Map.of("If-Match", "1")).expectStatus().isOk();

        String vm = s.vm("vm-1");
        JsonNode decisions = call(POST, "/v1/authorize", s.owner(), authorize(mia, s.accountId(), List.of(check("compute:delete", vm), check("compute:restart", vm))),
                OK).get("decisions");
        assertThat(decisions.get(0).get("allowed").asBoolean()).isFalse();
        assertThat(decisions.get(0).get("policyId").asText()).isEqualTo(policyId);
        assertThat(decisions.get(0).get("statement").asText()).isEqualTo("deny-delete");
        assertThat(decisions.get(0).get("role").asText()).isEqualTo("careful-operator");
        assertThat(decisions.get(0).has("requestable")).isFalse();
        assertThat(decisions.get(1).get("allowed").asBoolean()).isTrue();
        assertThat(decisions.get(1).get("role").asText()).isEqualTo("careful-operator");

        JsonNode replaced = body(send(PUT, b + "/policies/" + policyId, s.owner(), Map.of("name", "no-vm-deletes", "statements", List.of(Map.of("sid", "s0",
                "effect", "deny", "actions", List.of("compute:delete"), "resources", List.of(s.vm("*"))))), Map.of("If-Match", "1")).expectStatus().isOk());
        assertThat(replaced.get("version").asLong()).isEqualTo(2);
        assertThat(replaced.get("name").asText()).isEqualTo("no-vm-deletes");
        send(DELETE, b + "/roles/" + roleId, s.owner(), null, Map.of()).expectStatus().isEqualTo(CONFLICT);
        send(PATCH, b + "/groups/" + sreId, s.owner(), Map.of("roles", List.of()), Map.of()).expectStatus().isOk();
        send(DELETE, b + "/roles/" + roleId, s.owner(), null, Map.of()).expectStatus().isNoContent();
        send(DELETE, b + "/policies/" + policyId, s.owner(), null, Map.of()).expectStatus().isNoContent();
        call(GET, b + "/policies/" + policyId, s.owner(), null, NOT_FOUND);
        call(GET, b + "/roles/" + roleId, s.owner(), null, NOT_FOUND);
        send(DELETE, b + "/groups/" + sreId, s.owner(), null, Map.of()).expectStatus().isNoContent();
        JsonNode miaAfter = call(GET, s.users() + "/" + mia, s.owner(), null, OK);
        assertThat(miaAfter.get("groups")).hasSize(1);
        assertThat(miaAfter.get("groups").get(0).asText()).isEqualTo("viewers");
    }

    @Test
    void ownersKeepOneActiveMemberAndARemovedUserLosesTheirKeys() {
        Seeded s = newAccount();
        String owner = s.users() + "/" + s.ownerId();
        send(DELETE, owner, s.owner(), null, Map.of()).expectStatus().isEqualTo(CONFLICT);
        send(PATCH, owner, s.owner(), Map.of("status", "suspended"), Map.of()).expectStatus().isEqualTo(CONFLICT);
        send(PATCH, owner, s.owner(), Map.of("groups", List.of("viewers")), Map.of()).expectStatus().isEqualTo(CONFLICT);

        String second = addUser(s, "second", "owners");
        String secondOwner = bearer(second, s.accountId(), List.of("owners"));
        JsonNode key = call(POST, s.base() + "/access-keys", s.owner(), Map.of("userId", s.ownerId()), CREATED);
        send(DELETE, owner, secondOwner, null, Map.of()).expectStatus().isNoContent();
        call(GET, owner, secondOwner, null, NOT_FOUND);
        assertThat(call(GET, s.base() + "/access-keys/" + key.get("keyId").asText(), internal(), null, OK).get("status").asText()).isEqualTo("revoked");
        JsonNode denied = call(GET, s.users(), s.owner(), null, FORBIDDEN);
        assertThat(denied.get("denied").get("reason").asText()).isEqualTo("not a member of the account");
        assertThat(call(GET, "/v1/accounts", s.owner(), null, OK).get("items")).isEmpty();
        send(DELETE, s.users() + "/" + second, secondOwner, null, Map.of()).expectStatus().isEqualTo(CONFLICT);
    }

    @Test
    void eventsCarryThePlatformEnvelopeWithoutSecrets() throws IOException {
        Seeded s = newAccount();
        List<OutboxEntry> entries = outbox.after(0, 100_000).filter(e -> s.accountId().equals(e.partitionKey())).collectList().block();
        assertThat(entries).isNotNull().isNotEmpty();
        assertThat(entries).allMatch(e -> "identity.changes".equals(e.topic()));
        List<String> types = entries.stream().map(e -> e.event().type()).toList();
        assertThat(types).containsOnlyOnce("AccountChanged", "UserChanged");
        assertThat(types.stream().filter("PolicyChanged"::equals).count()).isEqualTo(4);
        assertThat(types.stream().filter("RoleChanged"::equals).count()).isEqualTo(4);
        assertThat(types.stream().filter("GroupChanged"::equals).count()).isEqualTo(4);

        EventEnvelope created = entries.stream().filter(e -> "AccountChanged".equals(e.event().type())).findFirst().orElseThrow().event();
        assertThat(created.aggregate()).isEqualTo("account");
        assertThat(created.aggregateId()).isEqualTo(s.accountId());
        assertThat(created.key()).isEqualTo("AccountChanged:" + s.accountId() + ":" + s.accountId() + ":1");
        assertThat(created.context().accountId()).isEqualTo(s.accountId());
        assertThat(created.context().region()).isNull();
        assertThat(created.actor()).isEqualTo("svc:console-bff");
        assertThat(created.data().get("version").asLong()).isEqualTo(1);
        assertThat(created.data().get("action").asText()).isEqualTo("created");
        assertThat(created.data().get("snapshot").get("accountId").asText()).isEqualTo(s.accountId());
        assertThat(created.data().get("snapshot").get("name").asText()).isEqualTo(s.name());
        EventEnvelope user = entries.stream().filter(e -> "UserChanged".equals(e.event().type())).findFirst().orElseThrow().event();
        assertThat(user.aggregate()).isEqualTo("user");
        assertThat(user.aggregateId()).isEqualTo(s.ownerId());
        assertThat(user.data().get("snapshot").get("email").asText()).isEqualTo(s.ownerEmail());

        String token = call(GET, s.base() + "/platform-credentials", internal(), null, OK).get("token").asText();
        assertThat(token).isNotBlank();
        assertThat(JSON.writeValueAsString(entries)).doesNotContain(token);

        addUser(s, "dana", "members");
        send(DELETE, s.users() + "/" + idOf(call(GET, s.users(), s.owner(), null, OK).get("items"), "name", "dana", "userId"), s.owner(), null, Map.of())
                .expectStatus().isNoContent();
        List<OutboxEntry> later = outbox.after(0, 100_000).filter(e -> s.accountId().equals(e.partitionKey()) && "UserChanged".equals(e.event().type()))
                .collectList().block();
        assertThat(later).isNotNull();
        EventEnvelope deleted = later.get(later.size() - 1).event();
        assertThat(deleted.data().get("action").asText()).isEqualTo("deleted");
        assertThat(deleted.data().has("snapshot")).isFalse();
    }

    @Test
    void aSuspendedAccountIsClosedToItsUsers() {
        Seeded s = newAccount();
        JsonNode suspended = body(send(PATCH, s.base(), internal(), Map.of("status", "suspended"), Map.of("If-Match", "1")).expectStatus().isOk());
        assertThat(suspended.get("status").asText()).isEqualTo("suspended");
        assertThat(suspended.get("version").asLong()).isEqualTo(2);
        send(PATCH, s.base(), s.owner(), Map.of("status", "active"), Map.of()).expectStatus().isBadRequest();
        JsonNode denied = call(POST, s.users(), s.owner(), Map.of("email", "late-" + RUN + "@example.com"), FORBIDDEN);
        assertThat(denied.get("denied").get("reason").asText()).isEqualTo("account suspended");
        JsonNode decision = call(POST, "/v1/authorize", s.owner(), authorize(s.ownerId(), s.accountId(), List.of(check("compute:get", s.vm("vm-1")))), OK);
        assertThat(decision.get("decisions").get(0).get("reason").asText()).isEqualTo("account suspended");
        OutboxEntry event = outbox.after(0, 100_000)
                .filter(e -> s.accountId().equals(e.partitionKey()) && "AccountChanged".equals(e.event().type()) && "suspended".equals(e.event().data().get("action").asText()))
                .blockFirst();
        assertThat(event).isNotNull();
        assertThat(event.event().key()).isEqualTo("AccountChanged:" + s.accountId() + ":" + s.accountId() + ":2");

        send(PATCH, s.base(), internal(), Map.of("status", "active"), Map.of("If-Match", "2")).expectStatus().isOk();
        JsonNode renamed = call(PATCH, s.base(), s.owner(), Map.of("name", "Renamed " + s.accountId()), OK);
        assertThat(renamed.get("name").asText()).isEqualTo("Renamed " + s.accountId());
        assertThat(renamed.get("version").asLong()).isEqualTo(4);
        send(PATCH, s.base(), s.owner(), Map.of("name", "Renamed " + s.accountId()), Map.of("If-Match", "3")).expectStatus().isEqualTo(CONFLICT);
    }

    @Test
    void demoSeedMatchesThePlatformFakeWorld() {
        DemoSeed seed = new DemoSeed(accountCommands, accountStore, principalCommands, userStore, policyCommands, roleCommands, groupCommands, iamSupport, clock);
        seed.seed().block();
        seed.seed().block();

        JsonNode compute = call(GET, "/v1/accounts/" + DemoSeed.ACCOUNT_COMPUTE + "/users?limit=100", internal(), null, OK).get("items");
        assertThat(texts(compute, "email")).containsExactlyInAnyOrder("gonzalo@example.com", "dana@example.com", "mateo@example.com", "mia@example.com");
        JsonNode data = call(GET, "/v1/accounts/" + DemoSeed.ACCOUNT_DATA + "/users?limit=100", internal(), null, OK).get("items");
        assertThat(texts(data, "email")).containsExactlyInAnyOrder("gonzalo@example.com", "raj@example.com", "ana@example.com");
        JsonNode account = call(GET, "/v1/accounts/" + DemoSeed.ACCOUNT_COMPUTE, internal(), null, OK);
        assertThat(account.get("name").asText()).isEqualTo("Compute");
        assertThat(account.get("owningTeam").asText()).isEqualTo("team-compute");

        JsonNode dana = call(POST, "/v1/principals:resolve", internal(), Map.of("issuer", DemoSeed.ISSUER, "subject", "dana"), OK);
        assertThat(dana.get("user").get("email").asText()).isEqualTo("dana@example.com");
        assertThat(dana.get("user").get("name").asText()).isEqualTo("Dana Reyes");
        assertThat(dana.get("accounts")).hasSize(1);
        assertThat(dana.get("accounts").get(0).get("accountId").asText()).isEqualTo(DemoSeed.ACCOUNT_COMPUTE);
        assertThat(dana.get("accounts").get(0).get("groups").get(0).asText()).isEqualTo("members");
        assertThat(dana.get("accounts").get(0).get("roles").get(0).asText()).isEqualTo("operator");
        JsonNode gonzalo = call(POST, "/v1/principals:resolve", internal(), Map.of("issuer", DemoSeed.ISSUER, "subject", "gonzalo"), OK);
        assertThat(texts(gonzalo.get("accounts"), "accountId")).containsExactlyInAnyOrder(DemoSeed.ACCOUNT_COMPUTE, DemoSeed.ACCOUNT_DATA);
        assertThat(gonzalo.get("accounts")).allMatch(a -> "account-admin".equals(a.get("roles").get(0).asText()));
        JsonNode mia = call(POST, "/v1/principals:resolve", internal(), Map.of("issuer", DemoSeed.ISSUER, "subject", "mia"), OK);
        assertThat(mia.get("user").get("userId").asText()).isEqualTo("user_mia");
        assertThat(strings(mia.get("accounts").get(0).get("groups"))).containsExactlyInAnyOrder("viewers", "compute-approvers");
        assertThat(strings(mia.get("accounts").get(0).get("roles"))).as("roles are the managed role names only").containsExactly("viewer");
        JsonNode ana = call(POST, "/v1/principals:resolve", internal(), Map.of("issuer", DemoSeed.ISSUER, "subject", "ana"), OK);
        assertThat(strings(ana.get("accounts").get(0).get("groups"))).containsExactlyInAnyOrder("members", "viewers");
        assertThat(strings(ana.get("accounts").get(0).get("roles"))).containsExactlyInAnyOrder("operator", "viewer");
        call(POST, "/v1/principals:resolve", internal(), Map.of("issuer", DemoSeed.ISSUER, "subject", "noah", "email", "noah@example.com"), NOT_FOUND);

        String computeBase = "/v1/accounts/" + DemoSeed.ACCOUNT_COMPUTE;
        String miaToken = bearer("user_mia", DemoSeed.ACCOUNT_COMPUTE, List.of("viewers", "compute-approvers"));
        JsonNode request = call(POST, computeBase + "/access-requests", miaToken, Map.of("action", "compute:create", "role", "operator", "reason",
                "I deploy the demo machines"), ACCEPTED);
        assertThat(strings(request.get("approvers"))).as("mia decides others' requests, not her own").containsExactlyInAnyOrder("user_gonzalo", "user_dana");
        call(POST, computeBase + "/access-requests/" + request.get("requestId").asText() + ":decide", miaToken, Map.of("decision", "approve"), FORBIDDEN);
        String danaToken = bearer("user_dana", DemoSeed.ACCOUNT_COMPUTE, List.of("members", "compute-approvers"));
        JsonNode decided = call(POST, computeBase + "/access-requests/" + request.get("requestId").asText() + ":decide", danaToken, Map.of("decision", "approve"),
                ACCEPTED);
        assertThat(decided.get("status").asText()).isEqualTo("approved");
        assertThat(decided.get("decidedBy").asText()).isEqualTo("user_dana");
        String mateoToken = bearer("user_mateo", DemoSeed.ACCOUNT_COMPUTE, List.of("members"));
        call(POST, computeBase + "/access-requests/" + request.get("requestId").asText() + ":decide", mateoToken, Map.of("decision", "approve"), FORBIDDEN);
        JsonNode approverRole = call(GET, computeBase + "/roles", internal(), null, OK).get("items");
        assertThat(texts(approverRole, "name")).contains("approver");
    }

    @Test
    void nobodyDecidesTheirOwnAccessRequest() {
        Seeded s = newAccount();
        String dana = addUser(s, "dana", "members");
        String mia = addUser(s, "mia", "viewers");
        String b = s.base();
        // an admin lets a custom group decide access requests, and dana is in it
        JsonNode policy = call(POST, b + "/policies", s.owner(), Map.of("name", "approver", "statements", List.of(Map.of("sid", "decide", "effect", "allow",
                "actions", List.of("access-request:get", "access-request:list", "access-request:decide"), "resources",
                List.of(iamSupport.anyResource(s.accountId(), "access-request"))))), CREATED);
        call(POST, b + "/roles", s.owner(), Map.of("name", "approver", "policies", List.of(policy.get("policyId").asText())), CREATED);
        call(POST, b + "/groups", s.owner(), Map.of("name", "approvers", "roles", List.of("approver"), "members", List.of(dana)), CREATED);
        String danaToken = bearer(dana, s.accountId(), List.of("members", "approvers"));
        String requests = b + "/access-requests";

        // dana asks for the admin role: she decides others' requests, never her own, and is no approver of it
        JsonNode own = call(POST, requests, danaToken, Map.of("action", "iam:create", "role", "account-admin", "reason", "I manage the team's users"),
                ACCEPTED);
        String ownId = own.get("requestId").asText();
        assertThat(strings(own.get("approvers"))).containsExactly(s.ownerId());
        JsonNode refused = call(POST, requests + "/" + ownId + ":decide", danaToken, Map.of("decision", "approve"), FORBIDDEN);
        assertThat(refused.get("denied").get("layer").asText()).isEqualTo("console-iam");
        assertThat(refused.get("denied").get("action").asText()).isEqualTo("access-request:decide");
        assertThat(refused.get("denied").get("resource").asText()).endsWith(ownId);
        assertThat(refused.get("denied").get("reason").asText()).contains("their own");
        assertThat(refused.get("denied").get("requestable").asBoolean()).isFalse();
        call(POST, requests + "/" + ownId + ":decide", danaToken, Map.of("decision", "reject"), FORBIDDEN);
        assertThat(call(GET, requests + "/" + ownId, danaToken, null, OK).get("status").asText()).as("still pending").isEqualTo("pending");
        assertThat(strings(call(GET, s.users() + "/" + dana, s.owner(), null, OK).get("groups"))).doesNotContain("owners");

        // mia's request goes to dana as well, and dana decides it
        JsonNode miaAsk = call(POST, requests, bearer(mia, s.accountId(), List.of("viewers")), Map.of("action", "compute:create", "resource", s.vm("*"),
                "reason", "I deploy the test machines"), ACCEPTED);
        assertThat(strings(miaAsk.get("approvers"))).containsExactlyInAnyOrder(s.ownerId(), dana);
        JsonNode decided = call(POST, requests + "/" + miaAsk.get("requestId").asText() + ":decide", danaToken, Map.of("decision", "approve"), ACCEPTED);
        assertThat(decided.get("decidedBy").asText()).isEqualTo(dana);

        // and the owner decides dana's
        JsonNode approved = call(POST, requests + "/" + ownId + ":decide", s.owner(), Map.of("decision", "approve"), ACCEPTED);
        assertThat(approved.get("status").asText()).isEqualTo("approved");
        assertThat(strings(call(GET, s.users() + "/" + dana, s.owner(), null, OK).get("groups"))).contains("owners");
    }

    @Test
    void aPermissionBoundaryCapsWhatARoleGrants() {
        Seeded s = newAccount();
        String dana = addUser(s, "dana", "viewers");
        String b = s.base();
        String computeOnly = call(POST, b + "/policies", s.owner(), Map.of("name", "compute-only", "statements", List.of(Map.of("sid", "compute", "effect",
                "allow", "actions", List.of("compute:*"), "resources", List.of("wfrn:wf:compute:*")))), CREATED).get("policyId").asText();
        JsonNode role = call(POST, b + "/roles", s.owner(), Map.of("name", "bounded-operator", "policies", List.of("managed-operator"), "boundary",
                "compute-only"), CREATED);
        String roleId = role.get("roleId").asText();
        assertThat(role.get("boundary").asText()).as("named, kept by id").isEqualTo(computeOnly);
        call(POST, b + "/groups", s.owner(), Map.of("name", "sre", "roles", List.of("bounded-operator"), "members", List.of(dana)), CREATED);

        // the role allows every change but deletes and IAM; the boundary lets only compute's through, and another role's allow still counts
        String db = "wfrn:wf:data:global:" + s.accountId() + ":db/db-1";
        JsonNode decisions = call(POST, "/v1/authorize", s.owner(), authorize(dana, s.accountId(), List.of(check("compute:restart", s.vm("vm-1")),
                check("data:update", db), check("data:get", db))), OK).get("decisions");
        assertThat(decisions.get(0).get("allowed").asBoolean()).isTrue();
        assertThat(decisions.get(0).get("role").asText()).isEqualTo("bounded-operator");
        JsonNode bounded = decisions.get(1);
        assertThat(bounded.get("allowed").asBoolean()).isFalse();
        assertThat(bounded.get("scope").asText()).isEqualTo("boundary");
        assertThat(bounded.get("policyId").asText()).isEqualTo(computeOnly);
        assertThat(bounded.get("role").asText()).isEqualTo("bounded-operator");
        assertThat(bounded.get("reason").asText()).isEqualTo("outside the permission boundary compute-only of role bounded-operator");
        assertThat(bounded.get("requestable").asBoolean()).as("asking for a role does not lift a boundary").isFalse();
        assertThat(decisions.get(2).get("allowed").asBoolean()).isTrue();
        assertThat(decisions.get(2).get("role").asText()).isEqualTo("viewer");
        EventEnvelope created = outbox.after(0, 100_000).filter(e -> roleId.equals(e.event().aggregateId())).map(OutboxEntry::event).blockFirst();
        assertThat(created).isNotNull();
        assertThat(created.data().get("snapshot").get("boundary").asText()).isEqualTo(computeOnly);

        // an empty boundary removes it, an id sets it again, a change that leaves it out keeps it
        JsonNode cleared = body(send(PATCH, b + "/roles/" + roleId, s.owner(), Map.of("boundary", ""), Map.of("If-Match", "1")).expectStatus().isOk());
        assertThat(cleared.has("boundary")).isFalse();
        assertThat(cleared.get("version").asLong()).isEqualTo(2);
        assertThat(call(POST, "/v1/authorize", s.owner(), authorize(dana, s.accountId(), List.of(check("data:update", db))), OK).get("decisions").get(0)
                .get("allowed").asBoolean()).isTrue();
        assertThat(call(PATCH, b + "/roles/" + roleId, s.owner(), Map.of("boundary", computeOnly), OK).get("boundary").asText()).isEqualTo(computeOnly);
        JsonNode described = call(PATCH, b + "/roles/" + roleId, s.owner(), Map.of("description", "Operates compute only"), OK);
        assertThat(described.get("boundary").asText()).isEqualTo(computeOnly);
        assertThat(described.get("version").asLong()).isEqualTo(4);

        // the account-admin role takes none (it is how an account undoes a boundary), a boundary names a policy of the account, and a bounding policy stays
        String adminRoleId = idOf(call(GET, b + "/roles", s.owner(), null, OK).get("items"), "name", "account-admin", "roleId");
        call(PATCH, b + "/roles/" + adminRoleId, s.owner(), Map.of("boundary", "compute-only"), CONFLICT);
        JsonNode unknown = call(POST, b + "/roles", s.owner(), Map.of("name", "ghost-bounded", "policies", List.of("managed-viewer"), "boundary", "nonexistent"),
                BAD_REQUEST);
        assertThat(unknown.get("problems").get(0).get("field").asText()).isEqualTo("boundary");
        JsonNode held = call(DELETE, b + "/policies/" + computeOnly, s.owner(), null, CONFLICT);
        assertThat(held.get("error").asText()).contains("bounded-operator");
        call(PATCH, b + "/roles/" + roleId, bearer(dana, s.accountId(), List.of("viewers", "sre")), Map.of("boundary", ""), FORBIDDEN);
    }

    @Test
    void guardrailsHoldOverEveryRoleInTheAccountsTheyTarget() {
        Seeded s = newAccount();
        Seeded other = newAccount();
        String mia = addUser(s, "mia", "viewers");
        String name = "no-prod-deletes-" + next() + "-" + RUN;
        Map<String, Object> prodDeletes = Map.of("sid", "prod-deletes", "effect", "deny", "actions", List.of("*:delete"), "resources", List.of("wfrn:wf:*"),
                "conditions", Map.of("tag.env", "prod"));
        Map<String, Object> input = Map.of("name", name, "description", "Production is deleted by change request only", "statements", List.of(prodDeletes),
                "accounts", List.of(s.accountId()));

        // the organization's, never an account's: anyone but the internal principal gets a 404
        call(POST, "/v1/guardrails", s.owner(), input, NOT_FOUND);
        call(GET, "/v1/guardrails", s.owner(), null, NOT_FOUND);
        call(POST, "/v1/guardrails", internal(), Map.of("name", name, "statements", List.of(prodDeletes)), BAD_REQUEST);
        call(POST, "/v1/guardrails", internal(), Map.of("name", name, "statements", List.of(prodDeletes), "accounts", List.of("*", s.accountId())), BAD_REQUEST);
        JsonNode nowhere = call(POST, "/v1/guardrails", internal(), Map.of("name", name, "statements", List.of(prodDeletes), "accounts", List.of(s.accountId(),
                "999999999999")), BAD_REQUEST);
        assertThat(nowhere.get("problems").get(0).get("field").asText()).isEqualTo("accounts[1]");
        call(POST, "/v1/guardrails", internal(), Map.of("name", name, "statements", List.of(Map.of("effect", "deny", "actions", List.of("compute:teleport"),
                "resources", List.of("wfrn:wf:*"))), "accounts", List.of(s.accountId())), BAD_REQUEST);

        Map<String, Object> prod = Map.of("tags", Map.of("env", "prod"));
        long before = call(POST, "/v1/authorize", s.owner(), authorize(s.ownerId(), s.accountId(), List.of(check("compute:get", s.vm("vm-1")))), OK).get("version")
                .asLong();
        JsonNode created = call(POST, "/v1/guardrails", internal(), input, CREATED);
        String guardrailId = created.get("guardrailId").asText();
        assertThat(guardrailId).startsWith("gr_");
        assertThat(created.get("version").asLong()).isEqualTo(1);
        assertThat(strings(created.get("accounts"))).containsExactly(s.accountId());
        call(POST, "/v1/guardrails", internal(), input, CONFLICT);

        // a matching deny refuses whatever a role allows, the admin's too; it holds only where it matches and only in the accounts it targets
        JsonNode answer = call(POST, "/v1/authorize", s.owner(), authorize(s.ownerId(), s.accountId(), List.of(check("compute:delete", s.vm("vm-1"), prod),
                check("compute:delete", s.vm("vm-2")), check("compute:restart", s.vm("vm-1"), prod))), OK);
        JsonNode refused = answer.get("decisions").get(0);
        assertThat(refused.get("allowed").asBoolean()).isFalse();
        assertThat(refused.get("scope").asText()).isEqualTo("guardrail");
        assertThat(refused.get("guardrailId").asText()).isEqualTo(guardrailId);
        assertThat(refused.get("statement").asText()).isEqualTo("prod-deletes");
        assertThat(refused.get("reason").asText()).isEqualTo("denied by guardrail " + name + ", statement prod-deletes");
        assertThat(refused.get("requestable").asBoolean()).as("no role of the account lifts a guardrail").isFalse();
        assertThat(refused.has("policyId")).isFalse();
        assertThat(answer.get("decisions").get(1).get("allowed").asBoolean()).isTrue();
        assertThat(answer.get("decisions").get(2).get("allowed").asBoolean()).isTrue();
        assertThat(answer.get("version").asLong()).as("the account's decisions moved on").isGreaterThan(before);
        assertThat(call(POST, "/v1/authorize", other.owner(), authorize(other.ownerId(), other.accountId(), List.of(check("compute:delete", other.vm("vm-1"),
                prod))), OK).get("decisions").get(0).get("allowed").asBoolean()).isTrue();
        JsonNode simulated = call(POST, s.base() + "/policy-simulations", s.owner(), Map.of("principal", Map.of("userId", s.ownerId()), "checks",
                List.of(check("compute:delete", s.vm("vm-1"), prod))), OK).get("results").get(0);
        assertThat(simulated.get("guardrailId").asText()).isEqualTo(guardrailId);

        // a change, under If-Match: a statement with no condition refuses an IAM write, with the guardrail in the denial
        Map<String, Object> noUserDeletes = Map.of("sid", "keep-users", "effect", "deny", "actions", List.of("iam:delete"), "resources",
                List.of("wfrn:wf:iam:*:*:user/*"));
        String one = "/v1/guardrails/" + guardrailId;
        send(PATCH, one, internal(), Map.of("statements", List.of(prodDeletes, noUserDeletes)), Map.of("If-Match", "2")).expectStatus().isEqualTo(CONFLICT);
        JsonNode changed = body(send(PATCH, one, internal(), Map.of("statements", List.of(prodDeletes, noUserDeletes)), Map.of("If-Match", "1")).expectStatus()
                .isOk());
        assertThat(changed.get("version").asLong()).isEqualTo(2);
        assertThat(changed.get("name").asText()).isEqualTo(name);
        assertThat(changed.get("statements")).hasSize(2);
        JsonNode denied = call(DELETE, s.users() + "/" + mia, s.owner(), null, FORBIDDEN).get("denied");
        assertThat(denied.get("layer").asText()).isEqualTo("console-iam");
        assertThat(denied.get("action").asText()).isEqualTo("iam:delete");
        assertThat(denied.get("scope").asText()).isEqualTo("guardrail");
        assertThat(denied.get("guardrailId").asText()).isEqualTo(guardrailId);
        assertThat(denied.get("requestable").asBoolean()).isFalse();

        // an allow-list: once a guardrail of an account allows anything, an action none of them allows is refused
        JsonNode globalOnly = call(POST, "/v1/guardrails", internal(), Map.of("name", "global-only-" + next() + "-" + RUN, "statements", List.of(
                Map.of("sid", "global", "effect", "allow", "actions", List.of("*:*"), "resources", List.of("wfrn:wf:*:global:*")),
                Map.of("sid", "identity", "effect", "allow", "actions", List.of("*:*"), "resources", List.of("wfrn:wf:iam:*"))), "accounts", List.of(s.accountId())),
                CREATED);
        String globalOnlyId = globalOnly.get("guardrailId").asText();
        String elsewhere = "wfrn:wf:compute:eu-west-1:" + s.accountId() + ":vm/vm-1";
        JsonNode regions = call(POST, "/v1/authorize", s.owner(), authorize(s.ownerId(), s.accountId(), List.of(check("compute:get", elsewhere),
                check("compute:get", s.vm("vm-1")))), OK).get("decisions");
        assertThat(regions.get(0).get("allowed").asBoolean()).isFalse();
        assertThat(regions.get(0).get("reason").asText()).isEqualTo("outside the organization's guardrails");
        assertThat(regions.get(0).get("scope").asText()).isEqualTo("guardrail");
        assertThat(regions.get(0).has("guardrailId")).as("no one guardrail refused it").isFalse();
        assertThat(regions.get(1).get("allowed").asBoolean()).isTrue();

        // the account's people who may list its IAM see the guardrails that apply there, in id order
        List<String> both = new ArrayList<>(List.of(guardrailId, globalOnlyId));
        java.util.Collections.sort(both);
        assertThat(texts(call(GET, s.base() + "/guardrails", s.owner(), null, OK).get("items"), "guardrailId")).containsExactlyElementsOf(both);
        assertThat(texts(call(GET, s.base() + "/guardrails", bearer(mia, s.accountId(), List.of("viewers")), null, OK).get("items"), "guardrailId")).hasSize(2);
        assertThat(call(GET, other.base() + "/guardrails", other.owner(), null, OK).get("items")).isEmpty();
        call(GET, other.base() + "/guardrails", s.owner(), null, NOT_FOUND);
        assertThat(texts(call(GET, "/v1/guardrails?limit=200", internal(), null, OK).get("items"), "guardrailId")).contains(guardrailId, globalOnlyId);
        assertThat(call(GET, one, internal(), null, OK).get("version").asLong()).isEqualTo(2);
        call(GET, one, s.owner(), null, NOT_FOUND);
        call(GET, "/v1/guardrails/gr_nope", internal(), null, NOT_FOUND);

        // retargeting moves it: the account it left is free of it, the one it reached holds it
        JsonNode moved = body(send(PATCH, one, internal(), Map.of("accounts", List.of(other.accountId())), Map.of("If-Match", "2")).expectStatus().isOk());
        assertThat(moved.get("version").asLong()).isEqualTo(3);
        assertThat(call(POST, "/v1/authorize", s.owner(), authorize(s.ownerId(), s.accountId(), List.of(check("compute:delete", s.vm("vm-1"), prod))), OK)
                .get("decisions").get(0).get("allowed").asBoolean()).isTrue();
        assertThat(call(POST, "/v1/authorize", other.owner(), authorize(other.ownerId(), other.accountId(), List.of(check("compute:delete", other.vm("vm-1"),
                prod))), OK).get("decisions").get(0).get("scope").asText()).isEqualTo("guardrail");
        send(DELETE, one, s.owner(), null, Map.of()).expectStatus().isNotFound();
        send(DELETE, one, internal(), null, Map.of()).expectStatus().isNoContent();
        send(DELETE, "/v1/guardrails/" + globalOnlyId, internal(), null, Map.of()).expectStatus().isNoContent();
        send(DELETE, one, internal(), null, Map.of()).expectStatus().isNotFound();
        assertThat(call(GET, s.base() + "/guardrails", s.owner(), null, OK).get("items")).isEmpty();
        assertThat(call(POST, "/v1/authorize", s.owner(), authorize(s.ownerId(), s.accountId(), List.of(check("compute:get", elsewhere))), OK).get("decisions")
                .get(0).get("allowed").asBoolean()).isTrue();

        // each account it touched heard every change: created, updated, deleted where it no longer applies
        List<EventEnvelope> events = outbox.after(0, 100_000).map(OutboxEntry::event).filter(e -> "GuardrailChanged".equals(e.type())).collectList().block();
        assertThat(events).isNotNull();
        java.util.function.BiFunction<Seeded, String, List<String>> heard = (account, id) -> events.stream()
                .filter(e -> account.accountId().equals(e.context().accountId()) && id.equals(e.aggregateId())).map(e -> e.data().get("action").asText()).toList();
        assertThat(heard.apply(s, guardrailId)).containsExactly("created", "updated", "deleted");
        assertThat(heard.apply(other, guardrailId)).containsExactly("updated", "deleted");
        assertThat(heard.apply(s, globalOnlyId)).containsExactly("created", "deleted");
        assertThat(heard.apply(other, globalOnlyId)).isEmpty();
        EventEnvelope first = events.stream().filter(e -> guardrailId.equals(e.aggregateId())).findFirst().orElseThrow();
        assertThat(first.aggregate()).isEqualTo("guardrail");
        assertThat(first.key()).isEqualTo("GuardrailChanged:" + s.accountId() + ":" + guardrailId + ":1");
        assertThat(first.actor()).isEqualTo("svc:console-bff");
        assertThat(first.data().get("snapshot").get("name").asText()).isEqualTo(name);
        EventEnvelope last = events.stream().filter(e -> guardrailId.equals(e.aggregateId())).reduce((a, z) -> z).orElseThrow();
        assertThat(last.data().get("action").asText()).isEqualTo("deleted");
        assertThat(last.data().get("version").asLong()).isEqualTo(4);
        assertThat(last.data().has("snapshot")).isFalse();
    }

    @Test
    void aGuardrailForEveryAccountReachesEachOfThem() {
        Seeded s = newAccount();
        Seeded other = newAccount();
        String probe = "probe-" + next() + "-" + RUN;
        Map<String, Object> tagged = Map.of("tags", Map.of("probe", probe));
        JsonNode everywhere = call(POST, "/v1/guardrails", internal(), Map.of("name", probe, "statements", List.of(Map.of("sid", "probe", "effect", "deny",
                "actions", List.of("compute:delete"), "resources", List.of("wfrn:wf:*"), "conditions", Map.of("tag.probe", probe))), "accounts", List.of("*")), CREATED);
        String id = everywhere.get("guardrailId").asText();
        for (Seeded account : List.of(s, other)) {
            assertThat(texts(call(GET, account.base() + "/guardrails", account.owner(), null, OK).get("items"), "guardrailId")).contains(id);
            JsonNode decision = call(POST, "/v1/authorize", account.owner(), authorize(account.ownerId(), account.accountId(), List.of(check("compute:delete",
                    account.vm("vm-1"), tagged))), OK).get("decisions").get(0);
            assertThat(decision.get("guardrailId").asText()).isEqualTo(id);
        }
        Seeded later = newAccount();
        assertThat(call(POST, "/v1/authorize", later.owner(), authorize(later.ownerId(), later.accountId(), List.of(check("compute:delete", later.vm("vm-1"),
                tagged))), OK).get("decisions").get(0).get("allowed").asBoolean()).as("an account made after it, too").isFalse();
        send(DELETE, "/v1/guardrails/" + id, internal(), null, Map.of()).expectStatus().isNoContent();
        assertThat(call(POST, "/v1/authorize", later.owner(), authorize(later.ownerId(), later.accountId(), List.of(check("compute:delete", later.vm("vm-1"),
                tagged))), OK).get("decisions").get(0).get("allowed").asBoolean()).isTrue();
        List<EventEnvelope> events = outbox.after(0, 100_000).map(OutboxEntry::event).filter(e -> id.equals(e.aggregateId())).collectList().block();
        assertThat(events).isNotNull();
        for (Seeded account : List.of(s, other)) {
            assertThat(events.stream().filter(e -> account.accountId().equals(e.context().accountId())).map(e -> e.data().get("action").asText()).toList())
                    .containsExactly("created", "deleted");
        }
        assertThat(events.stream().filter(e -> later.accountId().equals(e.context().accountId())).map(e -> e.data().get("action").asText()).toList())
                .containsExactly("deleted");
    }

    @Test
    void thePolicySimulatorDecidesAsAuthorizeWouldAndWritesNothing() {
        Seeded s = newAccount();
        String mia = addUser(s, "mia", "viewers");
        String viewer = bearer(mia, s.accountId(), List.of("viewers"));
        String simulate = s.base() + "/policy-simulations";
        String vm = s.vm("vm-1");
        List<Map<String, Object>> checks = List.of(check("compute:get", vm), check("compute:delete", vm), check("iam:create", "wfrn:wf:iam::" + s.accountId()
                + ":user/*"));

        // a person: each result is authorize's decision for them, at the same version, with the check it answers
        JsonNode asMia = call(POST, simulate, s.owner(), Map.of("principal", Map.of("userId", mia), "checks", checks), OK);
        JsonNode authorized = call(POST, "/v1/authorize", s.owner(), authorize(mia, s.accountId(), checks), OK);
        long version = authorized.get("version").asLong();
        assertThat(asMia.get("version").asLong()).isEqualTo(version);
        assertThat(asMia.get("results")).hasSize(3);
        for (int i = 0; i < checks.size(); i++) {
            com.fasterxml.jackson.databind.node.ObjectNode result = (com.fasterxml.jackson.databind.node.ObjectNode) asMia.get("results").get(i).deepCopy();
            assertThat(result.remove("action").asText()).isEqualTo(checks.get(i).get("action"));
            assertThat(result.remove("resource").asText()).isEqualTo(checks.get(i).get("resource"));
            assertThat(result).isEqualTo(authorized.get("decisions").get(i));
        }

        // unsaved policies: what they would change, with nothing stored
        Map<String, Object> draft = Map.of("name", "vm-deleter", "statements", List.of(Map.of("sid", "delete-vms", "effect", "allow", "actions",
                List.of("compute:delete"), "resources", List.of(s.vm("*")))));
        JsonNode withDraft = call(POST, simulate, s.owner(), Map.of("principal", Map.of("userId", mia), "policies", List.of(draft), "checks",
                List.of(check("compute:delete", vm))), OK).get("results").get(0);
        assertThat(withDraft.get("allowed").asBoolean()).isTrue();
        assertThat(withDraft.get("statement").asText()).isEqualTo("delete-vms");
        assertThat(withDraft.get("policyId").asText()).isEqualTo("simulated:policy-0");

        // a role, by name or id, alone or with a draft boundary in place of its own
        JsonNode operator = call(POST, simulate, s.owner(), Map.of("principal", Map.of("role", "operator"), "checks", List.of(check("compute:restart", vm),
                check("compute:delete", vm))), OK).get("results");
        assertThat(operator.get(0).get("allowed").asBoolean()).isTrue();
        assertThat(operator.get(0).get("role").asText()).isEqualTo("operator");
        assertThat(operator.get(1).get("allowed").asBoolean()).isFalse();
        String operatorId = idOf(call(GET, s.base() + "/roles", s.owner(), null, OK).get("items"), "name", "operator", "roleId");
        Map<String, Object> readsOnly = Map.of("name", "reads-only", "statements", List.of(Map.of("effect", "allow", "actions", List.of("*:get", "*:list"),
                "resources", List.of("wfrn:wf:*"))));
        JsonNode capped = call(POST, simulate, s.owner(), Map.of("principal", Map.of("role", operatorId), "boundary", readsOnly, "checks",
                List.of(check("compute:restart", vm), check("compute:get", vm))), OK).get("results");
        assertThat(capped.get(0).get("allowed").asBoolean()).isFalse();
        assertThat(capped.get(0).get("scope").asText()).isEqualTo("boundary");
        assertThat(capped.get(0).get("policyId").asText()).isEqualTo("simulated:boundary");
        assertThat(capped.get(0).get("reason").asText()).isEqualTo("outside the permission boundary reads-only of role operator");
        assertThat(capped.get(1).get("allowed").asBoolean()).isTrue();
        call(POST, simulate, s.owner(), Map.of("principal", Map.of("role", "account-admin"), "boundary", readsOnly, "checks", List.of(check("compute:get", vm))),
                CONFLICT);

        // a set of groups, by id or name, holds the roles of all of them
        String viewersId = idOf(call(GET, s.base() + "/groups", s.owner(), null, OK).get("items"), "name", "viewers", "groupId");
        JsonNode groups = call(POST, simulate, viewer, Map.of("principal", Map.of("groups", List.of(viewersId, "members")), "checks",
                List.of(check("compute:restart", vm))), OK).get("results").get(0);
        assertThat(groups.get("allowed").asBoolean()).isTrue();
        assertThat(groups.get("role").asText()).isEqualTo("operator");
        assertThat(call(POST, simulate, internal(), Map.of("principal", Map.of("userId", mia), "checks", List.of(check("compute:get", vm))), OK).get("results"))
                .hasSize(1);

        // nothing was written: the version stands, no policy was made, authorize still refuses
        JsonNode after = call(POST, "/v1/authorize", s.owner(), authorize(mia, s.accountId(), List.of(check("compute:delete", vm))), OK);
        assertThat(after.get("version").asLong()).isEqualTo(version);
        assertThat(after.get("decisions").get(0).get("allowed").asBoolean()).isFalse();
        assertThat(texts(call(GET, s.base() + "/policies", s.owner(), null, OK).get("items"), "name")).doesNotContain("vm-deleter", "reads-only");
        assertThat(call(GET, s.base() + "/roles/" + operatorId, s.owner(), null, OK).has("boundary")).isFalse();

        // the request's shape
        List<Map<String, Object>> one = List.of(check("compute:get", vm));
        call(POST, simulate, s.owner(), Map.of("principal", Map.of("userId", mia, "role", "operator"), "checks", one), BAD_REQUEST);
        call(POST, simulate, s.owner(), Map.of("principal", Map.of(), "checks", one), BAD_REQUEST);
        call(POST, simulate, s.owner(), Map.of("checks", one), BAD_REQUEST);
        call(POST, simulate, s.owner(), Map.of("principal", Map.of("userId", mia), "checks", List.of()), BAD_REQUEST);
        call(POST, simulate, s.owner(), Map.of("principal", Map.of("userId", mia), "checks", List.of(check("nonsense", vm))), BAD_REQUEST);
        JsonNode boundaryForAPerson = call(POST, simulate, s.owner(), Map.of("principal", Map.of("userId", mia), "boundary", readsOnly, "checks", one), BAD_REQUEST);
        assertThat(boundaryForAPerson.get("problems").get(0).get("field").asText()).isEqualTo("boundary");
        JsonNode badDraft = call(POST, simulate, s.owner(), Map.of("principal", Map.of("userId", mia), "policies", List.of(Map.of("statements", List.of(
                Map.of("effect", "allow", "actions", List.of("compute:teleport"), "resources", List.of("wfrn:wf:*"))))), "checks", one), BAD_REQUEST);
        assertThat(badDraft.get("problems").toString()).contains("unknown-action");
        call(POST, simulate, s.owner(), Map.of("principal", Map.of("userId", "usr_nobody"), "checks", one), NOT_FOUND);
        assertThat(call(POST, simulate, s.owner(), Map.of("principal", Map.of("role", "nonexistent"), "checks", one), BAD_REQUEST).get("problems").get(0)
                .get("field").asText()).isEqualTo("principal.role");
        call(POST, simulate, s.owner(), Map.of("principal", Map.of("groups", List.of("nonexistent")), "checks", one), BAD_REQUEST);

        // simulating needs the right to read the principal; another account is a 404
        call(POST, s.base() + "/groups", s.owner(), Map.of("name", "nobodies"), CREATED);
        String noah = addUser(s, "noah", "nobodies");
        String nobody = bearer(noah, s.accountId(), List.of("nobodies"));
        JsonNode refusedPerson = call(POST, simulate, nobody, Map.of("principal", Map.of("userId", mia), "checks", one), FORBIDDEN).get("denied");
        assertThat(refusedPerson.get("action").asText()).isEqualTo("iam:get");
        assertThat(refusedPerson.get("resource").asText()).isEqualTo("wfrn:wf:iam::" + s.accountId() + ":user/" + mia);
        assertThat(call(POST, simulate, nobody, Map.of("principal", Map.of("groups", List.of("viewers")), "checks", one), FORBIDDEN).get("denied").get("action")
                .asText()).isEqualTo("iam:list");
        Seeded other = newAccount();
        call(POST, other.base() + "/policy-simulations", s.owner(), Map.of("principal", Map.of("userId", other.ownerId()), "checks", one), NOT_FOUND);
    }

    @Test
    void callsWithoutATokenAre401AndTheActuatorIsOpen() {
        web.get().uri("/v1/accounts").exchange().expectStatus().isUnauthorized()
                .expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE)
                .expectBody().jsonPath("$.error").isNotEmpty().jsonPath("$.correlationId").isNotEmpty();
        web.get().uri("/v1/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer nonsense").exchange().expectStatus().isUnauthorized();
        web.get().uri("/v1/accounts").header(HttpHeaders.AUTHORIZATION, "Basic abc").exchange().expectStatus().isUnauthorized();
        web.get().uri("/v1/accounts").header("X-User", "gonzalo").header("X-Account", "123456789012").exchange().expectStatus().isUnauthorized();
        web.get().uri("/actuator/health").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
        web.get().uri("/v1/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + SIGNER.service("console-bff", java.time.Duration.ofHours(3))).exchange()
                .expectStatus().isUnauthorized();
    }
}
