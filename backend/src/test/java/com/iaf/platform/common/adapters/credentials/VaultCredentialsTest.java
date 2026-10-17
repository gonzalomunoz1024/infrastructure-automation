package com.iaf.platform.common.adapters.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.testing.LocalHttp;
import com.sun.net.httpserver.HttpServer;

import reactor.test.StepVerifier;

class VaultCredentialsTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    HttpServer server;
    AtomicInteger dataHits = new AtomicInteger();
    AtomicReference<String> posted = new AtomicReference<>();
    VaultCredentials vault;

    @BeforeEach
    void start() throws IOException {
        server = LocalHttp.start(ex -> {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();
            String key = ex.getRequestMethod() + " " + path + (query != null && query.contains("list=true") ? "?list" : "");
            if (!"tok".equals(ex.getRequestHeaders().getFirst("X-Vault-Token"))) {
                LocalHttp.respond(ex, 403, "{\"errors\":[\"permission denied\"]}");
                return;
            }
            switch (key) {
                case "GET /v1/secret/data/vsphere/token" -> {
                    dataHits.incrementAndGet();
                    LocalHttp.respond(ex, 200, "{\"data\":{\"data\":{\"value\":\"s3cret\",\"type\":\"token\"},\"metadata\":{\"version\":2}}}");
                }
                case "GET /v1/secret/data/db/single" -> LocalHttp.respond(ex, 200, "{\"data\":{\"data\":{\"password\":\"pw\"}}}");
                case "GET /v1/secret/data/db/many" -> LocalHttp.respond(ex, 200, "{\"data\":{\"data\":{\"a\":\"1\",\"b\":\"2\"}}}");
                case "GET /v1/secret/data/missing" -> LocalHttp.respond(ex, 404, "{\"errors\":[]}");
                case "POST /v1/secret/data/new/one" -> {
                    posted.set(LocalHttp.body(ex));
                    LocalHttp.respond(ex, 200, "{\"data\":{\"version\":1}}");
                }
                case "POST /v1/secret/metadata/new/one" -> LocalHttp.respond(ex, 204, "");
                case "GET /v1/secret/metadata/?list" -> LocalHttp.respond(ex, 200, "{\"data\":{\"keys\":[\"vsphere/\",\"db/\"]}}");
                case "GET /v1/secret/metadata/vsphere/?list" -> LocalHttp.respond(ex, 200, "{\"data\":{\"keys\":[\"token\"]}}");
                case "GET /v1/secret/metadata/db/?list" -> LocalHttp.respond(ex, 200, "{\"data\":{\"keys\":[\"single\"]}}");
                case "GET /v1/secret/metadata/vsphere/token" -> LocalHttp.respond(ex, 200,
                        "{\"data\":{\"custom_metadata\":{\"type\":\"token\",\"by\":\"mateo\"},\"updated_time\":\"2026-10-01T00:00:00Z\"}}");
                case "GET /v1/secret/metadata/db/single" -> LocalHttp.respond(ex, 200, "{\"data\":{\"custom_metadata\":null,\"updated_time\":\"2026-10-02T00:00:00Z\"}}");
                default -> LocalHttp.respond(ex, 404, "{\"errors\":[\"unknown " + key + "\"]}");
            }
        });
        PlatformProperties props = new PlatformProperties();
        props.getCredentials().setVaultUrl(LocalHttp.url(server) + "/");
        props.getCredentials().setVaultMount("secret");
        props.getCredentials().setVaultToken("tok");
        vault = new VaultCredentials(WebClient.builder(), props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void resolvesTheValueFieldAndCachesIt() {
        StepVerifier.create(vault.resolve("vault:vsphere/token")).expectNext("s3cret").verifyComplete();
        StepVerifier.create(vault.resolve("vsphere/token")).expectNext("s3cret").verifyComplete();
        assertThat(dataHits.get()).isEqualTo(1);
        assertThat(vault.cached()).isEqualTo(1);
    }

    @Test
    void resolvesASingleFieldSecretAndNothingElse() {
        StepVerifier.create(vault.resolve("vault:db/single")).expectNext("pw").verifyComplete();
        StepVerifier.create(vault.resolve("vault:db/many")).verifyComplete();
        StepVerifier.create(vault.resolve("vault:missing")).verifyComplete();
        StepVerifier.create(vault.resolve(null)).verifyComplete();
    }

    @Test
    void listsTheMetadataTreeWithoutValues() {
        StepVerifier.create(vault.list())
                .assertNext(list -> {
                    assertThat(list).extracting(CredentialInfo::name).containsExactly("db/single", "vsphere/token");
                    assertThat(list.get(1).type()).isEqualTo("token");
                    assertThat(list.get(1).updatedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
                    assertThat(list.get(1).path()).isEqualTo("vault:vsphere/token");
                    assertThat(list.get(0).type()).isEqualTo("token");
                    assertThat(list.toString()).doesNotContain("s3cret").doesNotContain("pw");
                })
                .verifyComplete();
    }

    @Test
    void storesTheValueAndItsType() {
        StepVerifier.create(vault.store("vault:new/one", "api-key", "k-1", "mateo"))
                .assertNext(info -> {
                    assertThat(info.name()).isEqualTo("new/one");
                    assertThat(info.type()).isEqualTo("api-key");
                    assertThat(info.updatedAt()).isEqualTo(NOW);
                })
                .verifyComplete();
        assertThat(posted.get()).contains("\"value\":\"k-1\"").contains("\"type\":\"api-key\"");
        StepVerifier.create(vault.store("", "token", "x", "mateo")).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void readsTheValueOutOfTheKvShape() {
        assertThat(VaultCredentials.valueOf(null)).isNull();
        assertThat(VaultCredentials.pathOf("vault:/a/b")).isEqualTo("a/b");
    }
}
