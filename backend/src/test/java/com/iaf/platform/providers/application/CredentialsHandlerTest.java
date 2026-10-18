package com.iaf.platform.providers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class CredentialsHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Identity MATEO = new Identity("mateo", "Mateo", null, List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity DANA = new Identity("dana", "Dana", null, List.of("team-data"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity LEA = new Identity("lea", "Lea", null, List.of("team-data", "team-platform"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity GUEST = new Identity("guest", "Guest", null, List.of(), List.of("user"), false, Identity.Kind.USER);
    static final Identity ROOT = new Identity("root", "Root", null, List.of(), List.of("admin"), true, Identity.Kind.USER);

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
        when(port.info(any())).thenReturn(Mono.empty());
        EventBus events = new EventBus(Clock.fixed(NOW, ZoneOffset.UTC));
        published = new ArrayList<>();
        events.register(published::add);
        providers = mock(ProviderStorePort.class);
        workflows = mock(WorkflowStorePort.class);
        when(providers.all()).thenReturn(List.of(new StoredProvider(ProviderYaml.parse(VSPHERE), VSPHERE, "providers/team-compute/vsphere.yaml", NOW, "mateo")));
        when(workflows.all()).thenReturn(List.of(WorkflowRecord.of(WorkflowYaml.parse(DB_SETUP), WorkflowStatus.PUBLISHED, "workflows/team-data/db-setup.yaml", NOW, "dana")));
        handler = new CredentialsHandler(port, events, providers, workflows);
    }

    static CredentialInfo info(String name, String owner) {
        return new CredentialInfo(name, "token", "vault:" + name, owner, NOW);
    }

    /** The port answers any store with the credential as stored, owner included. */
    void storeAnswers() {
        when(port.store(any(), any(), any(), any(), any())).thenAnswer(inv -> Mono.just(new CredentialInfo(inv.getArgument(0), inv.getArgument(1),
                "vault:" + inv.getArgument(0), inv.getArgument(4), NOW)));
    }

    static <T> Mono<T> as(Mono<T> mono, Identity who) {
        return mono.contextWrite(ctx -> CurrentIdentity.with(ctx, who));
    }

    @Test
    void storesThroughThePortAndPublishesWithoutTheValue() {
        when(port.store("fleet/token", "token", "s3cret", "mateo", "team-compute")).thenReturn(Mono.just(info("fleet/token", "team-compute")));
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("vault:fleet/token", "token", "s3cret")), MATEO))
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
        assertThat(e.data().path("owner").asText()).isEqualTo("team-compute");
        assertThat(e.data().path("by").asText()).isEqualTo("mateo");
        assertThat(e.data().toString()).doesNotContain("s3cret");
    }

    @Test
    void rejectsBadInputBeforeTouchingThePort() {
        StepVerifier.create(handler.storeCredential(new StoreCredential("", "token", "x"))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.storeCredential(new StoreCredential("bad name!", "token", "x"))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.storeCredential(new StoreCredential("a/b", "magic", "x"))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.storeCredential(new StoreCredential("a/b", "token", ""))).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.storeCredential(new StoreCredential("a/b", "token", "x", "Team Compute")))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("owner team"))
                .verify();
        verify(port, never()).store(any(), any(), any(), any(), any());
        verify(port, never()).info(any());
    }

    @Test
    void theOwnerIsTheCallersFirstTeamUnlessTheyNameAnotherOfTheirs() {
        storeAnswers();
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x")), LEA)).expectNextCount(1).verifyComplete();
        verify(port).store("fleet/token", "token", "x", "lea", "team-data");
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x", " team-platform ")), LEA)).expectNextCount(1).verifyComplete();
        verify(port).store("fleet/token", "token", "x", "lea", "team-platform");
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x", "team-compute")), LEA))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class).hasMessageContaining("team-compute").hasMessageNotContaining("x"))
                .verify();
        verify(port, never()).store(any(), any(), any(), any(), eq("team-compute"));
        assertThat(published).extracting(e -> e.data().path("owner").asText()).containsExactly("team-data", "team-platform");
    }

    @Test
    void anAdminNamesAnyTeamAndNeedsOneWhenTheyHaveNone() {
        storeAnswers();
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x", "team-net")), ROOT)).expectNextCount(1).verifyComplete();
        verify(port).store("fleet/token", "token", "x", "root", "team-net");
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x")), ROOT))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("owner is required"))
                .verify();
        // an existing owner stays when the admin names none
        when(port.info("fleet/token")).thenReturn(Mono.just(info("fleet/token", "team-data")));
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "y")), ROOT)).expectNextCount(1).verifyComplete();
        verify(port).store("fleet/token", "token", "y", "root", "team-data");
    }

    @Test
    void aNewValueKeepsTheOwnerAndOnlyThatTeamOrAnAdminMayWriteIt() {
        storeAnswers();
        when(port.info("fleet/token")).thenReturn(Mono.just(info("fleet/token", "team-data")));
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x")), MATEO))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class)
                        .hasMessageContaining("vault:fleet/token is owned by team-data").hasMessageNotContaining("x"))
                .verify();
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x", "team-compute")), MATEO))
                .expectError(ForbiddenException.class).verify();
        verify(port, never()).store(any(), any(), any(), any(), any());
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x")), DANA)).expectNextCount(1).verifyComplete();
        verify(port).store("fleet/token", "token", "x", "dana", "team-data");
        // the owner team may hand it to another of their teams
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x", "team-platform")), LEA)).expectNextCount(1).verifyComplete();
        verify(port).store("fleet/token", "token", "x", "lea", "team-platform");
    }

    @Test
    void onlyTheOwningTeamOrAnAdminChangesACredentialThatIsInUse() {
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("vsphere/token", "token", "x")), DANA))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class)
                        .hasMessageContaining("provider vsphere").hasMessageContaining("team-compute"))
                .verify();
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("ssh/dba", "ssh-key", "x")), MATEO))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class).hasMessageContaining("workflow db-setup"))
                .verify();
        verify(port, never()).store(any(), any(), any(), any(), any());

        storeAnswers();
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("vsphere/token", "token", "x")), MATEO)).expectNextCount(1).verifyComplete();
        verify(port).store("vsphere/token", "token", "x", "mateo", "team-compute");
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("vsphere/token", "token", "x", "team-compute")), ROOT)).expectNextCount(1).verifyComplete();
    }

    @Test
    void anyTeamMemberMayStoreANewCredentialButNotSomeoneWithoutATeam() {
        storeAnswers();
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x")), DANA)).expectNextCount(1).verifyComplete();
        // a longer path that merely starts with an existing one is a different credential
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("vsphere/token-2", "token", "x")), DANA)).expectNextCount(1).verifyComplete();
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x")), GUEST))
                .expectError(ForbiddenException.class).verify();
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x", "team-compute")), GUEST))
                .expectError(ForbiddenException.class).verify();
    }

    @Test
    void aDraftThatMentionsACredentialGivesItsTeamNoClaimOnIt() {
        when(workflows.all()).thenReturn(List.of(WorkflowRecord.of(WorkflowYaml.parse(DB_SETUP.replace("team-data", "team-web")), WorkflowStatus.DRAFT, "workflows/team-web/db-setup.yaml", NOW, "eve")));
        storeAnswers();
        // the draft does not count as a use, so the credential is still unclaimed
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("ssh/dba", "ssh-key", "x")), MATEO)).expectNextCount(1).verifyComplete();
    }

    @Test
    void theStoreReadsRunOffTheThreadTheVaultAnsweredOn() {
        List<String> threads = new ArrayList<>();
        when(providers.all()).thenAnswer(inv -> {
            threads.add("providers " + Thread.currentThread().getName());
            return List.of();
        });
        when(workflows.all()).thenAnswer(inv -> {
            threads.add("workflows " + Thread.currentThread().getName());
            return List.of();
        });
        // a real vault answers on an event-loop thread; here the parallel scheduler stands in for it
        when(port.info("fleet/token")).thenReturn(Mono.just(info("fleet/token", "team-compute")).publishOn(Schedulers.parallel()));
        storeAnswers();
        StepVerifier.create(as(handler.storeCredential(new StoreCredential("fleet/token", "token", "x")), MATEO)).expectNextCount(1).verifyComplete();
        assertThat(threads).hasSize(2).allSatisfy(t -> assertThat(t).contains("boundedElastic").doesNotContain("parallel"));
        assertThat(published).hasSize(1);
    }

    @Test
    void listsWhatThePortLists() {
        when(port.list()).thenReturn(Mono.just(List.of(info("a", "team-data"))));
        StepVerifier.create(handler.credentials()).assertNext(l -> assertThat(l).hasSize(1)).verifyComplete();
    }
}
