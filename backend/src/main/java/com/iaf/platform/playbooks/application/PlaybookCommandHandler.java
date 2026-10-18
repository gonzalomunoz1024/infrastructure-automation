package com.iaf.platform.playbooks.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.archives.PlaybookArchive;
import com.iaf.platform.common.archives.PlaybookArchive.Kind;
import com.iaf.platform.common.archives.PlaybookArchive.Limits;
import com.iaf.platform.common.archives.PlaybookArchive.Listing;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.config.PlaybooksProperties;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.playbooks.domain.PlaybookUpload;
import com.iaf.platform.playbooks.domain.SecretScan;
import com.iaf.platform.playbooks.domain.SecretScan.Finding;
import com.iaf.platform.playbooks.domain.UploadRules;
import com.iaf.platform.playbooks.ports.inbound.PlaybookCommands;
import com.iaf.platform.playbooks.ports.outbound.PlaybookLintPort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Checks an uploaded playbook and keeps it in the artifact store under its SHA-256. The upload is
 * unpacked into a directory of its own (names, links, entry count and unpacked size checked by
 * {@link PlaybookArchive}), the entry playbook must parse as a YAML list of plays, every text
 * file is scanned for secrets ({@link SecretScan}: a finding refuses the upload naming the file
 * and line, never the value), and the configured linter ({@link PlaybookLintPort}) must accept
 * it; the directory is removed afterwards. Each upload is a {@code playbook.uploaded} event.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class PlaybookCommandHandler implements PlaybookCommands {

    public static final String UPLOADED_EVENT = "playbook.uploaded";

    private final ArtifactStorePort artifacts;
    private final PlaybooksProperties props;
    private final EventBus events;
    private final PlaybookLintPort lint;

    @Override
    public Mono<PlaybookUpload> upload(UploadPlaybook command) {
        return CurrentIdentity.current().flatMap(who -> {
            UploadRules.requireAuthor(who, "upload playbooks");
            if (command == null || command.content() == null) {
                throw new IllegalArgumentException("file is required");
            }
            String name = UploadRules.fileName(command.fileName());
            return Mono.fromCallable(() -> check(command, name)).subscribeOn(Schedulers.boundedElastic())
                    .flatMap(checked -> artifacts.put(command.content())
                            .map(a -> new PlaybookUpload(name, a.sha256(), a.size(), checked.listing().kind() == Kind.YAML ? null : checked.listing().files(),
                                    checked.lint()))
                            .doOnNext(u -> events.publish(UPLOADED_EVENT, "playbook", u.sha256(), who.id(), data(u, checked.path()))));
        });
    }

    record Checked(Listing listing, String path, String lint) {
    }

    private Checked check(UploadPlaybook command, String name) {
        Limits limits = new Limits(props.getMaxEntries(), props.getMaxUnpackedSize().toBytes());
        Kind kind = Kind.of(name);
        Path unpacked;
        try {
            unpacked = Files.createTempDirectory("iaf-playbook-upload");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Listing listing;
            try {
                listing = PlaybookArchive.extract(command.content(), name, unpacked, limits);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (listing.files().isEmpty()) {
                throw new IllegalArgumentException(name + " holds no files");
            }
            String path = UploadRules.path(listing, name, command.path());
            String playbook = kind == Kind.YAML ? name : path;
            if (playbook != null) {
                UploadRules.requirePlaybook(playbook, read(unpacked.resolve(playbook), UploadRules.MAX_CHECKED_BYTES));
            }
            List<Finding> findings = scan(unpacked, listing.files());
            if (!findings.isEmpty()) {
                throw new IllegalArgumentException(SecretScan.refusal(name, findings));
            }
            String verdict = lint.lint(unpacked, playbook).map(r -> {
                if (!r.passed()) {
                    throw new IllegalArgumentException(name + " fails the lint (" + props.getLintCommand().trim() + "):\n" + r.output().strip());
                }
                return PlaybookUpload.LINT_PASSED;
            }).orElse(PlaybookUpload.LINT_SKIPPED);
            return new Checked(listing, path, verdict);
        } finally {
            delete(unpacked);
        }
    }

    /** Every text file of the unpacked upload, in archive order. */
    private static List<Finding> scan(Path unpacked, List<String> files) {
        List<Finding> findings = new ArrayList<>();
        for (String file : files) {
            findings.addAll(SecretScan.scan(file, read(unpacked.resolve(file), SecretScan.MAX_SCANNED)));
        }
        return findings;
    }

    /** The file's bytes, or null when it is larger than {@code max} (then it is not judged). */
    private static byte[] read(Path file, long max) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > max) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("could not remove the unpacked upload {}: {}", dir, e.getMessage());
        }
    }

    private static ObjectNode data(PlaybookUpload u, String path) {
        ObjectNode d = JsonNodeFactory.instance.objectNode();
        d.put("file", u.file());
        d.put("sha256", u.sha256());
        d.put("size", u.size());
        if (u.entries() != null) {
            d.put("entries", u.entries().size());
        }
        if (path != null) {
            d.put("path", path);
        }
        d.put("lint", u.lint());
        return d;
    }
}
