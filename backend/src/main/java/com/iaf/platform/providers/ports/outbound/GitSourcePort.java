package com.iaf.platform.providers.ports.outbound;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import reactor.core.publisher.Mono;

/**
 * Reads files of a Git repository at a ref (a branch, a tag or a commit), pinned to the commit the
 * ref pointed at: the spec of a provider, the playbooks of an Ansible repository, the variables of
 * a Terraform module. A private repository takes a {@code vault:} reference to a token; the token
 * itself is resolved at read time and never stored, returned or logged.
 */
public interface GitSourcePort {

    /** The files read ({@code path -> text}, paths from the repository root) and the commit they came from. */
    record Snapshot(String repo, String ref, String commit, Map<String, String> files, List<String> skipped) {
    }

    /** One file and the commit it came from. */
    record GitFile(String path, String content, String commit) {
    }

    /**
     * The text files under {@code dir} (the whole repository when blank) that {@code wanted} accepts.
     * An unknown repository or ref is an {@link IllegalArgumentException}.
     */
    Mono<Snapshot> read(String repo, String ref, String dir, Predicate<String> wanted, String credential);

    default Mono<GitFile> file(String repo, String ref, String path, String credential) {
        String clean = path == null ? "" : path.trim().replaceAll("^/+", "");
        if (clean.isEmpty()) {
            return Mono.error(new IllegalArgumentException("path is required: the file in the repository"));
        }
        int slash = clean.lastIndexOf('/');
        return read(repo, ref, slash > 0 ? clean.substring(0, slash) : "", clean::equals, credential).map(s -> {
            String text = s.files().get(clean);
            if (text == null) {
                throw new IllegalArgumentException(clean + " is not in " + s.repo() + " at " + s.ref());
            }
            return new GitFile(clean, text, s.commit());
        });
    }
}
