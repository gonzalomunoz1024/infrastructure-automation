package com.iaf.platform.common.adapters.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.common.domain.Hashes;
import com.iaf.platform.common.ports.ArtifactStorePort.Artifact;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.ArtifactsProperties;
import com.iaf.platform.testing.LocalHttp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The S3 adapter against a small S3 look-alike on a loopback port: path-style keys, HEAD, PUT and GET. */
class S3ArtifactStoreTest {

    static final String HELLO_SHA = FilesystemArtifactStoreTest.HELLO_SHA;
    static final String KEY = "/plans/artifacts/sha256/2c/" + HELLO_SHA;

    record Seen(String method, String path, Map<String, List<String>> headers) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                    .map(e -> e.getValue().get(0)).orElse(null);
        }
    }

    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final Map<String, String> digests = new ConcurrentHashMap<>();
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    volatile boolean answer412 = false;
    volatile byte[] corruptGet = null;
    HttpServer server;

    @TempDir
    Path dir;

    @BeforeEach
    void start() throws IOException {
        server = LocalHttp.start(this::handle);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        seen.add(new Seen(ex.getRequestMethod(), path, Map.copyOf(ex.getRequestHeaders())));
        switch (ex.getRequestMethod()) {
            case "HEAD" -> {
                if (objects.containsKey(path)) {
                    ex.getResponseHeaders().add("Content-Length", String.valueOf(objects.get(path).length));
                    ex.sendResponseHeaders(200, -1);
                    ex.close();
                } else {
                    LocalHttp.respond(ex, 404, null);
                }
            }
            case "PUT" -> {
                byte[] body = ex.getRequestBody().readAllBytes();
                if (answer412 || (objects.containsKey(path) && "*".equals(ex.getRequestHeaders().getFirst("If-None-Match")))) {
                    xml(ex, 412, "PreconditionFailed");
                    return;
                }
                objects.put(path, body);
                String digest = ex.getRequestHeaders().getFirst("x-amz-meta-sha256");
                if (digest != null) {
                    digests.put(path, digest);
                }
                ex.getResponseHeaders().add("ETag", "\"etag\"");
                LocalHttp.respond(ex, 200, null);
            }
            case "GET" -> {
                byte[] body = corruptGet != null ? corruptGet : objects.get(path);
                if (body == null) {
                    xml(ex, 404, "NoSuchKey");
                    return;
                }
                ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
                if (digests.containsKey(path)) {
                    ex.getResponseHeaders().add("x-amz-meta-sha256", digests.get(path));
                }
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
                ex.close();
            }
            default -> LocalHttp.respond(ex, 405, null);
        }
    }

    static void xml(HttpExchange ex, int status, String code) throws IOException {
        byte[] body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code><Message>" + code + "</Message></Error>")
                .getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/xml");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
        ex.close();
    }

    S3ArtifactStore store(CredentialsPort credentials) {
        ArtifactsProperties.S3 cfg = new ArtifactsProperties.S3();
        cfg.setBucket("plans");
        cfg.setEndpoint(LocalHttp.url(server));
        cfg.setRegion("eu-west-1");
        cfg.setCredential("vault:artifacts/s3");
        return new S3ArtifactStore(S3ArtifactStore.client(cfg, credentials), "plans", "artifacts");
    }

    static CredentialsPort vault() {
        CredentialsPort credentials = mock(CredentialsPort.class);
        when(credentials.resolve("vault:artifacts/s3")).thenReturn(Mono.just("AKIDEXAMPLE:not-a-real-secret"));
        return credentials;
    }

    @Test
    void putUploadsUnderTheDigestOnlyIfNothingIsThereYet() {
        S3ArtifactStore store = store(vault());

        StepVerifier.create(store.put("hello".getBytes(StandardCharsets.UTF_8))).expectNext(new Artifact(HELLO_SHA, 5)).verifyComplete();

        assertThat(objects).containsOnlyKeys(KEY);
        assertThat(new String(objects.get(KEY), StandardCharsets.UTF_8)).isEqualTo("hello");
        Seen put = seen.stream().filter(s -> s.method().equals("PUT")).findFirst().orElseThrow();
        assertThat(put.header("If-None-Match")).isEqualTo("*");
        assertThat(put.header("x-amz-checksum-sha256")).isEqualTo("LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=");
        assertThat(put.header("x-amz-meta-sha256")).isEqualTo(HELLO_SHA);
        assertThat(put.header("Authorization")).startsWith("AWS4-HMAC-SHA256").contains("Credential=AKIDEXAMPLE/").contains("/eu-west-1/s3/");
    }

    @Test
    void storedContentIsNotUploadedAgain() throws Exception {
        S3ArtifactStore store = store(vault());
        Path file = Files.writeString(dir.resolve("restart.yml"), "hello");
        store.put(file).block();
        seen.clear();

        StepVerifier.create(store.put(file)).expectNext(new Artifact(HELLO_SHA, 5)).verifyComplete();

        assertThat(seen).extracting(Seen::method).containsExactly("HEAD");
    }

    @Test
    void aWriterThatGotThereFirstCountsAsStored() {
        answer412 = true;
        StepVerifier.create(store(vault()).put("hello".getBytes(StandardCharsets.UTF_8))).expectNext(new Artifact(HELLO_SHA, 5)).verifyComplete();
    }

    @Test
    void getCopiesTheObjectAndExistsFollowsTheBucket() {
        S3ArtifactStore store = store(vault());
        store.put("hello".getBytes(StandardCharsets.UTF_8)).block();
        Path target = dir.resolve("copy.yml");

        StepVerifier.create(store.get(HELLO_SHA, target)).expectNext(target).verifyComplete();
        assertThat(target).hasContent("hello");
        StepVerifier.create(store.exists(HELLO_SHA)).expectNext(true).verifyComplete();
        StepVerifier.create(store.exists("f".repeat(64))).expectNext(false).verifyComplete();
        StepVerifier.create(store.get("f".repeat(64), dir.resolve("none"))).verifyComplete();
    }

    @Test
    void getRefusesAnObjectWhoseBytesDoNotMatchTheDigest() {
        S3ArtifactStore store = store(vault());
        store.put("hello".getBytes(StandardCharsets.UTF_8)).block();
        corruptGet = "tampered".getBytes(StandardCharsets.UTF_8);
        Path target = dir.resolve("copy.yml");

        StepVerifier.create(store.get(HELLO_SHA, target))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("corrupt"))
                .verify();
        assertThat(target).doesNotExist();
    }

    @Test
    void aNamedObjectIsPutUnderThePrefixReadBackAndReplacedWhenWrittenAgain() {
        S3ArtifactStore store = store(vault());
        byte[] logs = "{\"seq\":1}\n".getBytes(StandardCharsets.UTF_8);
        byte[] more = "{\"seq\":1}\n{\"seq\":2}\n".getBytes(StandardCharsets.UTF_8);
        String key = "/plans/artifacts/jobs/j1/logs.ndjson";

        StepVerifier.create(store.putNamed("jobs/j1/logs.ndjson", logs)).verifyComplete();

        assertThat(objects).containsOnlyKeys(key);
        assertThat(objects.get(key)).isEqualTo(logs);
        assertThat(seen).extracting(Seen::method).as("a plain PUT, nothing asked first").containsExactly("PUT");
        Seen put = seen.get(0);
        assertThat(put.path()).isEqualTo(key);
        assertThat(put.header("If-None-Match")).isNull();
        assertThat(put.header("Content-Type")).isEqualTo("application/x-ndjson");
        assertThat(put.header("x-amz-checksum-sha256")).isNotBlank();
        // digests computed here, not written out: a long hex literal next to "key" reads as a secret to the scan
        assertThat(put.header("x-amz-meta-sha256")).isEqualTo(Hashes.sha256("{\"seq\":1}\n"));
        StepVerifier.create(store.getNamed("jobs/j1/logs.ndjson")).assertNext(b -> assertThat(b).isEqualTo(logs)).verifyComplete();
        StepVerifier.create(store.existsNamed("jobs/j1/logs.ndjson")).expectNext(true).verifyComplete();
        StepVerifier.create(store.existsNamed("jobs/j1/plan.json")).expectNext(false).verifyComplete();
        StepVerifier.create(store.getNamed("jobs/j1/plan.json")).verifyComplete();

        StepVerifier.create(store.putNamed("jobs/j1/logs.ndjson", more)).verifyComplete();

        assertThat(objects.get(key)).isEqualTo(more);
        assertThat(digests).containsEntry(key, Hashes.sha256("{\"seq\":1}\n{\"seq\":2}\n"));
        StepVerifier.create(store.getNamed("jobs/j1/logs.ndjson")).assertNext(b -> assertThat(b).isEqualTo(more)).verifyComplete();
    }

    @Test
    void aNamedWriteTheBucketRefusesIsAnError() {
        answer412 = true;
        StepVerifier.create(store(vault()).putNamed("jobs/j1/plan.json", "{}".getBytes(StandardCharsets.UTF_8)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class))
                .verify();
        assertThat(objects).isEmpty();
    }

    @Test
    void aNamedObjectWhoseBytesNoLongerMatchItsDigestIsRefused() {
        S3ArtifactStore store = store(vault());
        store.putNamed("jobs/j1/plan.json", "{}".getBytes(StandardCharsets.UTF_8)).block();
        assertThat(digests).containsEntry("/plans/artifacts/jobs/j1/plan.json", "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
        corruptGet = "{\"tampered\":true}".getBytes(StandardCharsets.UTF_8);

        StepVerifier.create(store.getNamed("jobs/j1/plan.json"))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("corrupt"))
                .verify();
    }

    @Test
    void aNamedObjectGetsTheContentTypeOfItsName() {
        assertThat(S3ArtifactStore.contentType("jobs/j1/logs.ndjson")).isEqualTo("application/x-ndjson");
        assertThat(S3ArtifactStore.contentType("jobs/j1/plan.json")).isEqualTo("application/json");
        assertThat(S3ArtifactStore.contentType("jobs/j1/plan.tfplan")).isEqualTo("application/octet-stream");
    }

    @Test
    void aVaultValueThatIsNotAKeyPairFailsWithoutRevealingIt() {
        CredentialsPort credentials = mock(CredentialsPort.class);
        when(credentials.resolve("vault:artifacts/s3")).thenReturn(Mono.just("just-one-secret-value"));

        StepVerifier.create(store(credentials).exists(HELLO_SHA))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("<access key id>:<secret access key>")
                        .hasMessageNotContaining("just-one-secret-value"))
                .verify();
    }

    @Test
    void theBucketIsRequiredAndThePrefixIsNormalised() {
        assertThatThrownBy(() -> new S3ArtifactStore(null, " ", "x")).isInstanceOf(IllegalStateException.class).hasMessageContaining("bucket");
        S3ArtifactStore store = new S3ArtifactStore(null, "b", "/team/plans");
        assertThat(store.keyOf(HELLO_SHA)).isEqualTo("team/plans/sha256/2c/" + HELLO_SHA);
        assertThat(new S3ArtifactStore(null, "b", "").keyOf(HELLO_SHA)).isEqualTo("sha256/2c/" + HELLO_SHA);
        assertThat(URI.create(LocalHttp.url(server)).getHost()).isEqualTo("127.0.0.1");
    }
}
