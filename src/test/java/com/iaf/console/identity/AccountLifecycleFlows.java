package com.iaf.console.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.identity.accesskeys.application.AccessKeyCommands;
import com.iaf.console.identity.principals.adapters.directory.InMemoryDirectory;
import com.iaf.console.identity.principals.domain.DirectoryGroup;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.outbox.OutboxPort;

/**
 * The account lifecycle end to end over HTTP: groups held through the identity provider at sign-in
 * and in the nightly group sync, the admin's exclusions, the last owner's owners group, account
 * closure and the cost centre. The same tests run on the in-memory stores and on MongoDB.
 */
abstract class AccountLifecycleFlows extends WithService {

    static final String RESOLVE = "/v1/principals:resolve";
    static final String GROUP_SYNC = "/internal/v1/group-sync:run";
    static final String ISSUER = "https://idp.example.com/lifecycle-" + RUN;

    @Autowired
    OutboxPort outbox;
    @Autowired
    InMemoryDirectory directory;
    @Autowired
    AccessKeyCommands accessKeys;

    /** An account made for one test, with an owning team of its own. */
    record Made(String accountId, String name, String team, String ownerId, String ownerEmail) {

        String owner() {
            return bearer(ownerId, accountId, List.of("owners"));
        }

        String base() {
            return "/v1/accounts/" + accountId;
        }

        String users() {
            return base() + "/users";
        }

        String user(String userId) {
            return users() + "/" + userId;
        }
    }

