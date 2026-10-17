package com.iaf.platform.providers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.providers.ports.inbound.CredentialsCommands.StoreCredential;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CredentialsHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Identity MATEO = new Identity("mateo", "Mateo", null, List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);

    CredentialsPort port;
    List<DomainEvent> published;
    CredentialsHandler handler;

    @BeforeEach
    void setUp() {
        port = mock(CredentialsPort.class);
        EventBus events = new EventBus(Clock.fixed(NOW, ZoneOffset.UTC));
        published = new ArrayList<>();
        events.register(published::add);
        handler = new CredentialsHandler(port, events);
    }

    @Test
    void storesThroughThePortAndPublishesWithoutTheValue() {
        when(port.store("fleet/token", "token", "s3cret", "mateo")).thenReturn(Mono.just(new CredentialInfo("fleet/token", "token", "vault:fleet/token", NOW)));
        StepVerifier.create(handler.storeCredential(new StoreCredential("vault:fleet/token", "token", "s3cret")).contextWrite(ctx -> CurrentIdentity.with(ctx, MATEO)))
                .assertNext(a -> {
                    assertThat(a.id()).isEqualTo("vault:fleet/token");
                    assertThat(a.status()).isEqualTo("stored");
                })
                .verifyComplete();
        assertThat(published).hasSize(1);
        DomainEvent e = published.get(0);
        assertThat(e.type()).isEqualTo("credential.stored");
        assertThat(e.data().path("name").asText()).isEqualTo("fleet/token");
        assertThat(e.data().path("type").asText()).isEqualTo("token");
        assertThat(e.data().path("by").asText()).isEqualTo("mateo");
        assertThat(e.data().toString()).doesNotContain("s3cret");
    }

    @Test
    void rejectsBadInputBeforeTouchingThePort() {
        StepVerifier.create(handler.storeCredential(new StoreCredential("", "token", "x"))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.storeCredential(new StoreCredential("bad name!", "token", "x"))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.storeCredential(new StoreCredential("a/b", "magic", "x"))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.storeCredential(new StoreCredential("a/b", "token", ""))).expectError(IllegalArgumentException.class).verify();
        verify(port, never()).store(any(), any(), any(), any());
    }

    @Test
    void listsWhatThePortLists() {
        when(port.list()).thenReturn(Mono.just(List.of(new CredentialInfo("a", "token", "vault:a", NOW))));
        StepVerifier.create(handler.credentials()).assertNext(l -> assertThat(l).hasSize(1)).verifyComplete();
    }
}
