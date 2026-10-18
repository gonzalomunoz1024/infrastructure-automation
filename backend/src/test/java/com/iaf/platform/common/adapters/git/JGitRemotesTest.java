package com.iaf.platform.common.adapters.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.GitRemotePort.RemoteUnavailable;
import com.iaf.platform.common.ports.GitRemotePort.Resolved;
import com.iaf.platform.testing.LocalHttp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;

class JGitRemotesTest {

    static final PersonIdent WHO = new PersonIdent("Test", "test@example.com");

    @TempDir
    Path dir;

    final CredentialsPort vault = mock(CredentialsPort.class);
    Path originDir;
    Git origin;
    String url;
    String c1;
    String c2;
    String c3;

    @BeforeEach
    void origin() throws Exception {
        when(vault.resolve(anyString())).thenReturn(Mono.empty());
        originDir = dir.resolve("origin");
        origin = Git.init().setDirectory(originDir.toFile()).setInitialBranch("main").call();
        write("site.yml", "- hosts: all\n");
        write("roles/web/tasks/main.yml", "- ping:\n");
        write("bin/inventory.sh", "#!/bin/sh\necho {}\n");
        Files.setPosixFilePermissions(originDir.resolve("bin/inventory.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
        c1 = commit("first");
        origin.tag().setName("v1").setAnnotated(false).call();
        origin.branchCreate().setName("release").call();
        write("site.yml", "- hosts: web\n");
        c2 = commit("second");
        origin.tag().setName("v2").setMessage("release 2").setAnnotated(true).setSigned(false).setTagger(WHO).call();
        write("site.yml", "- hosts: db\n");
        c3 = commit("third");
        url = "file://" + originDir.toAbsolutePath();
    }

    @AfterEach
    void close() {
        origin.close();
    }

    void write(String name, String content) throws IOException {
        Path p = originDir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    String commit(String message) throws Exception {
        origin.add().addFilepattern(".").call();
        return origin.commit().setMessage(message).setAuthor(WHO).setCommitter(WHO).setSign(false).call().name();
    }

    JGitRemotes remotes() {
        return new JGitRemotes(vault, Duration.ofSeconds(5), dir.resolve("cache"), true, false);
    }

    String resolve(JGitRemotes remotes, String repo, String ref) {
        return remotes.resolve(repo, ref, null).map(Resolved::commit).block(Duration.ofSeconds(20));
    }

    @Test
    void tagsBranchesAndHeadResolveToTheirCommits() throws Exception {
        JGitRemotes remotes = remotes();

        assertThat(resolve(remotes, url, "v1")).isEqualTo(c1);
        assertThat(resolve(remotes, url, "v2")).as("an annotated tag is peeled to its commit").isEqualTo(c2);
        assertThat(resolve(remotes, url, "release")).isEqualTo(c1);
        assertThat(resolve(remotes, url, "main")).isEqualTo(c3);
        assertThat(resolve(remotes, url, "HEAD")).isEqualTo(c3);
        assertThat(resolve(remotes, url, "refs/heads/release")).isEqualTo(c1);

        origin.branchCreate().setName("v1").call();
        assertThat(resolve(remotes, url, "v1")).as("a tag wins over a branch of the same name").isEqualTo(c1);

        Resolved r = remotes.resolve(" " + url + " ", "v2", null).block();
        assertThat(r).isEqualTo(new Resolved(url, "v2", c2));
    }

    @Test
    void aFullCommitIdIsAnsweredWithoutAskingTheRemote() {
        String missing = "file://" + dir.resolve("no-such-repo").toAbsolutePath();
        assertThat(resolve(remotes(), missing, c1.toUpperCase())).isEqualTo(c1);
    }

    @Test
    void unknownRefsAndRepositoriesAreNotFound() {
        assertThatThrownBy(() -> resolve(remotes(), url, "v9")).isInstanceOf(NotFoundException.class).hasMessageContaining("'v9'");
        String missing = "file://" + dir.resolve("no-such-repo").toAbsolutePath();
        assertThatThrownBy(() -> resolve(remotes(), missing, "main")).isInstanceOf(NotFoundException.class).hasMessageContaining("Repository");
    }

    @Test
    void onlyHttpsRemotesWithoutEmbeddedSecretsAreRead() {
        JGitRemotes https = new JGitRemotes(vault, Duration.ofSeconds(5), dir.resolve("cache"), false, false);

        assertThat(https.remote("github.com/acme/ops-playbooks").url()).isEqualTo("https://github.com/acme/ops-playbooks");
        assertThat(https.remote("https://Git.Example.com:8443/acme/ops.git").port()).isEqualTo(8443);
        assertThatThrownBy(() -> https.remote("http://github.com/acme/ops")).hasMessageContaining("https remotes only");
        assertThatThrownBy(() -> https.remote("ssh://git@github.com/acme/ops")).hasMessageContaining("https remotes only");
        assertThatThrownBy(() -> https.remote("git@github.com:acme/ops.git")).hasMessageContaining("ssh address");
        assertThatThrownBy(() -> https.remote("https://deploy:hunter2@github.com/acme/ops")).hasMessageContaining("vault:<path>")
                .hasMessageNotContaining("hunter2");
        assertThatThrownBy(() -> https.remote("https://169.254.169.254/latest")).hasMessageContaining("link-local");
        assertThatThrownBy(() -> https.remote("https://metadata.google.internal/x")).hasMessageContaining("metadata");
        assertThatThrownBy(() -> https.remote(url)).hasMessageContaining("file://");
        assertThatThrownBy(() -> https.remote(" ")).hasMessageContaining("repo is required");
        assertThatThrownBy(() -> https.resolve("github.com/acme/ops", "--upload-pack=touch", null).block()).hasMessageContaining("not a git ref");
        assertThatThrownBy(() -> https.resolve("github.com/acme/ops", "main", "plain-secret").block()).hasMessageContaining("vault:<path>");
        assertThatThrownBy(() -> https.resolve("github.com/acme/ops", "main", "vault:git/none").block())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no secret at vault:git/none");
    }

    // ---------------------------------------------------------------- over http

    static final String HTTP_V2_TAG = "1111111111111111111111111111111111111111";
    static final String HTTP_V2_COMMIT = "2222222222222222222222222222222222222222";
    static final String HTTP_MAIN = "3333333333333333333333333333333333333333";

    final List<String> authorizations = new CopyOnWriteArrayList<>();

    /** The dumb HTTP protocol: info/refs as text and HEAD as a file, behind basic auth. */
    void dumbGit(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null) {
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"git\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        authorizations.add(auth);
        boolean smart = path.equals("/acme/smart.git/info/refs");
        String body = switch (path) {
            case "/acme/ops.git/info/refs" -> HTTP_MAIN + "\trefs/heads/main\n" + HTTP_V2_TAG + "\trefs/tags/v2\n" + HTTP_V2_COMMIT + "\trefs/tags/v2^{}\n";
            case "/acme/ops.git/HEAD" -> "ref: refs/heads/main\n";
            case "/acme/smart.git/info/refs" -> pkt("# service=git-upload-pack\n") + "0000"
                    + pkt(HTTP_MAIN + " HEAD\0multi_ack side-band-64k ofs-delta symref=HEAD:refs/heads/main\n")
                    + pkt(HTTP_MAIN + " refs/heads/main\n") + pkt(HTTP_V2_TAG + " refs/tags/v2\n") + pkt(HTTP_V2_COMMIT + " refs/tags/v2^{}\n") + "0000";
            default -> null;
        };
        if (body == null) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", smart ? "application/x-git-upload-pack-advertisement" : "text/plain");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void aPrivateRemoteGetsTheVaultCredentialAndTagsArePeeled() throws Exception {
        HttpServer server = LocalHttp.start(this::dumbGit);
        try {
            when(vault.resolve("vault:git/acme")).thenReturn(Mono.just("deploy:not-a-real-token"));
            when(vault.resolve("vault:git/token")).thenReturn(Mono.just("not-a-real-token"));
            JGitRemotes remotes = new JGitRemotes(vault, Duration.ofSeconds(5), dir.resolve("cache"), false, true);
            String repo = LocalHttp.url(server) + "/acme/ops.git";

            Resolved v2 = remotes.resolve(repo, "v2", "vault:git/acme").block(Duration.ofSeconds(20));
            assertThat(v2.commit()).isEqualTo(HTTP_V2_COMMIT);
            assertThat(authorizations).contains(basic("deploy:not-a-real-token"));

            authorizations.clear();
            assertThat(remotes.resolve(repo, "main", "vault:git/token").block(Duration.ofSeconds(20)).commit()).isEqualTo(HTTP_MAIN);
            assertThat(authorizations).contains(basic(JGitRemotes.TOKEN_USER + ":not-a-real-token"));

            assertThatThrownBy(() -> remotes.resolve(repo, "main", null).block(Duration.ofSeconds(20)))
                    .isInstanceOf(RemoteUnavailable.class).hasMessageContaining("Could not read " + repo);

            String smart = LocalHttp.url(server) + "/acme/smart.git";
            assertThat(remotes.resolve(smart, "v2", "vault:git/acme").block(Duration.ofSeconds(20)).commit()).as("the smart protocol").isEqualTo(HTTP_V2_COMMIT);
            assertThat(remotes.resolve(smart, "HEAD", "vault:git/acme").block(Duration.ofSeconds(20)).commit()).isEqualTo(HTTP_MAIN);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRedirectToAnotherHostIsNotFollowed() throws Exception {
        List<String> elsewhere = new CopyOnWriteArrayList<>();
        HttpServer other = LocalHttp.start(ex -> {
            elsewhere.add(ex.getRequestURI().toString());
            LocalHttp.respond(ex, 404, null);
        });
        HttpServer server = LocalHttp.start(ex -> {
            ex.getResponseHeaders().add("Location", "http://localhost:" + other.getAddress().getPort() + ex.getRequestURI());
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        try {
            JGitRemotes remotes = new JGitRemotes(vault, Duration.ofSeconds(5), dir.resolve("cache"), false, true);

            assertThatThrownBy(() -> remotes.resolve(LocalHttp.url(server) + "/acme/ops.git", "main", null).block(Duration.ofSeconds(20)))
                    .isInstanceOf(RemoteUnavailable.class).hasMessageContaining("redirected to http://localhost");
            assertThat(elsewhere).isEmpty();
        } finally {
            server.stop(0);
            other.stop(0);
        }
    }

    // ---------------------------------------------------------------- checkout

    @Test
    void aCheckoutHoldsTheFilesOfTheCommitAndIsCachedPerCommit() throws Exception {
        JGitRemotes remotes = remotes();

        Path first = remotes.checkout(url, c1, null).block(Duration.ofSeconds(30));

        assertThat(first.resolve("site.yml")).hasContent("- hosts: all");
        assertThat(first.resolve("roles/web/tasks/main.yml")).exists();
        assertThat(first.resolve(".git")).doesNotExist();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(first.resolve("bin/inventory.sh")))).isEqualTo("rwxr-xr-x");
        Path third = remotes.checkout(url, c3, null).block(Duration.ofSeconds(30));
        assertThat(third).isNotEqualTo(first);
        assertThat(third.resolve("site.yml")).hasContent("- hosts: db");

        JGitRemotes.deleteTree(originDir);
        assertThat(remotes.checkout(url, c1.toUpperCase(), null).block(Duration.ofSeconds(30))).as("served from the cache").isEqualTo(first);
        try (Stream<Path> trees = Files.list(dir.resolve("cache/trees"))) {
            assertThat(trees.map(p -> p.getFileName().toString())).hasSize(2).noneMatch(n -> n.startsWith(".export-"));
        }
    }

    @Test
    void aCommitTheRemoteDoesNotHaveIsNotFound() {
        assertThatThrownBy(() -> remotes().checkout(url, "f".repeat(40), null).block(Duration.ofSeconds(30)))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("Commit " + "f".repeat(40));
        assertThatThrownBy(() -> remotes().checkout(url, "v1", null).block()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("full commit id");
    }

    @Test
    void linksMustStayInsideTheCheckout() throws Exception {
        Files.createDirectories(originDir.resolve("shared"));
        Files.createSymbolicLink(originDir.resolve("shared/web"), Path.of("../roles/web"));
        String inside = commit("link inside");
        Path tree = remotes().checkout(url, inside, null).block(Duration.ofSeconds(30));
        assertThat(tree.resolve("shared/web/tasks/main.yml")).hasContent("- ping:");

        Files.createSymbolicLink(originDir.resolve("escape"), Path.of("../../../../../../etc"));
        String outside = commit("link outside");
        assertThatThrownBy(() -> remotes().checkout(url, outside, null).block(Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Link escape points");
        try (Stream<Path> trees = Files.list(dir.resolve("cache/trees"))) {
            assertThat(trees.map(p -> p.getFileName().toString())).as("nothing half-exported is left").hasSize(1);
        }
    }

    static String pkt(String line) {
        return String.format("%04x", line.getBytes(StandardCharsets.UTF_8).length + 4) + line;
    }

    static String basic(String pair) {
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }
}
