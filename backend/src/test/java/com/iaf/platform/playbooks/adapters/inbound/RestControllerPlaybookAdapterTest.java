package com.iaf.platform.playbooks.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.BodyInserters;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.ports.GitRemotePort.RemoteUnavailable;
import com.iaf.platform.common.ports.GitRemotePort.Resolved;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.config.PlaybooksProperties;
import com.iaf.platform.playbooks.application.PlaybookCommandHandler;
import com.iaf.platform.playbooks.application.PlaybookQueryHandler;
import com.iaf.platform.playbooks.domain.PlaybookUpload;
import com.iaf.platform.playbooks.ports.inbound.PlaybookCommands.UploadPlaybook;

import reactor.core.publisher.Mono;

class RestControllerPlaybookAdapterTest {

    static final ObjectMapper JSON = JsonMapper.builder().serializationInclusion(JsonInclude.Include.NON_NULL).build();
    static final String SHA = "b".repeat(64);
    static final String COMMIT = "4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d";

    final PlaybookCommandHandler commands = mock(PlaybookCommandHandler.class);
    final PlaybookQueryHandler queries = mock(PlaybookQueryHandler.class);
    final PlaybooksProperties props = new PlaybooksProperties();
    final WebTestClient client = WebTestClient.bindToController(new RestControllerPlaybookAdapter(commands, queries, props))
            .controllerAdvice(new GlobalExceptionHandler())
            .httpMessageCodecs(c -> c.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(JSON)))
            .build();

    static MultipartBodyBuilder multipart(String fileName, byte[] content, String path) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        if (path != null) {
            body.part("path", path);
        }
        return body;
    }

    WebTestClient.ResponseSpec post(MultipartBodyBuilder body) {
        return client.post().uri("/api/v1/playbooks:upload").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build())).exchange();
    }

    @Test
    void anUploadHandsTheBytesAndThePathToTheHandlerAndRemovesTheTempFile() {
        AtomicReference<UploadPlaybook> seen = new AtomicReference<>();
        AtomicReference<String> content = new AtomicReference<>();
        when(commands.upload(any())).thenAnswer(inv -> {
            UploadPlaybook u = inv.getArgument(0);
            seen.set(u);
            content.set(Files.readString(u.content()));
            return Mono.just(new PlaybookUpload("web.zip", SHA, 11, List.of("site.yml")));
        });

        post(multipart("web.zip", "zip content".getBytes(), "site.yml"))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.file").isEqualTo("web.zip")
                .jsonPath("$.sha256").isEqualTo(SHA)
                .jsonPath("$.size").isEqualTo(11)
                .jsonPath("$.entries[0]").isEqualTo("site.yml");

        assertThat(seen.get().fileName()).isEqualTo("web.zip");
        assertThat(seen.get().path()).isEqualTo("site.yml");
        assertThat(content.get()).isEqualTo("zip content");
        assertThat(Files.exists(seen.get().content())).as("the temp file is gone").isFalse();
    }

    @Test
    void aSinglePlaybookAnswersWithoutEntries() {
        when(commands.upload(any())).thenReturn(Mono.just(new PlaybookUpload("restart.yml", SHA, 5, null)));

        post(multipart("restart.yml", "- hosts: all\n".getBytes(), null))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.file").isEqualTo("restart.yml")
                .jsonPath("$.entries").doesNotExist();
    }

    @Test
    void aFileOverTheLimitIsRefusedWith413BeforeTheHandler() {
        props.setMaxUploadSize(DataSize.ofBytes(1024));

        post(multipart("big.zip", new byte[4096], null))
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.error").isEqualTo("The file is larger than 1024 bytes (platform.playbooks.max-upload-size)");
        post(multipart("huge.zip", new byte[200 * 1024], null)).expectStatus().isEqualTo(413);

        verify(commands, never()).upload(any());
    }

    @Test
    void anUploadWithoutAFilePartIsABadRequest() {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("path", "site.yml");

        post(body).expectStatus().isBadRequest().expectBody().jsonPath("$.error").isEqualTo("file is required: a multipart part named file");
    }

    @Test
    void resolveAnswersTheCommitOfTheRef() {
        when(queries.resolve("github.com/acme/ops-playbooks", "v3.1.0", "vault:git/acme"))
                .thenReturn(Mono.just(new Resolved("github.com/acme/ops-playbooks", "v3.1.0", COMMIT)));

        client.get().uri("/api/v1/playbooks:resolve?repo=github.com/acme/ops-playbooks&ref=v3.1.0&credential=vault:git/acme").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.repo").isEqualTo("github.com/acme/ops-playbooks")
                .jsonPath("$.ref").isEqualTo("v3.1.0")
                .jsonPath("$.commit").isEqualTo(COMMIT);
    }

    @Test
    void resolveMapsTheRemoteErrors() {
        when(queries.resolve("github.com/acme/down", "main", null)).thenReturn(Mono.error(new RemoteUnavailable("Could not read https://github.com/acme/down: connect timed out")));
        when(queries.resolve("github.com/acme/ops", "v9", null)).thenReturn(Mono.error(new NotFoundException("Ref", "'v9' of https://github.com/acme/ops")));
        when(queries.resolve("http://github.com/acme/ops", "main", null)).thenReturn(Mono.error(new IllegalArgumentException("https remotes only")));

        client.get().uri("/api/v1/playbooks:resolve?repo=github.com/acme/down&ref=main").exchange()
                .expectStatus().isEqualTo(502).expectBody().jsonPath("$.error").isEqualTo("Could not read https://github.com/acme/down: connect timed out");
        client.get().uri("/api/v1/playbooks:resolve?repo=github.com/acme/ops&ref=v9").exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/playbooks:resolve?repo=http://github.com/acme/ops&ref=main").exchange().expectStatus().isBadRequest();
    }
}
