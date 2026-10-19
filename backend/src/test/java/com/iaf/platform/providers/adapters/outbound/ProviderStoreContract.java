package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;

/** What every {@link ProviderStorePort} does, in memory and in a database. */
abstract class ProviderStoreContract {

    static final Instant NOW = Instant.parse("2026-10-17T12:00:00.123456789Z");

    static final String ECHO = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: echo, title: Echo, owner: team-a, version: 1.0.0 }
            spec:
              type: http
              endpoints:
                prod: https://echo.example
              actions:
                - id: ping
                  title: Ping
                  operation: { method: GET, path: /ping }
                  effect: read
                  retry: safe
                  sample: { ok: true }
            """;

    @TempDir
    Path content;

    /** A store over {@code props}, seeded as at startup. */
    abstract ProviderStorePort newStore(PlatformProperties props);

    /** A new store over the same storage, seeded as after a restart; null when the storage does not outlive the store. */
    abstract ProviderStorePort reopen(PlatformProperties props);

    static PlatformProperties props(Path dir, boolean git) {
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir(dir.toString());
        props.getContent().setGit(git);
        return props;
    }

    void write(String yaml) throws Exception {
        Path file = content.resolve("providers/team-a/echo.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    /** The echo provider at another version, changed through the platform. */
    static StoredProvider echo(String version, String by) {
        String yaml = ECHO.replace("version: 1.0.0", "version: " + version);
        return new StoredProvider(ProviderYaml.parse(yaml), yaml, "providers/team-a/echo.yaml", NOW, by);
    }

    static List<String> versionsOf(List<StoredProvider> versions) {
        return versions.stream().map(p -> p.definition().metadata().version()).toList();
    }

    @Test
    void seedsEveryProviderFileExactlyAsParsed() {
        ProviderStorePort store = newStore(props(SampleCatalog.ROOT, false));
        List<ContentFiles.ContentFile> files = ContentFiles.ofKind(SampleCatalog.ROOT, ProviderDefinition.KIND);
        assertThat(store.all()).hasSize(files.size()).isNotEmpty();
        assertThat(store.all()).extracting(p -> p.definition().name()).isSorted();
        for (ContentFiles.ContentFile f : files) {
            ProviderDefinition parsed = ProviderYaml.parse(f.yaml());
            StoredProvider stored = store.get(parsed.name()).orElseThrow();
            assertThat(stored.definition()).as(f.relative(SampleCatalog.ROOT)).isEqualTo(parsed);
            assertThat(stored.yaml()).isEqualTo(f.yaml());
            assertThat(stored.path()).isEqualTo(f.relative(SampleCatalog.ROOT));
            assertThat(stored.updatedBy()).isEqualTo("content");
            assertThat(stored.updatedAt()).isNotNull();
        }
    }

    @Test
    void anUnknownProviderIsEmpty() {
        ProviderStorePort store = newStore(props(content, false));
        assertThat(store.all()).isEmpty();
        assertThat(store.get("nope")).isEmpty();
        assertThat(store.get(null)).isEmpty();
    }

    @Test
    void aMissingContentDirSeedsNothing() {
        ProviderStorePort store = newStore(props(content.resolve("missing"), false));
        assertThat(store.all()).isEmpty();
    }

    @Test
    void savesReplacesAndDeletesByName() throws Exception {
        write(ECHO);
        ProviderStorePort store = newStore(props(content, false));
        StoredProvider seeded = store.get("echo").orElseThrow();
        String yaml = ECHO.replace("https://echo.example", "https://echo2.example");
        StoredProvider changed = new StoredProvider(ProviderYaml.parse(yaml), yaml, seeded.path(), NOW, "ana");

        store.save(changed);
        assertThat(store.get("echo")).contains(changed);
        assertThat(store.all()).containsExactly(changed);
        assertThat(Files.readString(content.resolve("providers/team-a/echo.yaml"))).as("without Git the file stays").isEqualTo(ECHO);

        store.delete("echo");
        assertThat(store.get("echo")).isEmpty();
        assertThat(store.all()).isEmpty();
        store.delete("echo");
    }

    @Test
    void writesTheFileBackWhenTheContentIsInGit() throws Exception {
        ProviderStorePort store = newStore(props(content, true));
        ProviderDefinition def = ProviderYaml.parse(ECHO);
        store.save(new StoredProvider(def, ECHO, null, NOW, "ana"));
        Path file = ContentFiles.pathFor(content, "providers", "team-a", "echo");
        assertThat(file).hasContent(ECHO.strip());

        store.delete("echo");
        assertThat(file).doesNotExist();
    }

    @Test
    void keepsAProviderChangedThroughThePlatformAcrossARestart() throws Exception {
        write(ECHO);
        ProviderStorePort store = newStore(props(content, false));
        String yaml = ECHO.replace("https://echo.example", "https://echo2.example");
        StoredProvider changed = new StoredProvider(ProviderYaml.parse(yaml), yaml, "providers/team-a/echo.yaml", NOW, "ana");
        store.save(changed);

        ProviderStorePort after = reopen(props(content, false));
        assumeTrue(after != null, "this store does not outlive the process");
        assertThat(after.get("echo")).contains(changed);
        assertThat(after.all()).hasSize(1);
    }

    // ---------------------------------------------------------------- versions beside the current one

    @Test
    void aCanaryCandidateIsKeptBesideTheCurrentVersionWithoutReplacingIt() throws Exception {
        write(ECHO);
        ProviderStorePort store = newStore(props(content, false));
        StoredProvider current = store.get("echo").orElseThrow();
        assertThat(store.versions("echo")).as("the current version counts among the versions").containsExactly(current);
        StoredProvider candidate = echo("1.1.0", "ana");

        store.saveVersion(candidate);
        assertThat(store.get("echo")).contains(current);
        assertThat(store.all()).containsExactly(current);
        assertThat(store.versions("echo")).as("newest first").containsExactly(candidate, current);
        assertThat(store.version("echo", "1.1.0")).contains(candidate);
        assertThat(store.version("echo", "1.0.0")).contains(current);
        assertThat(store.version("echo", "2.0.0")).isEmpty();
        assertThat(store.version("echo", null)).isEmpty();
        assertThat(store.version("nope", "1.0.0")).isEmpty();
        assertThat(store.versions("nope")).isEmpty();
        assertThat(store.versions(null)).isEmpty();
    }

    @Test
    void savingAKeptVersionMakesItCurrentAndTheVersionsStayInSemverOrder() throws Exception {
        write(ECHO);
        ProviderStorePort store = newStore(props(content, false));
        store.saveVersion(echo("1.10.0", "ana"));
        store.saveVersion(echo("1.2.0", "ana"));
        assertThat(versionsOf(store.versions("echo"))).as("by version, not by text or by order of arrival").containsExactly("1.10.0", "1.2.0", "1.0.0");

        store.save(store.version("echo", "1.2.0").orElseThrow());
        assertThat(store.get("echo").map(p -> p.definition().metadata().version())).contains("1.2.0");
        assertThat(store.all()).hasSize(1);
        assertThat(versionsOf(store.versions("echo"))).containsExactly("1.10.0", "1.2.0", "1.0.0");

        StoredProvider again = new StoredProvider(echo("1.10.0", "mateo").definition(), echo("1.10.0", "mateo").yaml(), "providers/team-a/echo.yaml", NOW.plusSeconds(60), "mateo");
        store.saveVersion(again);
        assertThat(store.version("echo", "1.10.0")).as("a version saved again replaces the kept one").contains(again);
        assertThat(store.versions("echo")).hasSize(3);

        StoredProvider current = store.get("echo").orElseThrow();
        store.saveVersion(echo("1.2.0", "mateo"));
        assertThat(store.version("echo", "1.2.0")).as("the current version reads as the current provider").contains(current);
        assertThat(store.versions("echo")).hasSize(3).contains(current);
    }

    @Test
    void aKeptVersionIsNotWrittenToTheContentRepoUntilItIsCurrent() throws Exception {
        ProviderStorePort store = newStore(props(content, true));
        store.save(echo("1.0.0", "ana"));
        Path file = content.resolve("providers/team-a/echo.yaml");
        assertThat(Files.readString(file)).contains("version: 1.0.0");

        store.saveVersion(echo("1.1.0", "ana"));
        assertThat(Files.readString(file)).as("the file stays the current version").contains("version: 1.0.0");

        store.save(store.version("echo", "1.1.0").orElseThrow());
        assertThat(Files.readString(file)).contains("version: 1.1.0");
    }

    @Test
    void deletingAProviderForgetsEveryVersion() throws Exception {
        write(ECHO);
        ProviderStorePort store = newStore(props(content, false));
        store.saveVersion(echo("1.1.0", "ana"));

        store.delete("echo");
        assertThat(store.get("echo")).isEmpty();
        assertThat(store.versions("echo")).isEmpty();
        assertThat(store.version("echo", "1.1.0")).isEmpty();
    }

    @Test
    void keepsACanaryCandidateAcrossARestart() throws Exception {
        write(ECHO);
        ProviderStorePort store = newStore(props(content, false));
        StoredProvider current = store.get("echo").orElseThrow();
        StoredProvider candidate = echo("1.1.0", "ana");
        store.saveVersion(candidate);

        ProviderStorePort after = reopen(props(content, false));
        assumeTrue(after != null, "this store does not outlive the process");
        assertThat(after.get("echo")).as("the candidate did not become current").contains(current);
        assertThat(after.version("echo", "1.1.0")).contains(candidate);
        assertThat(after.versions("echo")).containsExactly(candidate, current);
    }
}
