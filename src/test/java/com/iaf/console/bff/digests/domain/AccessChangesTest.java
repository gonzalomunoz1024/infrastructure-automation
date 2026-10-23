package com.iaf.console.bff.digests.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Decision 32.4: what an identity event granted, against the state the one before it left. */
class AccessChangesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant AT = Instant.parse("2026-10-14T09:00:00Z");

    @Test
    void aGroupsNewMembersAndRolesAreGrants() {
        AccessChanges.Change created = AccessChanges.of("group", "grp_1", "created", 1, null,
                json("{\"name\": \"data-approvers\", \"members\": [\"user_raj\"], \"roles\": [\"role_approver\"]}"), "user_gonzalo", AT);
        assertEquals(List.of(new AccessFact(AccessFact.MEMBERSHIP, "user_raj", "data-approvers", "user_gonzalo", AT),
                new AccessFact(AccessFact.ROLE, "data-approvers", "role_approver", "user_gonzalo", AT)), created.facts());
        AccessChanges.Change updated = AccessChanges.of("group", "grp_1", "updated", 2, created.after(),
                json("{\"name\": \"data-approvers\", \"members\": [\"user_raj\", \"user_mia\"], \"roles\": [\"role_approver\"]}"), "user_gonzalo", AT);
        assertEquals(List.of(new AccessFact(AccessFact.MEMBERSHIP, "user_mia", "data-approvers", "user_gonzalo", AT)), updated.facts());
        AccessChanges.Change removed = AccessChanges.of("group", "grp_1", "updated", 3, updated.after(),
                json("{\"name\": \"data-approvers\", \"members\": [\"user_mia\"], \"roles\": []}"), "user_gonzalo", AT);
        assertEquals(List.of(), removed.facts(), "taking access away grants nothing");
        assertEquals(List.of("user_mia"), removed.after().members());
    }

    @Test
    void aChangeWhoseEarlierStateWasNeverSeenOrAReplayGrantsNothing() {
        JsonNode group = json("{\"name\": \"owners\", \"members\": [\"user_gonzalo\", \"user_noah\"]}");
        AccessChanges.Change unknown = AccessChanges.of("group", "grp_2", "updated", 7, null, group, "user_gonzalo", AT);
        assertEquals(List.of(), unknown.facts());
        assertEquals(7, unknown.after().version(), "the state is kept for the next change");
        AccessChanges.Change replay = AccessChanges.of("group", "grp_2", "updated", 7, unknown.after(),
                json("{\"name\": \"owners\", \"members\": [\"user_gonzalo\", \"user_noah\", \"user_eve\"]}"), "user_gonzalo", AT);
        assertEquals(List.of(), replay.facts());
        assertEquals(unknown.after(), replay.after());
    }

    @Test
    void aUsersNewGroupsAnAccessKeyAndAPolicyAllowingEverythingAreGrants() {
        SubjectState before = SubjectState.of(1, json("{\"groups\": [\"viewers\"]}"));
        AccessChanges.Change user = AccessChanges.of("user", "user_mia", "updated", 2, before, json("{\"groups\": [\"viewers\", \"members\"]}"),
                "user_gonzalo", AT);
        assertEquals(List.of(new AccessFact(AccessFact.MEMBERSHIP, "user_mia", "members", "user_gonzalo", AT)), user.facts());
        AccessChanges.Change key = AccessChanges.of("accessKey", "ck_1", "created", 1, null, json("{\"keyId\": \"ck_1\", \"userId\": \"user_mateo\"}"),
                "user_mateo", AT);
        assertEquals(List.of(new AccessFact(AccessFact.ACCESS_KEY, "ck_1", "user_mateo", "user_mateo", AT)), key.facts());
        assertEquals(List.of(), AccessChanges.of("accessKey", "ck_1", "rotated", 2, null, json("{\"keyId\": \"ck_1\"}"), "user_mateo", AT).facts());
        JsonNode narrow = json("{\"name\": \"ops\", \"statements\": [{\"effect\": \"allow\", \"actions\": [\"compute:*\"], \"resources\": [\"*\"]}]}");
        JsonNode wide = json("{\"name\": \"ops\", \"statements\": [{\"effect\": \"allow\", \"actions\": [\"*\"], \"resources\": [\"*\"]}]}");
        AccessChanges.Change first = AccessChanges.of("policy", "pol_1", "created", 1, null, narrow, "user_gonzalo", AT);
        assertEquals(List.of(), first.facts());
        AccessChanges.Change widened = AccessChanges.of("policy", "pol_1", "updated", 2, first.after(), wide, "user_gonzalo", AT);
        assertEquals(List.of(new AccessFact(AccessFact.WILDCARD_POLICY, "pol_1", "ops", "user_gonzalo", AT)), widened.facts());
        assertEquals(List.of(), AccessChanges.of("policy", "pol_1", "updated", 3, widened.after(), wide, "user_gonzalo", AT).facts(),
                "still allowing everything is no new grant");
        assertTrue(SubjectState.of(1, json("{\"statements\": [{\"effect\": \"allow\", \"actions\": [\"*:*\"]}]}")).allowsEverything());
        assertTrue(!SubjectState.of(1, json("{\"statements\": [{\"effect\": \"deny\", \"actions\": [\"*\"]}]}")).allowsEverything());
        assertNull(AccessChanges.of("group", "grp_1", "deleted", 4, before, null, "user_gonzalo", AT).after());
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