    Made account(Map<String, Object> extra) {
        int n = next();
        String email = "lc-owner" + n + "-" + RUN + "@example.com";
        String name = "Lifecycle " + n + " " + RUN;
        String team = "lc-team-" + n + "-" + RUN;
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name, "owningTeam", team, "ownerEmail", email));
        body.putAll(extra);
        String accountId = call(POST, "/v1/accounts", internal(), body, CREATED).get("accountId").asText();
        JsonNode users = call(GET, "/v1/accounts/" + accountId + "/users", internal(), null, OK).get("items");
        return new Made(accountId, name, team, users.get(0).get("userId").asText(), email);
    }

    Made account() {
        return account(Map.of());
    }

    static String email(String shortName) {
        return shortName + "-" + next() + "-" + RUN + "@example.com";
    }

    /** Adds a person through the admin path (the first owner's); answers their user id. */
    String addUser(Made a, String email, String... groups) {
        return addUserAs(a, a.owner(), email, groups);
    }

    String addUserAs(Made a, String admin, String email, String... groups) {
        return call(POST, a.users(), admin, Map.of("email", email, "name", email.substring(0, email.indexOf('-')), "groups", List.of(groups)), CREATED)
                .get("userId").asText();
    }

    JsonNode signIn(String subject, String email, List<String> groups) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("issuer", ISSUER, "subject", subject, "email", email));
        if (groups != null) {
            body.put("groups", groups);
        }
        return call(POST, RESOLVE, internal(), body, OK);
    }

    String createGroup(Made a, String name, List<String> idpGroups) {
        return call(POST, a.base() + "/groups", a.owner(), Map.of("name", name, "idpGroups", idpGroups), CREATED).get("groupId").asText();
    }

    String groupId(Made a, String name) {
        return idOf(call(GET, a.base() + "/groups?limit=200", a.owner(), null, OK).get("items"), "name", name, "groupId");
    }

    /** This account's part of a group sync run over HTTP. */
    JsonNode sync(Made a) {
        JsonNode report = call(POST, GROUP_SYNC, internal(), null, OK);
        assertThat(report.get("startedAt").asText()).isNotBlank();
        assertThat(report.get("finishedAt").asText()).isNotBlank();
        for (JsonNode account : report.get("accounts")) {
            if (a.accountId().equals(account.get("accountId").asText())) {
                return account;
            }
        }
        throw new AssertionError("the run left out account " + a.accountId() + ": " + report);
    }

    /** A sync report's changes as {@code <email> <group>}. */
    static List<String> changes(JsonNode list) {
        List<String> values = new ArrayList<>();
        for (JsonNode change : list) {
            values.add(change.get("email").asText() + " " + change.get("group").asText());
        }
        return values;
    }

    List<EventEnvelope> events(String accountId) {
        return outbox.after(0, 100_000).filter(e -> accountId.equals(e.partitionKey())).map(OutboxEntry::event).collectList().block();
    }

    static void member(InMemoryDirectory directory, String group, String... emails) {
        directory.put(group, java.util.Arrays.stream(emails).map(e -> new DirectoryGroup.Member(e, null)).toList());
    }

    @Test
    void signInGivesAndTakesTheGroupsTheIdentityProviderMapsAndLeavesTheAdminsAlone() {
        Made a = account();
        String sreTeam = "lc-sre-" + next() + "-" + RUN;
        createGroup(a, "sre", List.of(sreTeam));
        String membersId = groupId(a, "members");
        String pat = email("pat");

        JsonNode first = signIn("pat-" + RUN, pat, List.of(a.team(), sreTeam, "unrelated"));
        String patId = first.get("user").get("userId").asText();
        assertThat(strings(first.get("accounts").get(0).get("groups"))).containsExactlyInAnyOrder("members", "sre");
        JsonNode user = call(GET, a.user(patId), a.owner(), null, OK);
        assertThat(strings(user.get("idpGroups"))).containsExactlyInAnyOrder("members", "sre");
        assertThat(user.get("excludedGroups")).isEmpty();
        long joined = user.get("version").asLong();

        // left the sre team at the identity provider: the group goes at the next sign-in
        signIn("pat-" + RUN, pat, List.of(a.team()));
        user = call(GET, a.user(patId), a.owner(), null, OK);
        assertThat(strings(user.get("groups"))).containsExactly("members");
        assertThat(user.get("version").asLong()).isEqualTo(joined + 1);
        signIn("pat-" + RUN, pat, List.of(a.team()));
        assertThat(call(GET, a.user(patId), a.owner(), null, OK).get("version").asLong()).as("nothing to change, no new version").isEqualTo(joined + 1);

        // an admin takes members away: excluded, so the provider's team no longer brings it back
        JsonNode removed = call(PATCH, a.user(patId), a.owner(), Map.of("groups", List.of()), OK);
        assertThat(removed.get("groups")).isEmpty();
        assertThat(strings(removed.get("excludedGroups"))).containsExactly("members");
        signIn("pat-" + RUN, pat, List.of(a.team(), sreTeam));
        user = call(GET, a.user(patId), a.owner(), null, OK);
        assertThat(strings(user.get("groups"))).as("sre is mapped and not excluded, members stays out").containsExactly("sre");
        assertThat(strings(user.get("excludedGroups"))).containsExactly("members");

        // giving it back makes it the admin's: no sign-in takes it away
        JsonNode back = call(PATCH, a.user(patId), a.owner(), Map.of("groups", List.of("sre", "members")), OK);
        assertThat(back.get("excludedGroups")).isEmpty();
        assertThat(strings(back.get("idpGroups"))).containsExactly("sre");
        signIn("pat-" + RUN, pat, List.of());
        user = call(GET, a.user(patId), a.owner(), null, OK);
        assertThat(strings(user.get("groups"))).as("an empty groups claim takes what the provider gave, not what the admin gave").containsExactly("members");
        long settled = user.get("version").asLong();
        signIn("pat-" + RUN, pat, null);
        assertThat(call(GET, a.user(patId), a.owner(), null, OK).get("version").asLong()).as("without a groups claim nothing is synced").isEqualTo(settled);

        EventEnvelope adminChange = events(a.accountId()).stream()
                .filter(e -> "UserChanged".equals(e.type()) && patId.equals(e.aggregateId()) && e.data().get("snapshot").has("excludedGroupIds"))
                .findFirst().orElseThrow();
        assertThat(strings(adminChange.data().get("snapshot").get("excludedGroupIds"))).containsExactly(membersId);
    }

    @Test
    void signInVisitsTheAccountsNothingMapsThePersonToAnyMore() {
        Made a = account();
        Made b = account();
        String lee = email("lee");
        JsonNode both = signIn("lee-" + RUN, lee, List.of(a.team(), b.team()));
        String leeId = both.get("user").get("userId").asText();
        assertThat(both.get("accounts")).hasSize(2);

        JsonNode onlyA = signIn("lee-" + RUN, lee, List.of(a.team()));
        assertThat(call(GET, b.user(leeId), b.owner(), null, OK).get("groups")).as("b's members came from b's team, which lee left").isEmpty();
        assertThat(strings(call(GET, a.user(leeId), a.owner(), null, OK).get("groups"))).containsExactly("members");
        assertThat(onlyA.get("accounts")).hasSize(2);
        EventEnvelope left = events(b.accountId()).stream().filter(e -> "UserChanged".equals(e.type()) && leeId.equals(e.aggregateId()))
                .reduce((x, y) -> y).orElseThrow();
        assertThat(left.data().get("action").asText()).isEqualTo("updated");
        assertThat(left.data().get("snapshot").get("groupIds")).isEmpty();
        assertThat(left.data().get("snapshot").has("idpGroupIds")).isFalse();
        assertThat(left.actor()).isEqualTo("svc:console-bff");
    }

    @Test
    void theNightlySyncFollowsTheDirectory() {
        Made a = account();
        String sreTeam = "lc-sre-" + next() + "-" + RUN;
        createGroup(a, "sre", List.of(sreTeam));
        String dana = email("dana");
        String mateo = email("mateo");
        String ana = email("ana");
        String noah = email("noah");
        String danaId = addUser(a, dana, "viewers");
        String mateoId = addUser(a, mateo, "viewers");
        addUser(a, ana, "members");
        member(directory, a.team(), dana, mateo, noah);
        member(directory, sreTeam, dana.toUpperCase(java.util.Locale.ROOT));

        JsonNode first = sync(a);
        assertThat(changes(first.get("added"))).containsExactlyInAnyOrder(dana + " members", dana + " sre", mateo + " members");
        assertThat(first.get("removed")).isEmpty();
        assertThat(first.get("skipped")).isEmpty();
        assertThat(first.get("missingIdpGroups")).isEmpty();
        assertThat(first.get("owningTeamMissing").asBoolean()).isFalse();
        assertThat(first.has("error")).isFalse();
        JsonNode danaNow = call(GET, a.user(danaId), a.owner(), null, OK);
        assertThat(strings(danaNow.get("groups"))).containsExactlyInAnyOrder("viewers", "members", "sre");
        assertThat(strings(danaNow.get("idpGroups"))).containsExactlyInAnyOrder("members", "sre");
        assertThat(call(GET, a.users() + "?q=" + noah, a.owner(), null, OK).get("items")).as("the sync gives no one a membership").isEmpty();

        JsonNode again = sync(a);
        assertThat(again.get("added")).isEmpty();
        assertThat(again.get("removed")).isEmpty();
        assertThat(call(GET, a.user(danaId), a.owner(), null, OK).get("version").asLong()).isEqualTo(danaNow.get("version").asLong());

        // an admin (or an access review) takes sre from dana: it stays taken
        String sreId = groupId(a, "sre");
        call(PATCH, a.base() + "/groups/" + sreId, a.owner(), Map.of("members", List.of()), OK);
        assertThat(sync(a).get("added")).isEmpty();
        assertThat(strings(call(GET, a.user(danaId), a.owner(), null, OK).get("excludedGroups"))).containsExactly("sre");

        // dana leaves the team: members goes; ana's members is the admin's and stays
        member(directory, a.team(), mateo, noah);
        JsonNode left = sync(a);
        assertThat(changes(left.get("removed"))).containsExactly(dana + " members");
        assertThat(strings(call(GET, a.user(danaId), a.owner(), null, OK).get("groups"))).containsExactly("viewers");

        // the owning team disappears: the account is marked, and what the team gave goes
        directory.remove(a.team());
        JsonNode gone = sync(a);
        assertThat(gone.get("owningTeamMissing").asBoolean()).isTrue();
        assertThat(strings(gone.get("missingIdpGroups"))).containsExactly(a.team());
        assertThat(changes(gone.get("removed"))).containsExactly(mateo + " members");
        assertThat(call(GET, a.base(), internal(), null, OK).get("owningTeamMissing").asBoolean()).isTrue();
        assertThat(sync(a).get("removed")).isEmpty();

        member(directory, a.team(), mateo);
        JsonNode found = sync(a);
        assertThat(found.get("owningTeamMissing").asBoolean()).isFalse();
        assertThat(changes(found.get("added"))).containsExactly(mateo + " members");
        assertThat(call(GET, a.base(), internal(), null, OK).has("owningTeamMissing")).as("cleared once the team is back").isFalse();

        List<EventEnvelope> events = events(a.accountId());
        List<JsonNode> marks = events.stream().filter(e -> "AccountChanged".equals(e.type()) && "updated".equals(e.data().get("action").asText()))
                .map(e -> e.data().get("snapshot")).toList();
        assertThat(marks).hasSize(2);
        assertThat(marks.get(0).get("owningTeamMissing").asBoolean()).isTrue();
        assertThat(marks.get(1).has("owningTeamMissing")).isFalse();
        assertThat(events.stream().filter(e -> "UserChanged".equals(e.type()) && danaId.equals(e.aggregateId())).map(e -> e.data().get("action").asText()))
                .as("added, then sre taken by the admin, then members gone").containsExactly("created", "updated", "updated", "updated");
        assertThat(events.stream().filter(e -> "UserChanged".equals(e.type()) && mateoId.equals(e.aggregateId())).count()).isEqualTo(4);

        call(POST, GROUP_SYNC, a.owner(), null, NOT_FOUND);
    }

    @Test
    void aSyncNeverTakesTheOwnersGroupFromTheLastActiveOwner() {
        Made a = account();
        String ownersTeam = "lc-owners-" + next() + "-" + RUN;
        call(PATCH, a.base() + "/groups/" + groupId(a, "owners"), a.owner(), Map.of("idpGroups", List.of(ownersTeam)), OK);
        String quinn = email("quinn");
        String quinnId = signIn("quinn-" + RUN, quinn, List.of(ownersTeam)).get("user").get("userId").asText();
        String quinnToken = bearer(quinnId, a.accountId(), List.of("owners"));
        send(DELETE, a.user(a.ownerId()), quinnToken, null, Map.of()).expectStatus().isNoContent();

        signIn("quinn-" + RUN, quinn, List.of());
        assertThat(strings(call(GET, a.user(quinnId), quinnToken, null, OK).get("groups"))).as("kept: quinn is the last active owner").containsExactly("owners");

        directory.put(ownersTeam, List.of());
        directory.put(a.team(), List.of());
        JsonNode kept = sync(a);
        assertThat(kept.get("removed")).isEmpty();
        assertThat(changes(kept.get("skipped"))).containsExactly(quinn + " owners");
        assertThat(kept.get("skipped").get(0).get("reason").asText()).contains("last active owner");
        assertThat(kept.get("skipped").get(0).get("userId").asText()).isEqualTo(quinnId);

        addUserAs(a, quinnToken, email("rowan"), "owners");
        JsonNode removed = call(POST, GROUP_SYNC, internal(), null, OK);
        JsonNode mine = null;
        for (JsonNode account : removed.get("accounts")) {
            if (a.accountId().equals(account.get("accountId").asText())) {
                mine = account;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(changes(mine.get("removed"))).as("another active owner exists now").containsExactly(quinn + " owners");
        assertThat(mine.get("skipped")).isEmpty();
    }

    @Test
    void closingAnAccountEndsWhatItsPeopleCouldStillUse() {
        Made a = account();
        String mia = email("mia");
        String miaId = addUser(a, mia, "viewers");
        String viewer = bearer(miaId, a.accountId(), List.of("viewers"));
        String keys = a.base() + "/access-keys";
        JsonNode ownerKey = call(POST, keys, a.owner(), Map.of("userId", a.ownerId()), CREATED);
        JsonNode miaKey = call(POST, keys, viewer, Map.of("userId", miaId), CREATED);
        JsonNode oldKey = call(POST, keys, viewer, Map.of("userId", miaId), CREATED);
        send(DELETE, keys + "/" + oldKey.get("keyId").asText(), viewer, null, Map.of()).expectStatus().isNoContent();
        String vms = "wfrn:wf:compute:global:" + a.accountId() + ":vm/*";
        String requestId = call(POST, a.base() + "/access-requests", viewer, Map.of("action", "compute:create", "resource", vms, "reason",
                "I deploy the test machines"), ACCEPTED).get("requestId").asText();
        String quotaRequestId = call(POST, a.base() + "/quotas/resources.vm:request-increase", viewer, Map.of("limit", 200, "reason",
                "the team needs more test machines"), ACCEPTED).get("requestId").asText();
        long version = call(GET, a.base(), internal(), null, OK).get("version").asLong();
        int before = events(a.accountId()).size();

        String close = a.base() + ":close";
        call(POST, close, a.owner(), Map.of("reason", "moved to the new platform"), NOT_FOUND);
        call(POST, close, internal(), Map.of("reason", " "), BAD_REQUEST);
        call(POST, close, internal(), Map.of(), BAD_REQUEST);
        call(POST, close, internal(), Map.of("reason", "x".repeat(2001)), BAD_REQUEST);
        call(POST, "/v1/accounts/999999999999:close", internal(), Map.of("reason", "nobody"), NOT_FOUND);
        JsonNode closed = call(POST, close, internal(), Map.of("reason", "moved to the new platform"), ACCEPTED);
        assertThat(closed.get("status").asText()).isEqualTo("closed");
        assertThat(closed.get("closedAt").asText()).isNotBlank();
        assertThat(closed.get("closeReason").asText()).isEqualTo("moved to the new platform");
        assertThat(closed.get("version").asLong()).isEqualTo(version + 1);
        assertThat(closed.get("platformServiceAccountId").asText()).as("the platform service account stays").isNotBlank();
        assertThat(closed.has("closed")).as("status says it; the contract's Account has no closed field").isFalse();

        assertThat(call(GET, keys + "/" + ownerKey.get("keyId").asText(), internal(), null, OK).get("status").asText()).isEqualTo("revoked");
        assertThat(call(GET, keys + "/" + miaKey.get("keyId").asText(), internal(), null, OK).get("status").asText()).isEqualTo("revoked");
        assertThat(accessKeys.verify(miaKey.get("keyId").asText(), miaKey.get("secretPart").asText()).block()).isNull();
        assertThat(call(GET, a.base() + "/access-requests/" + requestId, internal(), null, OK).get("status").asText()).isEqualTo("expired");
        assertThat(call(GET, a.base() + "/quota-requests/" + quotaRequestId, internal(), null, OK).get("status").asText()).isEqualTo("withdrawn");

        List<EventEnvelope> after = events(a.accountId());
        List<EventEnvelope> closing = after.subList(before, after.size());
        assertThat(closing.stream().map(EventEnvelope::type)).containsExactlyInAnyOrder("AccessKeyChanged", "AccessKeyChanged", "AccessRequestChanged",
                "QuotaChanged", "AccountChanged");
        EventEnvelope last = closing.get(closing.size() - 1);
        assertThat(last.type()).as("the account's event comes last").isEqualTo("AccountChanged");
        assertThat(last.data().get("action").asText()).isEqualTo("closed");
        assertThat(last.key()).isEqualTo("AccountChanged:" + a.accountId() + ":" + a.accountId() + ":" + (version + 1));
        assertThat(last.data().get("snapshot").get("status").asText()).isEqualTo("closed");
        assertThat(last.data().get("snapshot").get("closedAt").asText()).isEqualTo(closed.get("closedAt").asText());
        assertThat(last.data().get("snapshot").get("closeReason").asText()).isEqualTo("moved to the new platform");
        assertThat(closing.stream().filter(e -> "AccessKeyChanged".equals(e.type())).map(e -> e.data().get("action").asText())).containsOnly("revoked");
        assertThat(closing.stream().filter(e -> "AccessKeyChanged".equals(e.type())).map(EventEnvelope::aggregateId))
                .containsExactlyInAnyOrder(ownerKey.get("keyId").asText(), miaKey.get("keyId").asText());
        EventEnvelope expired = closing.stream().filter(e -> "AccessRequestChanged".equals(e.type())).findFirst().orElseThrow();
        assertThat(expired.data().get("action").asText()).isEqualTo("expired");
        EventEnvelope withdrawn = closing.stream().filter(e -> "QuotaChanged".equals(e.type())).findFirst().orElseThrow();
        assertThat(withdrawn.data().get("snapshot").get("request").get("status").asText()).isEqualTo("withdrawn");

        // closed is terminal
        call(POST, close, internal(), Map.of("reason", "again"), CONFLICT);
        assertThat(events(a.accountId())).as("a second close adds nothing").hasSize(after.size());
        call(PATCH, a.base(), internal(), Map.of("status", "active"), CONFLICT);
        call(PATCH, a.base(), internal(), Map.of("status", "suspended"), CONFLICT);
        assertThat(call(GET, a.base(), internal(), null, OK).get("status").asText()).isEqualTo("closed");
    }

    @Test
    void aFormerMemberIsRefusedOnceTheAccountIsClosed() {
        Made a = account();
        String mia = email("mia");
        String miaId = addUser(a, mia, "members");
        String member = bearer(miaId, a.accountId(), List.of("members"));
        signIn("mia-" + RUN, mia, List.of());
        assertThat(call(GET, a.users(), member, null, OK).get("items")).hasSize(2);
        String vm = "wfrn:wf:compute:global:" + a.accountId() + ":vm/vm-1";
        assertThat(call(POST, "/v1/authorize", member, Map.of("principal", Map.of("userId", miaId, "accountId", a.accountId()), "checks",
                List.of(Map.of("action", "compute:get", "resource", vm))), OK).get("decisions").get(0).get("allowed").asBoolean()).isTrue();

        call(POST, a.base() + ":close", internal(), Map.of("reason", "the team was dissolved"), ACCEPTED);

        JsonNode denied = call(GET, a.users(), member, null, FORBIDDEN);
        assertThat(denied.get("denied").get("reason").asText()).isEqualTo("account closed");
        call(POST, a.base() + "/access-keys", member, Map.of("userId", miaId), CONFLICT);
        call(POST, a.base() + "/access-keys", internal(), Map.of("userId", miaId), CONFLICT);
        call(POST, a.base() + "/access-requests", member, Map.of("action", "iam:create", "role", "account-admin", "reason", "let me back in"), FORBIDDEN);
        call(POST, a.users(), a.owner(), Map.of("email", email("late")), FORBIDDEN);
        JsonNode renamed = call(PATCH, a.base(), a.owner(), Map.of("name", a.name() + " reopened"), FORBIDDEN);
        assertThat(renamed.get("denied").get("reason").asText()).as("nor does its owner change it").isEqualTo("account closed");
        JsonNode decision = call(POST, "/v1/authorize", member, Map.of("principal", Map.of("userId", miaId, "accountId", a.accountId()), "checks",
                List.of(Map.of("action", "compute:get", "resource", vm))), OK).get("decisions").get(0);
        assertThat(decision.get("allowed").asBoolean()).isFalse();
        assertThat(decision.get("reason").asText()).isEqualTo("account closed");
        call(POST, RESOLVE, internal(), Map.of("issuer", ISSUER, "subject", "mia-" + RUN, "email", mia, "groups", List.of()), NOT_FOUND);
    }

    @Test
    void aSuspendedAccountClosesAndOnlyCloseCloses() {
        Made a = account();
        call(PATCH, a.base(), internal(), Map.of("status", "closed"), BAD_REQUEST);
        assertThat(call(PATCH, a.base(), internal(), Map.of("status", "suspended"), OK).get("status").asText()).isEqualTo("suspended");
        assertThat(call(PATCH, a.base(), internal(), Map.of("status", "active"), OK).get("status").asText()).as("suspended stays reversible")
                .isEqualTo("active");
        call(PATCH, a.base(), internal(), Map.of("status", "suspended"), OK);
        JsonNode closed = body(send(POST, a.base() + ":close", internal(), Map.of("reason", "never came back"), Map.of("Idempotency-Key", "close-" + RUN))
                .expectStatus().isEqualTo(ACCEPTED));
        assertThat(closed.get("status").asText()).isEqualTo("closed");
        JsonNode replay = body(send(POST, a.base() + ":close", internal(), Map.of("reason", "never came back"), Map.of("Idempotency-Key", "close-" + RUN))
                .expectStatus().isEqualTo(ACCEPTED).expectHeader().valueEquals("Idempotent-Replay", "true"));
        assertThat(replay.get("closedAt").asText()).as("a retry with its key answers the first closure").isEqualTo(closed.get("closedAt").asText());
        JsonNode recosted = call(PATCH, a.base(), internal(), Map.of("costCentre", "cc-final"), OK);
        assertThat(recosted.get("status").asText()).as("a closed account's other fields still change").isEqualTo("closed");
        assertThat(recosted.get("costCentre").asText()).isEqualTo("cc-final");
    }

    @Test
    void aCostCentreIsSetChangedAndCleared() {
        Made a = account(Map.of("costCentre", "  cc-1234  "));
        JsonNode created = call(GET, a.base(), a.owner(), null, OK);
        assertThat(created.get("costCentre").asText()).isEqualTo("cc-1234");
        assertThat(call(PATCH, a.base(), internal(), Map.of("costCentre", "cc-9"), OK).get("costCentre").asText()).isEqualTo("cc-9");
        assertThat(call(PATCH, a.base(), a.owner(), Map.of("name", a.name() + " renamed"), OK).get("costCentre").asText()).as("absent keeps it")
                .isEqualTo("cc-9");
        String operator = bearer(addUser(a, email("op"), "members"), a.accountId(), List.of("members"));
        JsonNode refused = call(PATCH, a.base(), operator, Map.of("costCentre", "cc-mine"), FORBIDDEN);
        assertThat(refused.get("denied").get("action").asText()).as("an account change is the account admin's").isEqualTo("account:update");
        assertThat(call(PATCH, a.base(), internal(), Map.of("costCentre", ""), OK).has("costCentre")).as("empty clears it").isFalse();
        assertThat(call(GET, a.base(), a.owner(), null, OK).has("costCentre")).isFalse();
        call(PATCH, a.base(), internal(), Map.of("costCentre", "c".repeat(65)), BAD_REQUEST);
        call(POST, "/v1/accounts", internal(), Map.of("name", "Too costly " + RUN, "owningTeam", "lc-team-x-" + RUN, "ownerEmail", email("cc"), "costCentre",
                "c".repeat(65)), BAD_REQUEST);
        Made blank = account(Map.of("costCentre", "   "));
        assertThat(call(GET, blank.base(), blank.owner(), null, OK).has("costCentre")).as("a blank one is none").isFalse();

        List<JsonNode> snapshots = events(a.accountId()).stream().filter(e -> "AccountChanged".equals(e.type())).map(e -> e.data().get("snapshot")).toList();
        assertThat(snapshots).hasSize(4);
        assertThat(snapshots.get(0).get("costCentre").asText()).isEqualTo("cc-1234");
        assertThat(snapshots.get(1).get("costCentre").asText()).isEqualTo("cc-9");
        assertThat(snapshots.get(2).get("costCentre").asText()).isEqualTo("cc-9");
        assertThat(snapshots.get(3).has("costCentre")).isFalse();
    }
}
