package com.iaf.platform.audit.adapters.outbound;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The narrow write-once object store under {@link WormEvidenceStore}: objects are written once,
 * never replaced and kept until their retain-until date. Keys are {@code /}-separated paths made of
 * safe characters only (the store encodes record ids). Implementations: a folder of read-only files
 * ({@link FileSystemWormObjects}) and an S3 bucket with Object Lock ({@link S3ObjectLockObjects}).
 */
public interface WormObjects {

    /**
     * Writes {@code bytes} at {@code key} unless an object is already there, atomically: of two
     * writers of the same key exactly one wins. Returns false, and changes nothing, when the key is
     * taken. The object cannot be changed or removed before {@code retainUntil}.
     */
    boolean putIfAbsent(String key, byte[] bytes, Instant retainUntil);

    Optional<byte[]> get(String key);

    /** The keys under {@code prefix} (objects only, at any depth), sorted. */
    List<String> list(String prefix);

    /** The names of the folders directly under {@code prefix} (a common prefix ending in {@code /}), sorted. */
    List<String> folders(String prefix);

    /** Where the objects live, for logs: a folder or a bucket, never a credential. */
    String describe();
}
