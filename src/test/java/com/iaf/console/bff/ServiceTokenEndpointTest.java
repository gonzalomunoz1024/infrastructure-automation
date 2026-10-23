package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.trust.application.Assertions;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** {@code POST /internal/v1/token} as a console service calls it: form-encoded, no session, answered in RFC 6749's shapes. */
class ServiceTokenEndpointTest extends DemoApp {

    private final WebClient web = WebClient.create(BASE);

    private Console.Reply token(MultiValueMap<String, String> form) {
        return console.call(web.post().uri("/internal/v1/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData(form)));
    }

    private static MultiValueMap<String, String> form(String assertion, String... audiences) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        form.add("client_assertion", assertion);
        for (String audience : audiences) {
            form.add("audience", audience);
        }
        return form;
    }

    @Test
    void aServiceGetsATokenThatVerifiesAgainstTheBffsKeys() throws Exception {
        Console.Reply reply = token(form(Assertions.own(TEST_CLIENT_KEY, TEST_CLIENT, Instant.now()), "console-state-service"));
        assertEquals(200, reply.status(), reply.body());
        assertTrue(reply.headers().getCacheControl().contains("no-store"));
        JsonNode body = reply.json();
        assertEquals("Bearer", body.path("token_type").asText());
        assertEquals(300, body.path("expires_in").asInt());
        assertEquals("[\"console-state-service\"]", body.path("audience").toString());

        SignedJWT jwt = SignedJWT.parse(body.path("access_token").asText());
        JWKSet keys = JWKSet.parse(console.get("/.well-known/jwks.json", null).body());
        ECKey key = (ECKey) keys.getKeyByKeyId(jwt.getHeader().getKeyID());
        assertTrue(jwt.verify(new ECDSAVerifier(key.toECPublicKey())));
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertEquals("console-bff", claims.getIssuer());
        assertEquals(List.of("console-state-service"), claims.getAudience());
        assertEquals(TEST_CLIENT, claims.getSubject());
        assertEquals(TEST_CLIENT, claims.getStringClaim("svc"));
        assertFalse(claims.getClaims().containsKey("acct"));
    }

    @Test
    void theSearchIndexerProvesItselfWithItsServiceAccountToken() throws Exception {
        String serviceAccount = Assertions.serviceAccount(CLUSTER_KEY, CLUSTER_ISSUER, "console", "console-search-indexer", Instant.now());
        Console.Reply reply = token(form(serviceAccount));
        assertEquals(200, reply.status(), reply.body());
        JWTClaimsSet claims = SignedJWT.parse(reply.json().path("access_token").asText()).getJWTClaimsSet();
        assertEquals("console-search-indexer", claims.getStringClaim("svc"));
        assertEquals(List.of("console-state-service", "console-identity-service"), claims.getAudience());
    }

    @Test
    void refusalsAreRfc6749Bodies() {
        MultiValueMap<String, String> password = form(Assertions.own(TEST_CLIENT_KEY, TEST_CLIENT, Instant.now()));
        password.set("grant_type", "password");
        Console.Reply grant = token(password);
        assertEquals(400, grant.status(), grant.body());
        assertEquals("unsupported_grant_type", grant.json().path("error").asText());
        assertFalse(grant.json().path("error_description").asText().isBlank());
        assertTrue(grant.headers().getCacheControl().contains("no-store"));

        Console.Reply forged = token(form(Assertions.own(DemoApp.ecKey("test-client-1"), TEST_CLIENT, Instant.now())));
        assertEquals(401, forged.status(), forged.body());
        assertEquals("invalid_client", forged.json().path("error").asText());

        Console.Reply target = token(form(Assertions.own(TEST_CLIENT_KEY, TEST_CLIENT, Instant.now()), "console-bff"));
        assertEquals(400, target.status(), target.body());
        assertEquals("invalid_target", target.json().path("error").asText());

        Console.Reply json = console.call(web.post().uri("/internal/v1/token").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"grant_type\":\"client_credentials\"}"));
        assertEquals(415, json.status(), json.body());
    }
}
