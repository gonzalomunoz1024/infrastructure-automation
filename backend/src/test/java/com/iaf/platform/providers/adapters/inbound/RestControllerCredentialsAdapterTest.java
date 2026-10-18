package com.iaf.platform.providers.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.providers.application.CredentialsHandler;
import com.iaf.platform.providers.ports.inbound.CredentialsCommands.StoreCredential;
import com.iaf.platform.testing.TestWeb;

import reactor.core.publisher.Mono;

class RestControllerCredentialsAdapterTest {

    CredentialsHandler handler = mock(CredentialsHandler.class);
    WebTestClient client = TestWeb.client(new RestControllerCredentialsAdapter(handler));

    @Test
    void listsNamesTypesPathsAndOwnersButNeverValues() {
        when(handler.credentials()).thenReturn(Mono.just(List.of(
                new CredentialInfo("vsphere/token", "token", "vault:vsphere/token", "team-compute", Instant.parse("2026-10-17T10:00:00Z")),
                new CredentialInfo("shared/token", "token", "vault:shared/token", null, Instant.parse("2026-10-17T10:00:00Z")))));
        client.get().uri("/api/v1/credentials").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("vsphere/token")
                .jsonPath("$[0].type").isEqualTo("token")
                .jsonPath("$[0].path").isEqualTo("vault:vsphere/token")
                .jsonPath("$[0].owner").isEqualTo("team-compute")
                .jsonPath("$[0].updatedAt").isEqualTo("2026-10-17T10:00:00Z")
                .jsonPath("$[0].value").doesNotExist()
                .jsonPath("$[1].owner").doesNotExist();
    }

    @Test
    void storesWith202AndPassesTheOwnerOn() {
        when(handler.storeCredential(any())).thenReturn(Mono.just(CommandAccepted.of("vault:fleet/token", "stored")));
        client.post().uri("/api/v1/credentials").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "fleet/token", "type", "token", "value", "s3cret", "owner", "team-compute")).exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.id").isEqualTo("vault:fleet/token").jsonPath("$.status").isEqualTo("stored").jsonPath("$.value").doesNotExist();
        ArgumentCaptor<StoreCredential> sent = ArgumentCaptor.forClass(StoreCredential.class);
        verify(handler).storeCredential(sent.capture());
        assertThat(sent.getValue().owner()).isEqualTo("team-compute");
        assertThat(sent.getValue().value()).isEqualTo("s3cret");
    }

    @Test
    void badInputIs400AndAnotherTeamsCredentialIs403() {
        when(handler.storeCredential(any())).thenReturn(Mono.error(new IllegalArgumentException("value is required")));
        client.post().uri("/api/v1/credentials").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "fleet/token")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("value is required");
        when(handler.storeCredential(any())).thenReturn(Mono.error(new ForbiddenException("vault:fleet/token is owned by team-data; only team-data or an admin can change it")));
        client.post().uri("/api/v1/credentials").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "fleet/token", "value", "x")).exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("vault:fleet/token is owned by team-data; only team-data or an admin can change it");
    }
}
