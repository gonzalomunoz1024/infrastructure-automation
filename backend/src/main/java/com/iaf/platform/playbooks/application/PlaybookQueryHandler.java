package com.iaf.platform.playbooks.application;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.GitRemotePort;
import com.iaf.platform.common.ports.GitRemotePort.Resolved;
import com.iaf.platform.identity.application.CredentialAccess;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.playbooks.domain.UploadRules;
import com.iaf.platform.playbooks.ports.inbound.PlaybookQueries;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Resolves a git ref to the commit it names now (ls-remote), for authors pinning a playbook. A
 * {@code vault:} credential goes to the remote only when the author may use it
 * ({@link CredentialAccess}): 403 naming the credential and its team otherwise.
 */
@Primary
@Service
@RequiredArgsConstructor
public class PlaybookQueryHandler implements PlaybookQueries {

    private final GitRemotePort git;
    private final CredentialsPort credentials;

    @Override
    public Mono<Resolved> resolve(String repo, String ref, String credential) {
        return CurrentIdentity.current().flatMap(who -> {
            UploadRules.requireAuthor(who, "resolve playbook refs");
            return CredentialAccess.guarded(credentials, who, () -> git.resolve(repo, ref, credential), credential);
        });
    }
}
