package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.providers.domain.ProviderDefinition.Auth;
import com.iaf.platform.testing.LocalHttp;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WebClientHttpProbeTest {

    static final Auth NONE = new Auth("none", null, null);

    HttpServer server;
    String base;
    CredentialsPort credentials;
    WebClientHttpProbe probe;

    @BeforeEach
    void start() throws IOException {
        server = LocalHttp.start(ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            switch (path) {
                case "/doc" -> LocalHttp.respond(ex, 200, "openapi: 3.1.0");
                case "/redirect" -> {
                    ex.getResponseHeaders().add("Location", base + "/doc");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                }
                case "/to-metadata" -> {
                    ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                }
                case "/ok" -> LocalHttp.respond(ex, 200, "{}");
                case "/nohead" -> LocalHttp.respond(ex, "HEAD".equals(method) ? 405 : 200, "{}");
                case "/private" -> LocalHttp.respond(ex, "Bearer tok".equals(auth) ? 200 : 401, "{}");
                case "/keyed" -> LocalHttp.respond(ex, "k-1".equals(ex.getRequestHeaders().getFirst("X-Api-Key")) ? 204 : 403, "");
                case "/down" -> LocalHttp.respond(ex, 503, "{}");
                default -> LocalHttp.respond(ex, 404, "{}");
            }
        });
        base = LocalHttp.url(server);
        credentials = mock(CredentialsPort.class);
        probe = new WebClientHttpProbe(WebClient.builder(), credentials, true);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void fetchFollowsRedirectsAndReturnsTheDocument() {
        StepVerifier.create(probe.fetch(base + "/redirect")).expectNext("openapi: 3.1.0").verifyComplete();
    }

    @Test
    void neverCallsOrFollowsARedirectToACloudMetadataAddress() {
        StepVerifier.create(probe.fetch("http://169.254.169.254/latest/meta-data/"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("metadata"))
                .verify();
        StepVerifier.create(probe.fetch("http://metadata.google.internal/computeMetadata/v1/"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("metadata"))
                .verify();
        StepVerifier.create(probe.fetch(base + "/to-metadata"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTP 302"))
                .verify();
        StepVerifier.create(probe.probe("http://[fe80::1]:8080/", NONE))
                .assertNext(r -> {
                    assertThat(r.ok()).isFalse();
                    assertThat(r.message()).contains("link-local");
                })
                .verifyComplete();
    }

    @Test
    void fetchErrorsAreBadRequests() {
        StepVerifier.create(probe.fetch(base + "/missing"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTP 404"))
                .verify();
        StepVerifier.create(probe.fetch("ftp://x/doc")).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(probe.fetch(" ")).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void probeUsesHeadAndFallsBackToGet() {
        StepVerifier.create(probe.probe(base + "/ok", NONE))
                .assertNext(r -> {
                    assertThat(r.ok()).isTrue();
                    assertThat(r.message()).startsWith("HEAD 200 in ");
                })
                .verifyComplete();
        StepVerifier.create(probe.probe(base + "/nohead", NONE))
                .assertNext(r -> {
                    assertThat(r.ok()).isTrue();
                    assertThat(r.message()).startsWith("GET 200 in ");
                })
                .verifyComplete();
        StepVerifier.create(probe.probe(base + "/nothing-here", NONE))
                .assertNext(r -> {
                    assertThat(r.ok()).isTrue();
                    assertThat(r.message()).contains("GET 404").contains("host reachable");
                })
                .verifyComplete();
    }

    @Test
    void probeSendsTheResolvedCredential() {
        StepVerifier.create(probe.probe(base + "/private", NONE))
                .assertNext(r -> {
                    assertThat(r.ok()).isFalse();
                    assertThat(r.message()).contains("401").contains("credential");
                })
                .verifyComplete();
        when(credentials.resolve("vault:fleet/token")).thenReturn(Mono.just("tok"));
        StepVerifier.create(probe.probe(base + "/private", new Auth("bearer", "vault:fleet/token", null)))
                .assertNext(r -> assertThat(r.ok()).isTrue())
                .verifyComplete();
        when(credentials.resolve("vault:fleet/api-key")).thenReturn(Mono.just("k-1"));
        StepVerifier.create(probe.probe(base + "/keyed", new Auth("header", "vault:fleet/api-key", "X-Api-Key")))
                .assertNext(r -> assertThat(r.ok()).isTrue())
                .verifyComplete();
        when(credentials.resolve("vault:fleet/missing")).thenReturn(Mono.empty());
        StepVerifier.create(probe.probe(base + "/private", new Auth("bearer", "vault:fleet/missing", null)))
                .assertNext(r -> assertThat(r.ok()).isFalse())
                .verifyComplete();
    }

    @Test
    void serverErrorsAndUnreachableHostsAreNotOkButNeverThrow() throws IOException {
        StepVerifier.create(probe.probe(base + "/down", NONE))
                .assertNext(r -> {
                    assertThat(r.ok()).isFalse();
                    assertThat(r.message()).contains("server error");
                })
                .verifyComplete();
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        StepVerifier.create(probe.probe("http://127.0.0.1:" + closedPort + "/", NONE))
                .assertNext(r -> assertThat(r.ok()).isFalse())
                .verifyComplete();
        StepVerifier.create(probe.probe("not a url", NONE))
                .assertNext(r -> {
                    assertThat(r.ok()).isFalse();
                    assertThat(r.message()).contains("URL");
                })
                .verifyComplete();
    }

    @Test
    void exampleHostsAreSimulatedInTheDemo() {
        StepVerifier.create(probe.probe("https://vcenter-dev.example.com", new Auth("bearer", "vault:vsphere/token", null)))
                .assertNext(r -> {
                    assertThat(r.ok()).isTrue();
                    assertThat(r.durationMs()).isBetween(20L, 80L);
                    assertThat(r.message()).contains("Simulated");
                })
                .verifyComplete();
        assertThat(WebClientHttpProbe.isSimulated("example.com")).isTrue();
        assertThat(WebClientHttpProbe.isSimulated("api.example.com")).isTrue();
        assertThat(WebClientHttpProbe.isSimulated("example.com.evil.io")).isFalse();
        assertThat(WebClientHttpProbe.isSimulated("notexample.com")).isFalse();
        WebClientHttpProbe live = new WebClientHttpProbe(WebClient.builder(), credentials, false);
        StepVerifier.create(live.probe("http://127.0.0.1:1/", NONE).timeout(Duration.ofSeconds(20)))
                .assertNext(r -> assertThat(r.message()).doesNotContain("Simulated"))
                .verifyComplete();
    }
}
