package com.iaf.console.bff.session.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.kit.events.EventEnvelope;

/** Which identity changes make which sessions read their memberships again (the identity service's rules). */
class MembershipChangeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT = "123456789012";

    @Test
    void anElevationIsItsPersons() throws Exception {
        assertEquals(List.of(new MembershipChange(MembershipChange.Scope.USER, "user_dana")),
                MembershipChange.of(event("ElevationChanged", "elevation", "elev_01", "expired",
                        "{\"elevationId\":\"elev_01\",\"accountId\":\"123456789012\",\"user\":{\"userId\":\"user_dana\"},\"until\":\"2026-10-23T09:00:00Z\"}")));
    }

    @Test
    void aUserUpdatedOrDeletedIsTheirs() throws Exception {
        assertEquals(List.of(new MembershipChange(MembershipChange.Scope.USER, "user_mia")),
                MembershipChange.of(event("UserChanged", "user", "user_mia", "deleted", "null")));
        assertEquals(List.of(new MembershipChange(MembershipChange.Scope.USER, "user_mia")),
                MembershipChange.of(event("UserChanged", "user", "user_mia", "updated", "{}")));
        assertEquals(List.of(), MembershipChange.of(event("UserChanged", "user", "user_mia", "created", "{}")));
    }

    @Test
    void groupsRolesAndPoliciesAreTheAccounts() throws Exception {
        for (String type : List.of("GroupChanged", "RoleChanged", "PolicyChanged")) {
            assertEquals(List.of(new MembershipChange(MembershipChange.Scope.ACCOUNT, ACCOUNT)),
                    MembershipChange.of(event(type, "group", "grp_1", "updated", "{}")), type);
        }
    }

    @Test
    void anAccountClosedOrSuspendedIsTheAccounts() throws Exception {
        assertEquals(List.of(new MembershipChange(MembershipChange.Scope.ACCOUNT, ACCOUNT)),
                MembershipChange.of(event("AccountChanged", "account", ACCOUNT, "suspended", "{}")));
        assertEquals(List.of(new MembershipChange(MembershipChange.Scope.ACCOUNT, ACCOUNT)),
                MembershipChange.of(event("AccountChanged", "account", ACCOUNT, "closed", "{}")));
        assertEquals(List.of(), MembershipChange.of(event("AccountChanged", "account", ACCOUNT, "updated", "{}")));
        assertEquals(List.of(), MembershipChange.of(event("QuotaChanged", "quota", "q", "updated", "{}")));
    }

    private static EventEnvelope event(String type, String aggregate, String id, String action, String snapshot) throws Exception {
        return new EventEnvelope("1.0", 1, "evt_1", type + ":" + ACCOUNT + ":" + id + ":1", 1L, type, Instant.parse("2026-10-23T08:00:00Z"), aggregate, id,
                "gonzalo", null, new EventEnvelope.Context(ACCOUNT, null, null, null), null,
                JSON.readTree("{\"version\":1,\"action\":\"" + action + "\",\"snapshot\":" + snapshot + "}"));
    }
}
