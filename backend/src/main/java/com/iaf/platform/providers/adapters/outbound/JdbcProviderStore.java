package com.iaf.platform.providers.adapters.outbound;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.adapters.jdbc.Revisioned;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.content.ContentFiles.ContentFile;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.workflows.domain.Semver;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The provider store of the {@code postgres} profile: one row of {@code providers} per provider,
 * its doc the whole stored provider (parsed definition, YAML as written, who changed it last),
 * and one row of {@code provider_versions} per version kept beside it: the current one and the
 * canary candidates ({@link #saveVersion}), which the provider's release routes calls to until one
 * is promoted and saved as current. At startup each {@code kind: Provider} file under the content
 * dir that the database does not hold yet is added, as in memory; a provider changed through the
 * platform is never reset by its file, and one still as seeded takes its file's new content.
 * Reads come from a copy of both tables reloaded whenever any instance has written to them. With
 * {@code platform.content.git} on, a save also writes the file back, as the in-memory store does;
 * a kept version is not written until it is current.
 */
@Slf4j
@Component
@Primary
@Profile("postgres")
public class JdbcProviderStore implements ProviderStorePort {

    static final String STORE = "providers";

    private static final Comparator<StoredProvider> NEWEST_FIRST = Comparator
            .comparing((StoredProvider p) -> Semver.parse(p.definition().metadata().version())).reversed();

    private final Jdbc jdbc;
    private final PlatformProperties props;
    private final Clock clock;
    private final Revisioned<Index> table;

    public JdbcProviderStore(Jdbc jdbc, PlatformProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.table = new Revisioned<>(jdbc, STORE, this::load);
    }

    @PostConstruct
    public void init() {
        seed();
    }

    /** Adds the provider files the database lacks and refreshes the ones still as seeded; returns how many it wrote. */
    public int seed() {
        Path root = root();
        if (root == null) {
            log.warn("content dir {} not found; no providers seeded", props.getContent().getDir());
            return 0;
        }
        Map<String, StoredProvider> stored = table.get().current();
        List<StoredProvider> writes = new ArrayList<>();
        int added = 0;
        for (ContentFile f : ContentFiles.ofKind(root, ProviderDefinition.KIND)) {
            ProviderDefinition def;
            try {
                def = ProviderYaml.parse(f.yaml());
            } catch (IllegalArgumentException e) {
                log.warn("skipping provider file {}: {}", f.relative(root), e.getMessage());
                continue;
            }
            StoredProvider existing = stored.get(def.name());
            if (existing == null) {
                added++;
            } else if (!InMemoryProviderStore.SEED_AUTHOR.equals(existing.updatedBy()) || Objects.equals(existing.yaml(), f.yaml())) {
                continue;
            }
            writes.add(new StoredProvider(def, f.yaml(), f.relative(root), modifiedAt(f.path()), InMemoryProviderStore.SEED_AUTHOR));
        }
        if (!writes.isEmpty()) {
            jdbc.inTransaction(() -> {
                writes.forEach(this::upsert);
                table.bump();
            });
        }
        log.info("provider store: {} provider(s) seeded and {} refreshed from {}", added, writes.size() - added, root);
        return writes.size();
    }

    @Override
    public List<StoredProvider> all() {
        return List.copyOf(table.get().current().values());
    }

    @Override
    public Optional<StoredProvider> get(String name) {
        return Optional.ofNullable(name == null ? null : table.get().current().get(name));
    }

    @Override
    public List<StoredProvider> versions(String name) {
        return name == null ? List.of() : table.get().versions().getOrDefault(name, List.of());
    }

    @Override
    public void save(StoredProvider provider) {
        jdbc.inTransaction(() -> {
            upsert(provider);
            table.bump();
        });
        if (props.getContent().isGit()) {
            write(provider);
        }
    }

    @Override
    public void saveVersion(StoredProvider provider) {
        jdbc.inTransaction(() -> {
            if (keep(provider, jdbc.json(provider))) {
                table.bump();
            }
        });
    }

    @Override
    public void delete(String name) {
        StoredProvider removed = get(name).orElse(null);
        if (removed == null) {
            return;
        }
        jdbc.inTransaction(() -> {
            jdbc.update("DELETE FROM provider_versions WHERE name = ?", name);
            jdbc.update("DELETE FROM providers WHERE name = ?", name);
            table.bump();
        });
        if (props.getContent().isGit()) {
            Path file = fileOf(removed);
            try {
                if (file != null) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException e) {
                log.warn("could not delete {}: {}", file, e.getMessage());
            }
        }
    }

    /** Makes the provider current and keeps it among its versions. */
    private void upsert(StoredProvider p) {
        String doc = jdbc.json(p);
        String name = p.definition().name();
        jdbc.upsert("UPDATE providers SET updated_at = ?, doc = ? WHERE name = ?", new Object[] {Jdbc.time(p.updatedAt()), doc, name},
                "INSERT INTO providers (name, updated_at, doc) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", new Object[] {name, Jdbc.time(p.updatedAt()), doc});
        keep(p, doc);
    }

    /** Keeps a version by its number, as in memory; a provider without one is only ever current. Returns whether a row was written. */
    private boolean keep(StoredProvider p, String doc) {
        String version = p.definition().metadata().version();
        if (version == null) {
            return false;
        }
        String name = p.definition().name();
        jdbc.upsert("UPDATE provider_versions SET updated_at = ?, doc = ? WHERE name = ? AND version = ?",
                new Object[] {Jdbc.time(p.updatedAt()), doc, name, version},
                "INSERT INTO provider_versions (name, version, updated_at, doc) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                new Object[] {name, version, Jdbc.time(p.updatedAt()), doc});
        return true;
    }

    private Index load() {
        return Index.of(jdbc.query("SELECT doc FROM providers", (rs, i) -> jdbc.read(rs.getString(1), StoredProvider.class)),
                jdbc.query("SELECT doc FROM provider_versions", (rs, i) -> jdbc.read(rs.getString(1), StoredProvider.class)));
    }

    /**
     * Both tables as the in-memory store holds them: the current providers in name order, and each
     * provider's kept versions newest first. A current version with no row of its own (written
     * before versions were kept) counts among the versions, as the port's default reads it.
     */
    private record Index(Map<String, StoredProvider> current, Map<String, List<StoredProvider>> versions) {

        static Index of(List<StoredProvider> currentRows, List<StoredProvider> keptRows) {
            Map<String, StoredProvider> current = new TreeMap<>();
            for (StoredProvider p : currentRows) {
                current.put(p.definition().name(), p);
            }
            Map<String, Map<String, StoredProvider>> kept = new HashMap<>();
            for (StoredProvider p : keptRows) {
                kept.computeIfAbsent(p.definition().name(), n -> new LinkedHashMap<>()).put(p.definition().metadata().version(), p);
            }
            Map<String, List<StoredProvider>> versions = new HashMap<>();
            TreeSet<String> names = new TreeSet<>(current.keySet());
            names.addAll(kept.keySet());
            for (String name : names) {
                StoredProvider c = current.get(name);
                Map<String, StoredProvider> byVersion = kept.getOrDefault(name, Map.of());
                if (byVersion.isEmpty()) {
                    versions.put(name, List.of(c));
                    continue;
                }
                List<StoredProvider> all = new ArrayList<>(byVersion.values());
                String currentVersion = c == null ? null : c.definition().metadata().version();
                if (currentVersion != null && !byVersion.containsKey(currentVersion)) {
                    all.add(c);
                }
                all.sort(NEWEST_FIRST);
                versions.put(name, List.copyOf(all));
            }
            return new Index(current, versions);
        }
    }

    private void write(StoredProvider provider) {
        Path file = fileOf(provider);
        if (file == null) {
            log.warn("content dir {} not found; provider {} kept in the database only", props.getContent().getDir(), provider.definition().name());
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, provider.yaml(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file, e);
        }
    }

    private Path fileOf(StoredProvider provider) {
        Path root = root();
        if (root == null) {
            return null;
        }
        if (provider.path() != null && !provider.path().isBlank()) {
            return ContentFiles.inside(root, provider.path());
        }
        ProviderDefinition def = provider.definition();
        return ContentFiles.pathFor(root, "providers", def.owner(), def.name());
    }

    private Path root() {
        String dir = props.getContent().getDir();
        if (dir == null || dir.isBlank()) {
            return null;
        }
        Path p = Path.of(dir).toAbsolutePath().normalize();
        return Files.isDirectory(p) ? p : null;
    }

    private Instant modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return clock.instant();
        }
    }
}
