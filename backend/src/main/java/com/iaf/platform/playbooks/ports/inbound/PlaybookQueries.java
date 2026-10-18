package com.iaf.platform.playbooks.ports.inbound;

import com.iaf.platform.common.ports.GitRemotePort.Resolved;

import reactor.core.publisher.Mono;

/** Read side of playbooks: what commit a git ref names now, so a workflow can pin it. */
public interface PlaybookQueries {

    /**
     * {@code repo} is an https URL (or {@code host/path}), {@code ref} a tag, branch, HEAD or full
     * commit id, {@code credential} an optional {@code vault:<path>} for a private remote.
     */
    Mono<Resolved> resolve(String repo, String ref, String credential);
}
