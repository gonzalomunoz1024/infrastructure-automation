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

import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.inbound.CredentialsCommands.StoreCredential;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CredentialsHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Identity MATEO = new Identity("mateo", "Mateo", null, List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity DANA = new Identity("dana", "Dana", null, List.of("team-data"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity GUEST = new Identity("guest", "Guest", null, List.of(), List.of("user"), false, Identity.Kind.USER);

    static final String VSPHERE = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: vsphere, owner: team-compute }
            spec:
              type: http
              endpoints: { prod: https://vcenter.example.com }
              auth: { type: bearer, credential: vault:vsphere/token }
            """;

    static final String DB_SETUP = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: db-setup, owner: team-data, version: 1.0.0 }
            workflow:
              do:
                - connect:
                    call: ansible
                    with: { playbook: setup.yml, credential: vault:ssh/dba }
            """;

    CredentialsPort port;
    ProviderStorePort providers;
    WorkflowStorePort workflows;
    List<DomainEvent> published;
    CredentialsHandler handler;

    @BeforeEach
    void setUp() {
        port = mock(CredentialsPort.class);
        EventBus events = new EventBus(Clock.fixed(NOW, ZoneOffset.UTC));
        published = new ArrayList<>();
        events.register(published::add);
        providers = mock(ProviderStorePort.class);
        workflows = mock(WorkflowStorePort.class);
        when(providers.all()).thenReturn(List.of(new StoredProvider(ProviderYaml.parse(VSPHERE), VSPHERE, "providers/team-compute/vsphere.yaml", NOW, "mateo")));
        when(workflows.all()).thenReturn(List.of(WorkflowRecord.of(WorkflowYaml.parse(DB_SETUP), WorkflowStatus.PUBLISHED, "workflows/team-data/db-setup.yaml", NOW, "dana")));
        handler = new CredentialsHandler(port, events, providers, workflows);
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
    void onlyTheOwningTeamOrAnAdminChangesACredentialThatIsInUse() {
        StepVerifier.create(handler.storeCredential(new StoreCredential("vsphere/token", "token", "x")).contextWrite(ctx -> CurrentIdentity.with(ctx, DANA)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class)
                        .hasMessageContaining("provider vsphere").hasMessageContaining("team-compute"))
                .verify();
        StepVerifier.create(handler.storeCredential(new StoreCredential("ssh/dba", "ssh-key", "x")).contextWrite(ctx -> CurrentIdentity.with(ctx, MATEO)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class).hasMessageContaining("workflow db-setup"))
                .verify();
        verify(port, never()).store(any(), any(), any(), any());

        when(port.store(any(), any(), any(), any())).thenReturn(Mono.just(new CredentialInfo("vsphere/token", "token", "vault:vsphere/token", NOW)));
        StepVerifier.create(handler.storeCredential(new StoreCredential("vsphere/token", "token", "x")).contextWrite(ctx -> CurrentIdentity.with(ctx, MATEO)))
                .expectNextCount(1).verifyComplete();
        Identity admin = new Identity("root", "Root", null, List.of(), List.of("admin"), true, Identity.Kind.USER);
        StepVerifier.create(handler.storeCredential(new StoreCredential("vsphere/token", "token", "x")).contextWrite(ctx -> CurrentIdentity.with(ctx, admin)))
                .expectNextCount(1).verifyComplete();
    }

    @Test
    void anyTeamMemberMayStoreANewCredentialButNotSomeoneWithoutATeam() {
        when(port.store(any(), any(), any(), any())).thenReturn(Mono.just(new CredentialInfo("fleet/token", "token", "vault:fleet/token", NOW)));
        StepVerifier.create(handler.storeCredential(new StoreCredential("fleet/token", "token", "x")).contextWrite(ctx -> CurrentIdentity.with(ctx, DANA)))
                .expectNextCount(1).verifyComplete();
        // a longer path that merely starts with an existing one is a different credential
        StepVerifier.create(handler.storeCredential(new StoreCredential("vsphere/token-2", "token", "x")).contextWrite(ctx -> CurrentIdentity.with(ctx, DANA)))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(handler.storeCredential(new StoreCredential("fleet/token", "token", "x")).contextWrite(ctx -> CurrentIdentity.with(ctx, GUEST)))
                .expectError(ForbiddenException.class).verify();
    }

    @Test
    void aDraftThatMentionsACredentialGivesItsTeamNoClaimOnIt() {
        when(workflows.all()).thenReturn(List.of(WorkflowRecord.of(WorkflowYaml.parse(DB_SETUP.replace("team-data", "team-web")), WorkflowStatus.DRAFT, "workflows/team-web/db-setup.yaml", NOW, "eve")));
        when(port.store(any(), any(), any(), any())).thenReturn(Mono.just(new CredentialInfo("ssh/dba", "ssh-key", "vault:ssh/dba", NOW)));
        // the draft does not count as a use, so the credential is still unclaimed
        StepVerifier.create(handler.storeCredential(new StoreCredential("ssh/dba", "ssh-key", "x")).contextWrite(ctx -> CurrentIdentity.with(ctx, MATEO)))
                .expectNextCount(1).verifyComplete();
    }

    @Test
    void listsWhatThePortLists() {
        when(port.list()).thenReturn(Mono.just(List.of(new CredentialInfo("a", "token", "vault:a", NOW))));
        StepVerifier.create(handler.credentials()).assertNext(l -> assertThat(l).hasSize(1)).verifyComplete();
    }
}
