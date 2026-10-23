package com.iaf.console.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.identity.common.auth.InternalJwts;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

/**
 * The whole service on a random port against an in-process platform-fake, with a test key that
 * signs the BFF's internal JWTs. Subclasses add the profile (memory, or mongo when a database is
 * at hand) and share one context per configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class WithService {

    static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    static final PlatformFake FAKE = PlatformFake.builder().demo(false).stepDelay(Duration.ZERO).build();
    static final int FAKE_PORT = FAKE.start(0);
    static final ECKey KEY = generate();
    static final InternalJwts.Signer SIGNER = new InternalJwts.Signer(KEY, "console-bff", "console-identity-service", Clock.systemUTC());
    static final String BUNDLE_TOKEN = "bundle-" + UUID.randomUUID();
    /** Distinguishes this run's emails and names from another context's in the same JVM. */
    static final String RUN = Long.toHexString(System.nanoTime() & 0xffffffL);
    static final String BUNDLE_PATH = "/internal/opa/bundles/console-authz.tar.gz";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    WebTestClient web;

    @DynamicPropertySource
    static void serviceProperties(DynamicPropertyRegistry registry) {
        registry.add("console.identity.platform.base-url", FAKE::baseUrl);
        registry.add("console.identity.auth.jwks", () -> new JWKSet(KEY.toPublicJWK()).toString());
        registry.add("console.identity.auth.bundle-token", () -> BUNDLE_TOKEN);
    }

    private static ECKey generate() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("test-bff").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The console-internal principal, as the BFF or another console service calls. */
    static String internal() {
        return "Bearer " + SIGNER.service("console-bff", Duration.ofMinutes(10));
    }

    /** A user acting in an account, with the managed roles the managed groups carry, as the BFF derives them from the membership. */
    static String bearer(String userId, String accountId, List<String> groups) {
        return bearer(userId, accountId, groups, groups.stream().map(WithService::managedRole).filter(java.util.Objects::nonNull).toList());
    }

    static String bearer(String userId, String accountId, List<String> groups, List<String> roles) {
        return "Bearer " + SIGNER.user(userId, accountId, "global", groups, roles, Duration.ofMinutes(10));
    }

    private static String managedRole(String group) {
        return switch (group) {
            case "owners" -> "account-admin";
            case "members" -> "operator";
            case "viewers" -> "viewer";
            case "auditors" -> "auditor";
            default -> null;
        };
    }

    static int next() {
        return COUNTER.incrementAndGet();
    }

    WebTestClient.ResponseSpec send(HttpMethod method, String uri, String auth, Object body, Map<String, String> headers) {
        WebTestClient.RequestBodySpec spec = web.method(method).uri(uri).accept(MediaType.APPLICATION_JSON);
        if (auth != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, auth);
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            spec = spec.header(h.getKey(), h.getValue());
        }
        if (body != null) {
            return spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
        }
        return spec.exchange();
    }

    /** Sends and expects a status; answers the JSON body (a missing node when there is none). */
    JsonNode call(HttpMethod method, String uri, String auth, Object body, HttpStatus expected) {
        return body(send(method, uri, auth, body, Map.of()).expectStatus().isEqualTo(expected));
    }

    static JsonNode body(WebTestClient.ResponseSpec response) {
        byte[] bytes = response.expectBody().returnResult().getResponseBody();
        if (bytes == null || bytes.length == 0) {
            return MissingNode.getInstance();
        }
        try {
            return JSON.readTree(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> texts(JsonNode array, String field) {
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode n : array) {
            values.add(n.get(field).asText());
        }
        return values;
    }

    /** The values of an array of strings. */
    static List<String> strings(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode n : array) {
            values.add(n.asText());
        }
        return values;
    }

    static String idOf(JsonNode array, String nameField, String name, String idField) {
        for (JsonNode n : array) {
            if (name.equals(n.path(nameField).asText())) {
                return n.get(idField).asText();
            }
        }
        throw new AssertionError("no " + nameField + " '" + name + "' in " + array);
    }
}
