package com.iaf.platform.playbooks.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.GitRemotePort;
import com.iaf.platform.common.ports.GitRemotePort.Resolved;
import com.iaf.platform.identity.application.CurrentIdentity;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PlaybookQueryHandlerTest {

    static final String COMMIT = "4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d";

    final GitRemotePort git = mock(GitRemotePort.class);
    final PlaybookQueryHandler handler = new PlaybookQueryHandler(git);

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
}
