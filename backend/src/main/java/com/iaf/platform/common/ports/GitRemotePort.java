package com.iaf.platform.common.ports;

import java.nio.file.Path;
import java.util.regex.Pattern;

import reactor.core.publisher.Mono;

/**
 * The git remotes playbooks come from. Remotes are https only (a bare {@code host/path} means
 * https); a private remote takes a {@code vault:<path>} credential that is resolved at call time
 * and never echoed. Errors: {@link IllegalArgumentException} for a refused URL or reference,
 * {@code NotFoundException} for an unknown repository, ref or commit, and
 * {@link RemoteUnavailable} when the remote cannot be read.
 */
public interface GitRemotePort {

    /** A full commit id: 40 lowercase hex digits. */
    Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");

    /** What {@code ref} names on {@code repo} right now; {@code commit} is a full commit id. */
    record Resolved(String repo, String ref, String commit) {
    }

    /**
     * Resolve a tag (an annotated tag is peeled to its commit), a branch, {@code HEAD} or a full
     * ref name like {@code refs/heads/main}; a tag wins over a branch of the same name, as in git.
     * A full commit id is answered as it is.
     */
    Mono<Resolved> resolve(String repo, String ref, String credential);

    /**
     * The files of {@code repo} at {@code commit}, without {@code .git}, in a cache directory
     * shared by every caller (read it, never write to it). Fetched once per repo and commit.
     */
    Mono<Path> checkout(String repo, String commit, String credential);

    /** The remote could not be reached or refused the request; the message never holds a secret. */
    class RemoteUnavailable extends RuntimeException {
        public RemoteUnavailable(String message) {
            super(message);
        }
    }
}
