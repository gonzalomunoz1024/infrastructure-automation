package com.iaf.platform.playbooks.ports.inbound;

import java.nio.file.Path;

import com.iaf.platform.playbooks.domain.PlaybookUpload;

import reactor.core.publisher.Mono;

/**
 * Write side of playbooks: keep an uploaded playbook in the artifact store. Uploading needs a
 * team (or admin); the content is checked before it is stored and stored once per SHA-256.
 */
public interface PlaybookCommands {

    /**
     * {@code fileName} is the name the client sent (.yml, .yaml, .zip or .tar.gz); {@code content}
     * a local file holding the bytes, left for the caller to delete; {@code path} the playbook to
     * run inside an archive, which must exist in it.
     */
    record UploadPlaybook(String fileName, Path content, String path) {
    }

    Mono<PlaybookUpload> upload(UploadPlaybook command);
}
