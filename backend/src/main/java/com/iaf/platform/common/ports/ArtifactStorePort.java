package com.iaf.platform.common.ports;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import reactor.core.publisher.Mono;

/**
 * Content-addressed, write-once storage for what a run needs or leaves behind: uploaded
 * playbooks, Terraform plans. Content is named by its SHA-256; putting the same bytes twice
 * stores them once, and nothing is ever overwritten or deleted through this port. Reads check
 * the digest again, so a corrupted object never reaches a run.
 *
 * <p>A named object is kept under a name of its own instead, such as a finished job's archive
 * ({@code jobs/<id>/logs.ndjson}): written once as well (the first content under a name stays),
 * beside the content-addressed objects and never among them.</p>
 */
public interface ArtifactStorePort {

    /** What the store holds under a digest. */
    record Artifact(String sha256, long size) {
    }

    /** Store the content of {@code file} (the file itself is left alone). */
    Mono<Artifact> put(Path file);

    /** Store {@code content}. */
    Mono<Artifact> put(byte[] content);

    /** Copy the content into {@code target} and return it; empty when the store has no such digest. */
    Mono<Path> get(String sha256, Path target);

    Mono<Boolean> exists(String sha256);

    /** Store {@code content} under {@code name} unless something is stored there already: true when this call stored it. */
    Mono<Boolean> putNamed(String name, byte[] content);

    /** What is stored under {@code name}; empty when nothing is. */
    Mono<byte[]> getNamed(String name);

    Mono<Boolean> existsNamed(String name);

    /**
     * A name: segments of letters, digits, {@code .}, {@code _} and {@code -} (none starting with a
     * dot) joined by {@code /}, at most 512 characters, outside the store's own {@code sha256/} and
     * {@code staging/}; anything else is an IllegalArgumentException.
     */
    static String name(String name) {
        if (name == null || name.length() > 512 || !NAME.matcher(name).matches()
                || List.of("sha256", "staging").contains(name.split("/", 2)[0])) {
            throw new IllegalArgumentException("'" + name + "' is not an artifact name (segments of letters, digits, '.', '_' and '-' joined by '/')");
        }
        return name;
    }

    /** {@code sha256:9F2C…} or {@code 9f2c…} as 64 lowercase hex digits; anything else is an IllegalArgumentException. */
    static String digest(String sha256) {
        String d = sha256 == null ? "" : sha256.trim();
        if (d.regionMatches(true, 0, "sha256:", 0, 7)) {
            d = d.substring(7);
        }
        d = d.toLowerCase(Locale.ROOT);
        if (!DIGEST.matcher(d).matches()) {
            throw new IllegalArgumentException("'" + sha256 + "' is not a SHA-256 digest (64 hex digits)");
        }
        return d;
    }

    Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    Pattern NAME = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$");
}
