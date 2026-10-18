package com.iaf.platform.audit.adapters.outbound;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.audit.domain.Signatures;

/**
 * Write-once objects as read-only files under one folder. An object is written to a temporary file,
 * made read-only and linked into place with a hard link, which fails when the name exists: an object
 * is never replaced, and of two writers of one key exactly one wins. Next to every object a
 * read-only {@code <name>.retention.json} records the key, the retain-until date, the size and the
 * SHA-256 of the content.
 *
 * <p>A plain filesystem lets the owner of the folder delete read-only files; put the folder on
 * storage that enforces retention (a WORM or SnapLock volume, an immutable-attribute job) where the
 * law requires it.
 */
public class FileSystemWormObjects implements WormObjects {

    static final String RETENTION_SUFFIX = ".retention.json";

    private static final Set<PosixFilePermission> READ_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    private final Path root;

    public FileSystemWormObjects(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the evidence folder " + this.root, e);
        }
    }

    @Override
    public boolean putIfAbsent(String key, byte[] bytes, Instant retainUntil) {
        Path target = resolve(key);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            Files.createDirectories(target.getParent());
            if (!create(target, bytes)) {
                return false;
            }
            ObjectNode retention = WormEvidenceStore.JSON.createObjectNode()
                    .put("key", key)
                    .put("retainUntil", retainUntil.toString())
                    .put("bytes", bytes.length)
                    .put("sha256", Signatures.sha256Hex(bytes))
                    .put("mode", "write-once");
            create(sidecar(target), WormEvidenceStore.JSON.writeValueAsBytes(retention));
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write evidence object " + key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read evidence object " + key, e);
        }
    }

    /** The retain-until date its sidecar records, if the object has one. */
    public Optional<Instant> retainUntil(String key) {
        return get(key + RETENTION_SUFFIX)
                .map(b -> WormEvidenceStore.read(b, com.fasterxml.jackson.databind.JsonNode.class))
                .filter(n -> n.hasNonNull("retainUntil"))
                .map(n -> Instant.parse(n.get("retainUntil").asText()));
    }

    @Override
    public List<String> list(String prefix) {
        int slash = prefix.lastIndexOf('/');
        Path dir = slash < 0 ? root : resolve(prefix.substring(0, slash));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .map(this::keyOf)
                    .filter(k -> k.startsWith(prefix) && !k.endsWith(RETENTION_SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list evidence objects under " + prefix, e);
        }
    }

    @Override
    public List<String> folders(String prefix) {
        Path dir = prefix.isEmpty() ? root : resolve(prefix);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list evidence folders under " + prefix, e);
        }
    }

    @Override
    public String describe() {
        return "folder " + root;
    }

    /** Temp file, read-only, then a hard link to the final name: atomic, complete, never replacing. */
    private static boolean create(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.write(tmp, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            readOnly(tmp);
            try {
                Files.createLink(target, tmp);
                return true;
            } catch (FileAlreadyExistsException e) {
                return false;
            } catch (UnsupportedOperationException e) {
                return createNew(target, bytes);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Where hard links are not supported: an exclusive create, then read-only. */
    private static boolean createNew(Path target, byte[] bytes) throws IOException {
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
        } catch (FileAlreadyExistsException e) {
            return false;
        }
        readOnly(target);
        return true;
    }

    private static void readOnly(Path file) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, READ_ONLY);
        } else if (!file.toFile().setReadOnly()) {
            throw new IOException("cannot make " + file + " read-only");
        }
    }

    private static Path sidecar(Path target) {
        return target.resolveSibling(target.getFileName() + RETENTION_SUFFIX);
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("\\")) {
            throw new IllegalArgumentException("not an evidence object key: " + key);
        }
        for (String segment : key.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new IllegalArgumentException("not an evidence object key: " + key);
            }
        }
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("evidence object key " + key + " leaves the evidence folder");
        }
        return file;
    }

    private String keyOf(Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }
}
