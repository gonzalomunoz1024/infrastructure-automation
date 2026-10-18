package com.iaf.platform.playbooks.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.common.ports.ArtifactStorePort.Artifact;
import com.iaf.platform.config.PlaybooksProperties;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.playbooks.domain.PlaybookUpload;
import com.iaf.platform.playbooks.ports.inbound.PlaybookCommands.UploadPlaybook;
import com.iaf.platform.playbooks.ports.outbound.PlaybookLintPort;
import com.iaf.platform.playbooks.ports.outbound.PlaybookLintPort.LintResult;
import com.iaf.platform.testing.Archives;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PlaybookCommandHandlerTest {

    static final String SHA = "a".repeat(64);
    static final String PLAY = "- hosts: all\n  tasks:\n    - ping:\n";

    @TempDir
    Path dir;

    final ArtifactStorePort artifacts = mock(ArtifactStorePort.class);
    final EventBus events = mock(EventBus.class);
    final PlaybooksProperties props = new PlaybooksProperties();
    final PlaybookLintPort lint = mock(PlaybookLintPort.class);
    PlaybookCommandHandler handler;

    @BeforeEach
    void setUp() {
        when(artifacts.put(any(Path.class))).thenAnswer(inv -> Mono.just(new Artifact(SHA, Files.size(inv.<Path>getArgument(0)))));
        when(lint.lint(any(), any())).thenReturn(Optional.empty());
        handler = new PlaybookCommandHandler(artifacts, props, events, lint);
    }

    Mono<PlaybookUpload> upload(Identity who, String fileName, Path content, String path) {
        return handler.upload(new UploadPlaybook(fileName, content, path)).contextWrite(ctx -> CurrentIdentity.with(ctx, who));
    }

    static Map<String, String> bundle() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("site.yml", PLAY);
        m.put("roles/web/tasks/main.yml", "- ping:\n");
        return m;
    }

    @Test
    void aSinglePlaybookIsStoredUnderItsDigestAndAnnounced() throws Exception {
        Path file = Files.writeString(dir.resolve("upload.part"), PLAY);

        StepVerifier.create(upload(SampleCatalog.OPS, "C:\\fakepath\\restart.yml", file, null))
                .expectNext(new PlaybookUpload("restart.yml", SHA, PLAY.length(), null, "skipped"))
                .verifyComplete();

        verify(artifacts).put(file);
        verify(lint).lint(any(), eq("restart.yml"));
        ArgumentCaptor<JsonNode> data = ArgumentCaptor.forClass(JsonNode.class);
        verify(events).publish(eq(PlaybookCommandHandler.UPLOADED_EVENT), eq("playbook"), eq(SHA), eq("mateo"), data.capture());
        assertThat(data.getValue().path("file").asText()).isEqualTo("restart.yml");
        assertThat(data.getValue().has("entries")).isFalse();
        assertThat(data.getValue().path("lint").asText()).isEqualTo("skipped");
    }

    @Test
    void aLiteralSecretInAnyFileOfTheArchiveIsRefusedNamingTheFileAndLineNeverTheValue() throws Exception {
        Map<String, String> files = bundle();
        files.put("roles/db/vars/main.yml", "---\ndb_user: app\ndb_password: hunter2-s3cret\n");
        files.put("files/deploy_key", "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmU=\n-----END OPENSSH PRIVATE KEY-----\n");
        Path zip = Archives.zip(dir.resolve("upload.part"), files);

        StepVerifier.create(upload(SampleCatalog.OPS, "web.zip", zip, "site.yml"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("web.zip holds what looks like a secret: roles/db/vars/main.yml:3 db_password has a literal value; files/deploy_key:1 a private key block")
                        .hasMessageNotContaining("hunter2").hasMessageNotContaining("b3BlbnNzaC"))
                .verify();
        verify(artifacts, never()).put(any(Path.class));
        verify(lint, never()).lint(any(), any());
        verify(events, never()).publish(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aSinglePlaybookIsScannedToo() throws Exception {
        Path file = Files.writeString(dir.resolve("upload.part"), "- hosts: all\n  vars:\n    ansible_password: hunter2\n");

        StepVerifier.create(upload(SampleCatalog.OPS, "restart.yml", file, null))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("restart.yml:3 ansible_password has a literal value").hasMessageNotContaining("hunter2"))
                .verify();
        verify(artifacts, never()).put(any(Path.class));
    }

    @Test
    void theLinterJudgesTheUnpackedUploadAndTheAnswerSaysSo() throws Exception {
        props.setLintCommand("ansible-lint --offline {playbook}");
        Path zip = Archives.zip(dir.resolve("upload.part"), bundle());
        AtomicReference<Path> unpacked = new AtomicReference<>();
        when(lint.lint(any(), eq("site.yml"))).thenAnswer(inv -> {
            Path d = inv.getArgument(0);
            unpacked.set(d);
            assertThat(d.resolve("site.yml")).content().isEqualTo(PLAY);
            assertThat(d.resolve("roles/web/tasks/main.yml")).exists();
            return Optional.of(new LintResult(true, "Passed: 0 failure(s)\n"));
        });

        StepVerifier.create(upload(SampleCatalog.OPS, "web.zip", zip, "site.yml"))
                .assertNext(u -> assertThat(u.lint()).isEqualTo("passed"))
                .verifyComplete();
        assertThat(unpacked.get()).isNotNull();
        assertThat(Files.exists(unpacked.get())).as("the unpacked upload is removed").isFalse();
        ArgumentCaptor<JsonNode> data = ArgumentCaptor.forClass(JsonNode.class);
        verify(events).publish(anyString(), anyString(), anyString(), anyString(), data.capture());
        assertThat(data.getValue().path("lint").asText()).isEqualTo("passed");

        when(lint.lint(any(), eq("site.yml"))).thenReturn(Optional.of(new LintResult(false, "site.yml:1: yaml[truthy]: Truthy value should be one of [false, true]\n")));
        StepVerifier.create(upload(SampleCatalog.OPS, "web.zip", zip, "site.yml"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("web.zip fails the lint (ansible-lint --offline {playbook}):\nsite.yml:1: yaml[truthy]: Truthy value should be one of [false, true]"))
                .verify();
        verify(artifacts).put(any(Path.class));
    }

    @Test
    void anArchiveAnswersItsFilesAndChecksThePlaybookItNames() throws Exception {
        Path zip = Archives.zip(dir.resolve("upload.part"), bundle());

        StepVerifier.create(upload(SampleCatalog.OPS, "web.zip", zip, "site.yml"))
                .assertNext(u -> {
                    assertThat(u.file()).isEqualTo("web.zip");
                    assertThat(u.entries()).containsExactly("site.yml", "roles/web/tasks/main.yml");
                })
                .verifyComplete();

        ArgumentCaptor<JsonNode> data = ArgumentCaptor.forClass(JsonNode.class);
        verify(events).publish(anyString(), anyString(), anyString(), anyString(), data.capture());
        assertThat(data.getValue().path("entries").asInt()).isEqualTo(2);
        assertThat(data.getValue().path("path").asText()).isEqualTo("site.yml");
    }

    @Test
    void aPathThatIsNotInTheArchiveIsABadRequestAndNothingIsStored() throws Exception {
        Path tgz = Archives.tarGz(dir.resolve("upload.part"), bundle());

        StepVerifier.create(upload(SampleCatalog.OPS, "web.tar.gz", tgz, "deploy.yml"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deploy.yml is not in web.tar.gz"))
                .verify();
        verify(artifacts, never()).put(any(Path.class));
    }

    @Test
    void anUnsafeArchiveIsRefusedBeforeItIsStored() throws Exception {
        Path zip = Archives.zip(dir.resolve("upload.part"), Map.of("../../etc/cron.d/x", "* * * * * root sh\n"));

        StepVerifier.create(upload(SampleCatalog.OPS, "web.zip", zip, null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'..'"))
                .verify();
        verify(artifacts, never()).put(any(Path.class));
    }

    @Test
    void theArchiveLimitsComeFromConfiguration() throws Exception {
        props.setMaxUnpackedSize(DataSize.ofBytes(10));
        Path zip = Archives.zip(dir.resolve("upload.part"), bundle());

        StepVerifier.create(upload(SampleCatalog.OPS, "web.zip", zip, null))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("more than 10 bytes"))
                .verify();
    }

    @Test
    void aSinglePlaybookMustBeAListOfPlays() throws Exception {
        Path file = Files.writeString(dir.resolve("upload.part"), "hosts: all\n");

        StepVerifier.create(upload(SampleCatalog.OPS, "restart.yml", file, null))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("restart.yml is not a playbook"))
                .verify();
    }

    @Test
    void someoneWithoutATeamCannotUpload() throws Exception {
        Path file = Files.writeString(dir.resolve("upload.part"), PLAY);

        StepVerifier.create(upload(SampleCatalog.GUEST, "restart.yml", file, null)).expectError(ForbiddenException.class).verify();
        verify(artifacts, never()).put(any(Path.class));
    }
}
