package com.iaf.platform.providers.adapters.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.providers.application.CredentialsHandler;
import com.iaf.platform.testing.TestWeb;

import reactor.core.publisher.Mono;

class RestControllerCredentialsAdapterTest {

    CredentialsHandler handler = mock(CredentialsHandler.class);
    WebTestClient client = TestWeb.client(new RestControllerCredentialsAdapter(handler));

    @Test
    void listsNamesTypesAndPathsButNeverValues() {
        when(handler.credentials()).thenReturn(Mono.just(List.of(new CredentialInfo("vsphere/token", "token", "vault:vsphere/token", Instant.parse("2026-10-17T10:00:00Z")))));
        client.get().uri("/api/v1/credentials").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("vsphere/token")
                .jsonPath("$[0].type").isEqualTo("token")
                .jsonPath("$[0].path").isEqualTo("vault:vsphere/token")
                .jsonPath("$[0].updatedAt").isEqualTo("2026-10-17T10:00:00Z")
                .jsonPath("$[0].value").doesNotExist();
    }

    @Test
    void storesWith202() {
        when(handler.storeCredential(any())).thenReturn(Mono.just(CommandAccepted.of("vault:fleet/token", "stored")));
        client.post().uri("/api/v1/credentials").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "fleet/token", "type", "token", "value", "s3cret")).exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.id").isEqualTo("vault:fleet/token").jsonPath("$.status").isEqualTo("stored").jsonPath("$.value").doesNotExist();
    }

    @Test
    void badInputIs400() {
        when(handler.storeCredential(any())).thenReturn(Mono.error(new IllegalArgumentException("value is required")));
        client.post().uri("/api/v1/credentials").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "fleet/token")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("value is required");
    }
}
