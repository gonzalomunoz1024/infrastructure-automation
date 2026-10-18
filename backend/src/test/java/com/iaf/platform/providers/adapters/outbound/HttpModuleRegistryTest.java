package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.providers.ports.outbound.ModuleRegistryPort.Location;
import com.iaf.platform.testing.LocalHttp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The module registry protocol against a registry on a loopback port. */
class HttpModuleRegistryTest {

    record Seen(String path, String authorization) {
    }

    HttpServer server;
    CredentialsPort credentials;
    HttpModuleRegistry registry;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final Map<String, Answer> answers = new ConcurrentHashMap<>();

    record Answer(int status, String body, String location) {
    }

    @BeforeEach
    void start() throws IOException {
        server = LocalHttp.start(this::handle);
        credentials = mock(CredentialsPort.class);
        when(credentials.resolve("vault:terraform/registry")).thenReturn(Mono.just("s3cret-token"));
        when(credentials.resolve("vault:nothing/here")).thenReturn(Mono.empty());
        registry = new HttpModuleRegistry(LocalHttp.url(server), credentials, new ObjectMapper());
        answers.put("/.well-known/terraform.json", new Answer(200, "{\"modules.v1\": \"/api/registry/v1/modules/\"}", null));
        answers.put("/api/registry/v1/modules/acme/lb/aws/versions", new Answer(200, """
                {"modules": [{"versions": [{"version": "1.0.0"}, {"version": "2.0.0-rc1"}, {"version": "1.10.0"}, {"version": "2.0.0"}]}]}
                """, null));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void handle(HttpExchange exchange) throws IOException {
        seen.add(new Seen(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization")));
        Answer a = answers.get(exchange.getRequestURI().getPath());
        if (a == null) {
            LocalHttp.respond(exchange, 404, "{\"errors\": [\"not found\"]}");
            return;
        }
        if (a.location() != null) {
            exchange.getResponseHeaders().add("X-Terraform-Get", a.location());
        }
        LocalHttp.respond(exchange, a.status(), a.body());
    }

    @Test
    void theModulesApiIsDiscoveredAndVersionsComeNewestFirst() {
        StepVerifier.create(registry.versions("acme/lb/aws", null))
                .assertNext(v -> assertThat(v).containsExactly("2.0.0", "2.0.0-rc1", "1.10.0", "1.0.0"))
                .verifyComplete();
        assertThat(seen).extracting(Seen::path).containsExactly("/.well-known/terraform.json", "/api/registry/v1/modules/acme/lb/aws/versions");
        assertThat(registry.host()).isEqualTo("127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void withoutServiceDiscoveryTheDefaultModulesApiIsUsed() {
        answers.remove("/.well-known/terraform.json");
        answers.put("/v1/modules/acme/lb/aws/versions", new Answer(200, "{\"modules\": [{\"versions\": [{\"version\": \"0.1.0\"}]}]}", null));
        StepVerifier.create(registry.versions("acme/lb/aws", null))
                .assertNext(v -> assertThat(v).containsExactly("0.1.0"))
                .verifyComplete();
    }

    @Test
    void aVersionIsFoundThroughItsDownloadEndpoint() {
        answers.put("/api/registry/v1/modules/acme/lb/aws/2.0.0/download", new Answer(204, null, "git::https://github.com/acme/terraform-aws-lb?ref=v2.0.0"));
        answers.put("/api/registry/v1/modules/acme/lb/aws/1.0.0/download", new Answer(204, null, "./archives/lb-1.0.0.tar.gz"));
        StepVerifier.create(registry.locate("acme/lb/aws", "2.0.0", null))
                .assertNext(l -> assertThat(l).isEqualTo(new Location("acme/lb/aws", "2.0.0", "git::https://github.com/acme/terraform-aws-lb?ref=v2.0.0")))
                .verifyComplete();
        StepVerifier.create(registry.locate("acme/lb/aws", "1.0.0", null))
                .assertNext(l -> assertThat(l.download()).isEqualTo(LocalHttp.url(server) + "/api/registry/v1/modules/acme/lb/aws/1.0.0/archives/lb-1.0.0.tar.gz"))
                .verifyComplete();
    }

    @Test
    void anUnknownVersionNamesTheVersionsThereAre() {
        StepVerifier.create(registry.locate("acme/lb/aws", "9.9.9", null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("No version 9.9.9 of acme/lb/aws in " + registry.host() + "; its versions are 2.0.0, 2.0.0-rc1, 1.10.0, 1.0.0"))
                .verify();
        StepVerifier.create(registry.versions("acme/nope/aws", null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessage("No module acme/nope/aws in " + registry.host()))
                .verify();
    }

    @Test
    void theTokenGoesToTheRegistryAsABearerAndIntoNoMessage() {
        answers.put("/api/registry/v1/modules/acme/lb/aws/2.0.0/download", new Answer(401, "{}", null));
        StepVerifier.create(registry.versions("acme/lb/aws", "vault:terraform/registry"))
                .expectNextCount(1)
                .verifyComplete();
        assertThat(seen).extracting(Seen::authorization).containsOnly("Bearer s3cret-token");
        StepVerifier.create(registry.locate("acme/lb/aws", "2.0.0", "vault:terraform/registry"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("the registry token was not accepted").hasMessageNotContaining("s3cret"))
                .verify();
        StepVerifier.create(registry.locate("acme/lb/aws", "2.0.0", null))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("the module is private; give registry.credential"))
                .verify();
        StepVerifier.create(registry.versions("acme/lb/aws", "vault:nothing/here"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessage("No secret at vault:nothing/here"))
                .verify();
    }

    @Test
    void detailsAreTheRegistrysDescriptionOrNothing() {
        answers.put("/api/registry/v1/modules/acme/lb/aws/2.0.0", new Answer(200, """
                {"id": "acme/lb/aws/2.0.0", "root": {"inputs": [{"name": "name", "type": "string", "required": true}], "outputs": []}}
                """, null));
        StepVerifier.create(registry.details("acme/lb/aws", "2.0.0", null))
                .assertNext(d -> assertThat(d.path("root").path("inputs").get(0).path("name").asText()).isEqualTo("name"))
                .verifyComplete();
        StepVerifier.create(registry.details("acme/lb/aws", "1.0.0", null))
                .verifyComplete();
    }

    @Test
    void anAnswerLargerThanTheLimitIsABadGatewayNotReadWhole() {
        answers.put("/api/registry/v1/modules/acme/lb/aws/2.0.0", new Answer(200, "{\"root\": \"" + "x".repeat(HttpModuleRegistry.MAX_BODY_BYTES) + "\"}", null));
        StepVerifier.create(registry.details("acme/lb/aws", "2.0.0", null))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("answered more than 8 MB to describe acme/lb/aws 2.0.0"))
                .verify();
    }

    @Test
    void aRegistryThatIsNotOneSaysSo() {
        answers.put("/.well-known/terraform.json", new Answer(200, "{\"providers.v1\": \"/v1/providers/\"}", null));
        StepVerifier.create(registry.versions("acme/lb/aws", null))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("is not a Terraform module registry"))
                .verify();
    }
}
