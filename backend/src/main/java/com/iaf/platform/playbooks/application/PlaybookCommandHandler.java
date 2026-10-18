package com.iaf.platform.playbooks.application;

import java.io.IOException;
import java.io.UncheckedIOException;

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
import com.iaf.platform.playbooks.domain.UploadRules;
import com.iaf.platform.playbooks.ports.inbound.PlaybookCommands;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Checks an uploaded playbook (names, links, entry count and unpacked size of an archive, the
 * entry playbook and its YAML) and keeps it in the artifact store under its SHA-256. Each upload
 * is a {@code playbook.uploaded} event.
 */
@Primary
@Service
@RequiredArgsConstructor
public class PlaybookCommandHandler implements PlaybookCommands {

    public static final String UPLOADED_EVENT = "playbook.uploaded";

    private final ArtifactStorePort artifacts;
    private final PlaybooksProperties props;
    private final EventBus events;

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
                            .map(a -> new PlaybookUpload(name, a.sha256(), a.size(), checked.listing().kind() == Kind.YAML ? null : checked.listing().files()))
                            .doOnNext(u -> events.publish(UPLOADED_EVENT, "playbook", u.sha256(), who.id(), data(u, checked.path()))));
        });
    }

    record Checked(Listing listing, String path) {
    }

    private Checked check(UploadPlaybook command, String name) {
        Limits limits = new Limits(props.getMaxEntries(), props.getMaxUnpackedSize().toBytes());
        Kind kind = Kind.of(name);
        String wanted = command.path() == null || command.path().isBlank() ? null : PlaybookArchive.safeName(command.path().trim(), false);
        Listing listing;
        try {
            listing = PlaybookArchive.inspect(command.content(), name, limits, kind == Kind.YAML ? name : wanted);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (listing.files().isEmpty()) {
            throw new IllegalArgumentException(name + " holds no files");
        }
        String path = UploadRules.path(listing, name, command.path());
        if (kind == Kind.YAML || path != null) {
            UploadRules.requirePlaybook(path == null ? name : path, listing.captured());
        }
        return new Checked(listing, path);
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
        return d;
    }
}
