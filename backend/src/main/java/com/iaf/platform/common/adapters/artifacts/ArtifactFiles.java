package com.iaf.platform.common.adapters.artifacts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.iaf.platform.common.ports.ArtifactStorePort.Artifact;

/** Hashing and verified copies, shared by the artifact store adapters. */
final class ArtifactFiles {

    private ArtifactFiles() {
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The digest and size of a file. */
    static Artifact hash(Path file) throws IOException {
        MessageDigest md = sha256();
        long size;
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
            size = in.transferTo(OutputStream.nullOutputStream());
        }
        return new Artifact(HexFormat.of().formatHex(md.digest()), size);
    }

    /** Copy {@code in} to {@code out}, returning the digest and size of what went through. */
    static Artifact copy(InputStream in, OutputStream out) throws IOException {
        MessageDigest md = sha256();
        long size;
        try (InputStream din = new DigestInputStream(in, md)) {
            size = din.transferTo(out);
        }
        return new Artifact(HexFormat.of().formatHex(md.digest()), size);
    }

    /**
     * Copy {@code in} into {@code target} (replacing it) and check that the bytes hash to
     * {@code expected}; on a mismatch the target is removed and the copy fails.
     */
    static Path copyVerified(InputStream in, String expected, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Artifact got;
        try (OutputStream out = Files.newOutputStream(target)) {
            got = copy(in, out);
        }
        if (!got.sha256().equals(expected)) {
            Files.deleteIfExists(target);
            throw new IllegalStateException("artifact " + expected + " is corrupt: its content hashes to " + got.sha256());
        }
        return target;
    }
}
