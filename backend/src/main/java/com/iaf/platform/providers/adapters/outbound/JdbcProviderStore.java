package com.iaf.platform.providers.adapters.outbound;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

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

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The provider store of the {@code postgres} profile: one row of {@code providers} per provider,
 * its doc the whole stored provider (parsed definition, YAML as written, who changed it last).
 * At startup each {@code kind: Provider} file under the content dir that the database does not
 * hold yet is added, as in memory; a provider changed through the platform is never reset by its
 * file, and one still as seeded takes its file's new content. Reads come from a copy of the table
 * reloaded whenever any instance has written to it. With {@code platform.content.git} on, a save
 * also writes the file back, as the in-memory store does.
 */
@Slf4j
@Component
@Primary
@Profile("postgres")
public class JdbcProviderStore implements ProviderStorePort {

    static final String STORE = "providers";

    private final Jdbc jdbc;
    private final PlatformProperties props;
    private final Clock clock;
    private final Revisioned<Map<String, StoredProvider>> table;

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
        Map<String, StoredProvider> stored = table.get();
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
        return List.copyOf(table.get().values());
    }

    @Override
    public Optional<StoredProvider> get(String name) {
        return Optional.ofNullable(name == null ? null : table.get().get(name));
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
    public void delete(String name) {
        StoredProvider removed = get(name).orElse(null);
        if (removed == null) {
            return;
        }
        jdbc.inTransaction(() -> {
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

    private void upsert(StoredProvider p) {
        String doc = jdbc.json(p);
        String name = p.definition().name();
        jdbc.upsert("UPDATE providers SET updated_at = ?, doc = ? WHERE name = ?", new Object[] {Jdbc.time(p.updatedAt()), doc, name},
                "INSERT INTO providers (name, updated_at, doc) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", new Object[] {name, Jdbc.time(p.updatedAt()), doc});
    }

    /** The table by name, in name order as the in-memory store lists it. */
    private Map<String, StoredProvider> load() {
        Map<String, StoredProvider> byName = new TreeMap<>();
        for (StoredProvider p : jdbc.query("SELECT doc FROM providers", (rs, i) -> jdbc.read(rs.getString(1), StoredProvider.class))) {
            byName.put(p.definition().name(), p);
        }
        return byName;
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
