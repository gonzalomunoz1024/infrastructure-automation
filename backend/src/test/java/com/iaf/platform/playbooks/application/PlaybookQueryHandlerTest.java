package com.iaf.platform.playbooks.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.common.ports.GitRemotePort;
import com.iaf.platform.common.ports.GitRemotePort.Resolved;
import com.iaf.platform.identity.application.CurrentIdentity;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PlaybookQueryHandlerTest {

    static final String COMMIT = "4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d";
    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    final GitRemotePort git = mock(GitRemotePort.class);
    final CredentialsPort credentials = mock(CredentialsPort.class);
    final PlaybookQueryHandler handler = new PlaybookQueryHandler(git, credentials);

    @BeforeEach
    void credentials() {
        List<CredentialInfo> known = List.of(
                new CredentialInfo("git/acme", "token", "git/acme", "team-compute", NOW),
                new CredentialInfo("git/data", "token", "git/data", "team-data", NOW),
                new CredentialInfo("git/shared", "token", "git/shared", null, NOW));
        when(credentials.info(anyString())).thenAnswer(inv -> Mono.justOrEmpty(
                known.stream().filter(c -> c.name().equals(CredentialsPort.nameOf(inv.getArgument(0)))).findFirst()));
    }

    Mono<Resolved> resolve(Identity who, String credential) {
        return handler.resolve("github.com/acme/ops-playbooks", "v3.1.0", credential).contextWrite(ctx -> CurrentIdentity.with(ctx, who));
    }

    @Test
    void anAuthorGetsTheCommitTheRefNamesWithTheCredentialPassedOn() {
        Resolved r = new Resolved("github.com/acme/ops-playbooks", "v3.1.0", COMMIT);
        when(git.resolve("github.com/acme/ops-playbooks", "v3.1.0", "vault:git/acme")).thenReturn(Mono.just(r));

        StepVerifier.create(resolve(SampleCatalog.OPS, "vault:git/acme")).expectNext(r).verifyComplete();
    }

    @Test
    void someoneWithoutATeamCannotAskTheRemote() {
        StepVerifier.create(resolve(SampleCatalog.GUEST, null)).expectError(ForbiddenException.class).verify();
        verify(git, never()).resolve(any(), any(), any());
    }

    @Test
    void anotherTeamsCredentialIsRefusedBeforeTheRemoteIsAskedNamingItAndItsTeamOnly() {
        StepVerifier.create(resolve(SampleCatalog.OPS, "vault:git/data"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class)
                        .hasMessage("Mateo may not use credential vault:git/data (owned by team-data)"))
                .verify();
        verify(git, never()).resolve(any(), any(), any());
        verify(credentials, never()).resolve(any());
    }

    @Test
    void anAdminAnUnownedCredentialAndAnUnknownOneGoThrough() {
        Resolved r = new Resolved("github.com/acme/ops-playbooks", "v3.1.0", COMMIT);
        when(git.resolve(any(), any(), any())).thenReturn(Mono.just(r));

        StepVerifier.create(resolve(SampleCatalog.ADMIN, "vault:git/data")).expectNext(r).verifyComplete();
        StepVerifier.create(resolve(SampleCatalog.OPS, "vault:git/shared")).expectNext(r).verifyComplete();
        StepVerifier.create(resolve(SampleCatalog.OPS, "vault:git/nope")).expectNext(r).verifyComplete();
        verify(git).resolve("github.com/acme/ops-playbooks", "v3.1.0", "vault:git/nope");
    }
}
