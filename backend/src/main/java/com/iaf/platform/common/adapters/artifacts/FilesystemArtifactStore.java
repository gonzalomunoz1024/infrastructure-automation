package com.iaf.platform.common.adapters.artifacts;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.config.ArtifactsProperties;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The default {@link ArtifactStorePort}: files under {@code platform.artifacts.dir}, at
 * {@code sha256/<first two digits>/<digest>}, and a named object at its name
 * ({@code jobs/<id>/logs.ndjson}). A put writes a staging file, then links it into place, which
 * fails rather than overwrites when the digest or the name is already there; stored files are
 * read-only.
 */
@Slf4j
@Component
public class FilesystemArtifactStore implements ArtifactStorePort {

    private final Path root;

    @Autowired
    public FilesystemArtifactStore(ArtifactsProperties props) {
        this(Path.of(props.getDir()));
    }

    FilesystemArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public Mono<Artifact> put(Path file) {
        return blocking(() -> {
            try (InputStream in = Files.newInputStream(file)) {
                return store(in);
            }
        });
    }

    @Override
    public Mono<Artifact> put(byte[] content) {
        return blocking(() -> store(new ByteArrayInputStream(content == null ? new byte[0] : content)));
    }

    @Override
    public Mono<Path> get(String sha256, Path target) {
        return blocking(() -> {
            String digest = ArtifactStorePort.digest(sha256);
            Path source = pathOf(digest);
            if (!Files.isRegularFile(source)) {
                return Optional.<Path>empty();
            }
            try (InputStream in = Files.newInputStream(source)) {
                return Optional.of(ArtifactFiles.copyVerified(in, digest, target));
            }
        }).flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<Boolean> exists(String sha256) {
        return blocking(() -> Files.isRegularFile(pathOf(ArtifactStorePort.digest(sha256))));
    }

    @Override
    public Mono<Boolean> putNamed(String name, byte[] content) {
        return blocking(() -> {
            Path target = root.resolve(ArtifactStorePort.name(name));
            if (Files.exists(target)) {
                return false;
            }
            Path staging = root.resolve("staging");
            Files.createDirectories(staging);
            Path part = Files.createTempFile(staging, "put-", ".part");
            try {
                Files.write(part, content == null ? new byte[0] : content);
                Files.createDirectories(target.getParent());
                readOnly(part);
                try {
                    Files.createLink(target, part);
                } catch (FileAlreadyExistsException e) {
                    return false;
                } catch (UnsupportedOperationException | FileSystemException e) {
                    try {
                        Files.move(part, target);
                    } catch (FileAlreadyExistsException taken) {
                        return false;
                    }
                }
                log.debug("stored {} ({} bytes)", name, content == null ? 0 : content.length);
                return true;
            } finally {
                Files.deleteIfExists(part);
            }
        });
    }

    @Override
    public Mono<byte[]> getNamed(String name) {
        return blocking(() -> {
            Path file = root.resolve(ArtifactStorePort.name(name));
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.<byte[]>empty();
        }).flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<Boolean> existsNamed(String name) {
        return blocking(() -> Files.isRegularFile(root.resolve(ArtifactStorePort.name(name))));
    }

    Path pathOf(String digest) {
        return root.resolve("sha256").resolve(digest.substring(0, 2)).resolve(digest);
    }

    private Artifact store(InputStream in) throws IOException {
        Path staging = root.resolve("staging");
        Files.createDirectories(staging);
        Path part = Files.createTempFile(staging, "put-", ".part");
        try {
            Artifact a;
            try (OutputStream out = Files.newOutputStream(part)) {
                a = ArtifactFiles.copy(in, out);
            }
            Path target = pathOf(a.sha256());
            if (Files.exists(target)) {
                return a;
            }
            Files.createDirectories(target.getParent());
            readOnly(part);
            try {
                Files.createLink(target, part);
            } catch (FileAlreadyExistsException e) {
                // a put of the same content got there first: the stored copy stays
            } catch (UnsupportedOperationException | FileSystemException e) {
                moveOnce(part, target);
            }
            log.debug("stored artifact {} ({} bytes)", a.sha256(), a.size());
            return a;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** Where hard links are not available: a move that never replaces an existing digest. */
    private static void moveOnce(Path part, Path target) throws IOException {
        try {
            Files.move(part, target);
        } catch (FileAlreadyExistsException e) {
            // stored concurrently; keep the first copy
        }
    }

    private static void readOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        } catch (UnsupportedOperationException | IOException e) {
            if (!file.toFile().setReadOnly()) {
                log.debug("could not make {} read-only", file);
            }
        }
    }

    private static <T> Mono<T> blocking(Callable<T> call) {
        return Mono.fromCallable(() -> {
            try {
                return call.call();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
