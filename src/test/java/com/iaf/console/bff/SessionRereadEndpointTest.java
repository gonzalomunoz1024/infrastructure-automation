package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** Review finding 1, end to end: a change to someone's memberships reaches their session at its next request. */
class SessionRereadEndpointTest extends DemoApp {

    @Test
    void aDowngradeAndThenARemovalReachTheSessionAtItsNextRequest() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply added = console.post("/console/v1/iam/users", gonzalo,
                Map.of("userId", "user_noah", "email", "noah@example.com", "name", "Noah Berg", "groups", List.of("owners")));
        assertTrue(added.status() == 200 || added.status() == 201, added.body());
        try {
            Console.Signed noah = console.signIn("noah");
            assertTrue(noah.view().path("account").path("roles").toString().contains("account-admin"), noah.view().toString());

            Console.Reply downgraded = console.send(HttpMethod.PATCH, "/console/v1/iam/users/user_noah", gonzalo, Map.of("groups", List.of("viewers")),
                    Map.of());
            assertEquals(200, downgraded.status(), downgraded.body());
            Console.Reply view = console.get("/console/v1/session", noah);
            assertEquals(200, view.status(), view.body());
            assertEquals("[\"viewer\"]", view.json().path("account").path("roles").toString(), "the session no longer holds account-admin");

            Console.Reply removed = console.send(HttpMethod.DELETE, "/console/v1/iam/users/user_noah", gonzalo, null, Map.of());
            assertTrue(removed.status() == 204 || removed.status() == 200, removed.body());
            assertEquals(401, console.get("/console/v1/session", noah).status(), "out of the account: the session is gone");
            assertEquals(401, console.get("/console/v1/types", noah).status());
        } finally {
            console.send(HttpMethod.DELETE, "/console/v1/iam/users/user_noah", gonzalo, null, Map.of());
        }
    }
}
