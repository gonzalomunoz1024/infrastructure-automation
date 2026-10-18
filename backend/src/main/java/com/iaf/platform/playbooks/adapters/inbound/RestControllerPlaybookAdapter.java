package com.iaf.platform.playbooks.adapters.inbound;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.platform.common.ports.GitRemotePort.RemoteUnavailable;
import com.iaf.platform.common.ports.GitRemotePort.Resolved;
import com.iaf.platform.config.PlaybooksProperties;
import com.iaf.platform.playbooks.application.PlaybookCommandHandler;
import com.iaf.platform.playbooks.application.PlaybookQueryHandler;
import com.iaf.platform.playbooks.domain.PlaybookUpload;
import com.iaf.platform.playbooks.ports.inbound.PlaybookCommands.UploadPlaybook;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code /api/v1/playbooks}: {@code POST /playbooks:upload} (multipart: {@code file}, optional
 * {@code path}) keeps a playbook in the artifact store; {@code GET /playbooks:resolve} answers the
 * commit a git ref names. A file over {@code platform.playbooks.max-upload-size} is a 413, a remote
 * that cannot be read a 502.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RestControllerPlaybookAdapter {

    /** Room for the multipart boundaries and part headers around the file itself. */
    static final long MULTIPART_SLACK = 64 * 1024;

    private final PlaybookCommandHandler commands;
    private final PlaybookQueryHandler queries;
    private final PlaybooksProperties props;

    @PostMapping(value = "/playbooks:upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PlaybookUpload> upload(ServerWebExchange exchange) {
        long max = props.getMaxUploadSize().toBytes();
        long declared = exchange.getRequest().getHeaders().getContentLength();
        if (declared > max + MULTIPART_SLACK) {
            return Mono.error(tooLarge(max));
        }
        return exchange.getMultipartData().flatMap(parts -> {
            Part file = parts.getFirst("file");
            if (!(file instanceof FilePart part)) {
                return Mono.error(new IllegalArgumentException("file is required: a multipart part named file"));
            }
            String path = parts.getFirst("path") instanceof FormFieldPart field && !field.value().isBlank() ? field.value() : null;
            return Mono.usingWhen(tempFile(),
                    tmp -> write(part, tmp, max).then(Mono.defer(() -> commands.upload(new UploadPlaybook(part.filename(), tmp, path)))),
                    RestControllerPlaybookAdapter::delete);
        });
    }

    @GetMapping("/playbooks:resolve")
    public Mono<Resolved> resolve(@RequestParam(required = false) String repo, @RequestParam(required = false) String ref,
            @RequestParam(required = false) String credential) {
        return queries.resolve(repo, ref, credential)
                .onErrorMap(RemoteUnavailable.class, e -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage()));
    }

    /** Copy the part to {@code target}, failing with 413 as soon as it passes {@code max} bytes. */
    static Mono<Void> write(FilePart part, Path target, long max) {
        AtomicLong seen = new AtomicLong();
        Flux<DataBuffer> limited = part.content().handle((buffer, sink) -> {
            if (seen.addAndGet(buffer.readableByteCount()) > max) {
                DataBufferUtils.release(buffer);
                sink.error(tooLarge(max));
            } else {
                sink.next(buffer);
            }
        });
        return DataBufferUtils.write(limited, target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static ResponseStatusException tooLarge(long max) {
        String limit = max >= DataSize.ofMegabytes(1).toBytes() && max % DataSize.ofMegabytes(1).toBytes() == 0
                ? DataSize.ofBytes(max).toMegabytes() + " MB" : max + " bytes";
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The file is larger than " + limit + " (platform.playbooks.max-upload-size)");
    }

    private static Mono<Path> tempFile() {
        return Mono.fromCallable(() -> Files.createTempFile("playbook-upload-", ".part")).subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<Void> delete(Path tmp) {
        return Mono.<Void>fromRunnable(() -> {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                // the temp directory is cleaned eventually
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
