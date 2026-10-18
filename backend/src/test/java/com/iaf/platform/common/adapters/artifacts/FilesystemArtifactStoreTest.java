package com.iaf.platform.common.adapters.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.common.ports.ArtifactStorePort.Artifact;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class FilesystemArtifactStoreTest {

    static final String HELLO_SHA = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @TempDir
    Path dir;

    FilesystemArtifactStore store() {
        return new FilesystemArtifactStore(dir.resolve("store"));
    }

    @Test
    void putAnswersTheDigestAndSizeOfTheContent() {
        StepVerifier.create(store().put("hello".getBytes(StandardCharsets.UTF_8)))
                .expectNext(new Artifact(HELLO_SHA, 5))
                .verifyComplete();
    }

    @Test
    void putOfAFileStoresItsContentAndLeavesTheFileAlone() throws Exception {
        Path file = Files.writeString(dir.resolve("restart.yml"), "hello");
        FilesystemArtifactStore store = store();

        Artifact a = store.put(file).block();

        assertThat(a).isEqualTo(new Artifact(HELLO_SHA, 5));
        assertThat(file).exists().hasContent("hello");
        assertThat(store.pathOf(HELLO_SHA)).exists().hasContent("hello");
        assertThat(store.pathOf(HELLO_SHA).toString()).endsWith("sha256/2c/" + HELLO_SHA);
    }

    @Test
    void theSameContentIsStoredOnceReadOnlyAndNeverOverwritten() throws Exception {
        FilesystemArtifactStore store = store();
        store.put("hello".getBytes(StandardCharsets.UTF_8)).block();
        Path stored = store.pathOf(HELLO_SHA);
        var firstWrite = Files.getLastModifiedTime(stored);
        Thread.sleep(20);

        Artifact again = store.put("hello".getBytes(StandardCharsets.UTF_8)).block();

        assertThat(again.sha256()).isEqualTo(HELLO_SHA);
        assertThat(Files.getLastModifiedTime(stored)).isEqualTo(firstWrite);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(stored))).isEqualTo("r--r--r--");
        try (Stream<Path> files = Files.walk(dir.resolve("store/sha256"))) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
        }
        try (Stream<Path> staging = Files.list(dir.resolve("store/staging"))) {
            assertThat(staging.count()).as("no staging file is left behind").isZero();
        }
    }

    @Test
    void concurrentPutsOfTheSameContentAllAnswerTheSameDigest() {
        FilesystemArtifactStore store = store();
        byte[] content = "same bytes from many jobs".getBytes(StandardCharsets.UTF_8);

        List<Artifact> all = Flux.fromStream(IntStream.range(0, 16).boxed())
                .flatMap(i -> store.put(content).subscribeOn(Schedulers.parallel()), 16)
                .collectList().block(Duration.ofSeconds(10));

        assertThat(all).hasSize(16).extracting(Artifact::sha256).containsOnly(all.get(0).sha256());
        assertThat(store.exists(all.get(0).sha256()).block()).isTrue();
    }

    @Test
    void getCopiesTheContentIntoTheTarget() {
        FilesystemArtifactStore store = store();
        store.put("hello".getBytes(StandardCharsets.UTF_8)).block();
        Path target = dir.resolve("out/nested/copy.yml");

        StepVerifier.create(store.get("sha256:" + HELLO_SHA.toUpperCase(), target)).expectNext(target).verifyComplete();

        assertThat(target).hasContent("hello");
    }

    @Test
    void getRefusesAStoredFileWhoseContentNoLongerMatchesItsDigest() throws Exception {
        FilesystemArtifactStore store = store();
        store.put("hello".getBytes(StandardCharsets.UTF_8)).block();
        Path stored = store.pathOf(HELLO_SHA);
        Files.setPosixFilePermissions(stored, PosixFilePermissions.fromString("rw-r--r--"));
        Files.writeString(stored, "tampered");
        Path target = dir.resolve("copy.yml");

        StepVerifier.create(store.get(HELLO_SHA, target))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalStateException.class).hasMessageContaining("corrupt"))
                .verify();
        assertThat(target).doesNotExist();
    }

    @Test
    void anUnknownDigestIsEmptyAndDoesNotExist() {
        String unknown = "0".repeat(64);
        StepVerifier.create(store().get(unknown, dir.resolve("x"))).verifyComplete();
        StepVerifier.create(store().exists(unknown)).expectNext(false).verifyComplete();
    }

    @Test
    void aNamedObjectIsStoredReadOnlyReadBackAndReplacedWhenWrittenAgain() throws Exception {
        FilesystemArtifactStore store = store();
        byte[] first = "{\"seq\":1}\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"seq\":1}\n{\"seq\":2}\n".getBytes(StandardCharsets.UTF_8);
        Path stored = dir.resolve("store/jobs/j1/logs.ndjson");

        StepVerifier.create(store.putNamed("jobs/j1/logs.ndjson", first)).verifyComplete();

        assertThat(stored).hasBinaryContent(first);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(stored))).isEqualTo("r--r--r--");
        StepVerifier.create(store.getNamed("jobs/j1/logs.ndjson")).assertNext(b -> assertThat(b).isEqualTo(first)).verifyComplete();
        StepVerifier.create(store.existsNamed("jobs/j1/logs.ndjson")).expectNext(true).verifyComplete();

        StepVerifier.create(store.putNamed("jobs/j1/logs.ndjson", second)).verifyComplete();

        StepVerifier.create(store.getNamed("jobs/j1/logs.ndjson")).assertNext(b -> assertThat(b).isEqualTo(second)).verifyComplete();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(stored))).isEqualTo("r--r--r--");
        try (Stream<Path> staging = Files.list(dir.resolve("store/staging"))) {
            assertThat(staging.count()).as("no staging file is left behind").isZero();
        }
    }

    @Test
    void concurrentWritesOfOneNameLeaveOneOfThemWhole() throws Exception {
        FilesystemArtifactStore store = store();
        String big = "x".repeat(64 * 1024);

        Flux.range(0, 8)
                .flatMap(i -> store.putNamed("jobs/j2/plan.json", ("{\"n\":" + i + ",\"pad\":\"" + big + "\"}").getBytes(StandardCharsets.UTF_8))
                        .subscribeOn(Schedulers.parallel()), 8)
                .then().block(Duration.ofSeconds(10));

        assertThat(Files.readString(dir.resolve("store/jobs/j2/plan.json"))).matches("\\{\"n\":[0-7],\"pad\":\"x{65536}\"}");
        try (Stream<Path> staging = Files.list(dir.resolve("store/staging"))) {
            assertThat(staging.count()).as("no staging file is left behind").isZero();
        }
    }

    @Test
    void anUnknownNameIsEmptyAndDoesNotExist() {
        StepVerifier.create(store().getNamed("jobs/none/logs.ndjson")).verifyComplete();
        StepVerifier.create(store().existsNamed("jobs/none/logs.ndjson")).expectNext(false).verifyComplete();
    }

    @Test
    void aNameThatCouldLeaveItsPlaceIsABadRequest() {
        for (String bad : List.of("../outside", "jobs/../../x", "/abs", "jobs//x", "jobs/x/", ".hidden", "jobs/.git/config",
                "sha256/2c/" + HELLO_SHA, "staging/put-1.part", "a b", "")) {
            assertThatThrownBy(() -> ArtifactStorePort.name(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        StepVerifier.create(store().putNamed("../outside", new byte[0])).expectError(IllegalArgumentException.class).verify();
        assertThat(dir.resolve("outside")).doesNotExist();
        assertThat(ArtifactStorePort.name("jobs/AbC-12_x/logs.ndjson")).isEqualTo("jobs/AbC-12_x/logs.ndjson");
    }

    @Test
    void somethingThatIsNotADigestIsABadRequest() {
        StepVerifier.create(store().exists("../../etc/passwd")).expectError(IllegalArgumentException.class).verify();
        assertThatThrownBy(() -> ArtifactStorePort.digest("abc")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64 hex");
        assertThat(ArtifactStorePort.digest("SHA256:" + HELLO_SHA.toUpperCase())).isEqualTo(HELLO_SHA);
    }
}
