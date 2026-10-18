package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.providers.ports.outbound.GitSourcePort.GitFile;
import com.iaf.platform.providers.ports.outbound.GitSourcePort.Snapshot;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The Git reader against a repository on disk: refs resolved to their commit, files read from that commit's tree. */
class JGitSourceTest {

    static final String SPEC_V1 = "openapi: 3.1.0\ninfo: { title: Fleet, version: 1.0.0 }\npaths: {}\n";
    static final String SPEC_V2 = "openapi: 3.1.0\ninfo: { title: Fleet, version: 2.0.0 }\npaths: {}\n";
    static final PersonIdent SEED = new PersonIdent("seed", "seed@example.com");

    @TempDir
    Path dir;
    CredentialsPort credentials;
    JGitSource git;
    String repo;
    String first;
    String second;

    @BeforeEach
    void seedRepository() throws Exception {
        credentials = mock(CredentialsPort.class);
        git = new JGitSource(credentials, true, Duration.ofSeconds(30));
        Path root = dir.resolve("fleet-api");
        repo = root.toString();
        try (Git g = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()) {
            write(root, "specs/openapi.yaml", SPEC_V1);
            write(root, "README.md", "# Fleet API\n");
            g.add().addFilepattern(".").call();
            first = g.commit().setMessage("v1").setAuthor(SEED).setCommitter(SEED).setSign(false).call().name();
            g.tag().setName("v1.0.0").setAnnotated(true).setMessage("v1.0.0").setTagger(SEED).setSigned(false).call();

            write(root, "specs/openapi.yaml", SPEC_V2);
            Files.write(root.resolve("specs/logo.png"), new byte[] { (byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13 });
            Files.createSymbolicLink(root.resolve("specs/link.yaml"), Path.of("../README.md"));
            g.add().addFilepattern(".").call();
            second = g.commit().setMessage("v2").setAuthor(SEED).setCommitter(SEED).setSign(false).call().name();
        }
    }

    static void write(Path root, String path, String text) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void aBranchIsReadAtItsNewestCommit() {
        StepVerifier.create(git.read(repo, "main", "specs", p -> p.endsWith("openapi.yaml"), null))
                .assertNext(s -> {
                    assertThat(s.ref()).isEqualTo("main");
                    assertThat(s.commit()).isEqualTo(second);
                    assertThat(s.files()).containsExactlyEntriesOf(java.util.Map.of("specs/openapi.yaml", SPEC_V2));
                })
                .verifyComplete();
    }

    @Test
    void anAnnotatedTagReadsTheCommitItWasCutFrom() {
        StepVerifier.create(git.read(repo, "v1.0.0", "", p -> true, null))
                .assertNext(s -> {
                    assertThat(s.commit()).isEqualTo(first);
                    assertThat(s.files()).containsEntry("specs/openapi.yaml", SPEC_V1).containsKey("README.md").hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void aCommitHashReadsThatCommitEvenAbbreviated() {
        StepVerifier.create(git.read(repo, first, "specs", p -> true, null))
                .assertNext(s -> assertThat(s.files()).containsEntry("specs/openapi.yaml", SPEC_V1))
                .verifyComplete();
        StepVerifier.create(git.read(repo, first.substring(0, 10), "specs", p -> true, null))
                .assertNext(s -> assertThat(s.commit()).isEqualTo(first))
                .verifyComplete();
    }

    @Test
    void withoutARefTheMainBranchIsRead() {
        StepVerifier.create(git.read(repo, null, null, p -> p.endsWith("openapi.yaml"), null))
                .assertNext(s -> {
                    assertThat(s.ref()).isEqualTo("main");
                    assertThat(s.commit()).isEqualTo(second);
                })
                .verifyComplete();
    }

    @Test
    void binariesAndSymlinksAreSkippedNotRead() {
        Snapshot s = git.read(repo, "main", "specs", p -> true, null).block();
        assertThat(s.files()).containsOnlyKeys("specs/openapi.yaml");
        assertThat(s.skipped()).containsExactlyInAnyOrder("specs/logo.png (binary)", "specs/link.yaml (not a regular file)");
    }

    @Test
    void oneFileComesWithTheCommitItWasReadFrom() {
        StepVerifier.create(git.file(repo, "v1.0.0", "/specs/openapi.yaml", null))
                .assertNext(f -> assertThat(f).isEqualTo(new GitFile("specs/openapi.yaml", SPEC_V1, first)))
                .verifyComplete();
    }

    @Test
    void aMissingFileRefOrCommitIsABadRequest() {
        StepVerifier.create(git.file(repo, "main", "specs/nope.yaml", null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("specs/nope.yaml is not in"))
                .verify();
        StepVerifier.create(git.read(repo, "release-9", "", p -> true, null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No branch, tag or commit 'release-9'"))
                .verify();
        StepVerifier.create(git.read(repo, "0123456789abcdef0123456789abcdef01234567", "", p -> true, null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No branch, tag or commit"))
                .verify();
        StepVerifier.create(git.read(dir.resolve("nowhere").toString(), "main", "", p -> true, null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Could not read"))
                .verify();
        StepVerifier.create(git.read(repo, "main", "../etc", p -> true, null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inside the repository"))
                .verify();
    }

    @Test
    void thePlatformsOwnDiskIsOffLimitsUnlessLocalReadsAreAllowed() {
        JGitSource production = new JGitSource(credentials, false, Duration.ofSeconds(30));
        StepVerifier.create(production.read(repo, "main", "", p -> true, null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("own disk"))
                .verify();
        assertThatThrownBy(() -> production.uriOf("file:///etc")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("own disk");
    }

    @Test
    void onlyHttpsUrlsWithoutCredentialsAreFetched() {
        assertThat(git.uriOf("github.com/acme/fleet-api")).isEqualTo("https://github.com/acme/fleet-api");
        assertThat(git.uriOf(" https://github.com/acme/fleet-api.git ")).isEqualTo("https://github.com/acme/fleet-api.git");
        assertThatThrownBy(() -> git.uriOf("http://github.com/acme/fleet-api")).hasMessageContaining("https URL");
        assertThatThrownBy(() -> git.uriOf("git@github.com:acme/fleet-api.git")).hasMessageContaining("ssh is not supported");
        assertThatThrownBy(() -> git.uriOf("ssh://git@github.com/acme/fleet-api")).hasMessageContaining("https URL");
        assertThatThrownBy(() -> git.uriOf("https://x-access-token:s3cr3t@github.com/acme/fleet-api"))
                .hasMessageContaining("Credentials never go in the repository URL")
                .hasMessageNotContaining("s3cr3t");
        assertThatThrownBy(() -> git.uriOf("https://169.254.169.254/latest")).hasMessageContaining("link-local");
        assertThatThrownBy(() -> git.uriOf("https://metadata.google.internal/x")).hasMessageContaining("metadata");
        assertThatThrownBy(() -> git.uriOf(" ")).hasMessageContaining("repo is required");
        assertThat(JGitSource.display("https://bot:s3cr3t@github.com/acme/x")).isEqualTo("https://github.com/acme/x");
    }

    @Test
    void theTokenComesFromVaultAndAMissingOneIsABadRequest() {
        when(credentials.resolve("vault:git/missing")).thenReturn(Mono.empty());
        when(credentials.resolve("vault:git/fleet")).thenReturn(Mono.just("t0k3n"));
        StepVerifier.create(git.read(repo, "main", "", p -> true, "vault:git/missing"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessage("No secret at vault:git/missing"))
                .verify();
        StepVerifier.create(git.file(repo, "main", "README.md", "vault:git/fleet"))
                .assertNext(f -> assertThat(f.content()).isEqualTo("# Fleet API\n"))
                .verifyComplete();
    }
}
