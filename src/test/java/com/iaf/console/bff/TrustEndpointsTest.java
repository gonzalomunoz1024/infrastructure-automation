package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/** The keys other services verify the BFF's tokens with, the demo identity provider's discovery, and health. */
class TrustEndpointsTest extends DemoApp {

    @Test
    void theJwksPublishesTheSigningKeyWithoutItsPrivatePart() {
        Console.Reply reply = console.get("/.well-known/jwks.json", null);
        assertEquals(200, reply.status(), reply.body());
        JsonNode key = reply.json().path("keys").get(0);
        assertEquals("EC", key.path("kty").asText());
        assertEquals("P-256", key.path("crv").asText());
        assertFalse(key.path("kid").asText().isBlank());
        assertFalse(key.has("d"), "the private part never leaves the process");
        assertTrue(reply.headers().getCacheControl().contains("max-age"));
    }

    @Test
    void theDemoIdentityProviderDescribesItself() {
        Console.Reply reply = console.get("/demo-idp/.well-known/openid-configuration", null);
        assertEquals(200, reply.status(), reply.body());
        assertEquals(BASE + "/demo-idp", reply.json().path("issuer").asText());
        assertTrue(reply.json().path("authorization_endpoint").asText().endsWith("/demo-idp/authorize"));
        assertTrue(reply.json().path("token_endpoint").asText().endsWith("/demo-idp/token"));
        assertTrue(reply.json().path("code_challenge_methods_supported").toString().contains("S256"));
        Console.Reply jwks = console.get(reply.json().path("jwks_uri").asText().replace(BASE, ""), null);
        assertEquals(200, jwks.status());
        Console.Reply picker = console.get("/demo-idp/authorize?client_id=console&redirect_uri=" + BASE + "/auth/callback&response_type=code&state=s&nonce=n"
                + "&code_challenge=abc&code_challenge_method=S256", null);
        assertEquals(200, picker.status(), picker.body());
        assertTrue(picker.body().contains("gonzalo"), "the picker lists the demo people");
    }

    @Test
    void healthAndRegions() {
        assertEquals(200, console.get("/actuator/health", null).status());
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply regions = console.get("/console/v1/regions", gonzalo);
        assertEquals(200, regions.status(), regions.body());
        assertEquals("global", regions.json().path("items").get(0).path("name").asText());
        assertEquals("available", regions.json().path("items").get(0).path("status").asText());
    }
}
