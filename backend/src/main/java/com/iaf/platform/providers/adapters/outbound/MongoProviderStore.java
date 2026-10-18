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

import org.bson.Document;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.adapters.mongo.Revisioned;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.content.ContentFiles.ContentFile;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.workflows.domain.Semver;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The provider store of the {@code mongo} profile: one document of {@code providers} per provider,
 * keyed by name, its doc the whole stored provider (parsed definition, YAML as written, who
 * changed it last), and one document of {@code provider_versions} per version kept beside it,
 * keyed {@code <name>@<version>}: the current one and the canary candidates ({@link #saveVersion}),
 * which the provider's release routes calls to until one is promoted and saved as current. At
 * startup each {@code kind: Provider} file under the content dir that the database does not hold
 * yet is added, as in memory; a provider changed through the platform is never reset by its file,
 * and one still as seeded takes its file's new content. Reads come from a copy of both collections
 * reloaded whenever any instance has written to them. With {@code platform.content.git} on, a save
 * also writes the file back, as the in-memory store does; a kept version is not written until it
 * is current.
 *
 * <p>A save writes the provider's document, then its version's: one that stops in between leaves a
 * current version without a document of its own in {@code provider_versions}, which still counts
 * among the versions (see {@link Index}). A delete removes the versions first, then the provider:
 * one that stops in between leaves the provider with its current version alone, and deleting it
 * again finishes the job.
 */
@Slf4j
@Component
@Primary
@Profile("mongo")
public class MongoProviderStore implements ProviderStorePort {

    static final String STORE = "providers";
    static final String PROVIDERS = "providers";
    static final String VERSIONS = "provider_versions";

    private static final Comparator<StoredProvider> NEWEST_FIRST = Comparator
            .comparing((StoredProvider p) -> Semver.parse(p.definition().metadata().version())).reversed();

    private final Mongo mongo;
    private final PlatformProperties props;
    private final Clock clock;
    private final Revisioned<Index> table;

    public MongoProviderStore(Mongo mongo, PlatformProperties props, Clock clock) {
        this.mongo = mongo;
        this.props = props;
        this.clock = clock;
        this.table = new Revisioned<>(mongo, STORE, this::load);
        mongo.index(VERSIONS, Indexes.ascending("name"));
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
            writes.forEach(this::upsert);
            table.bump();
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
        upsert(provider);
        table.bump();
        if (props.getContent().isGit()) {
            write(provider);
        }
    }

    @Override
    public void saveVersion(StoredProvider provider) {
        if (keep(provider, mongo.doc(provider))) {
            table.bump();
        }
    }

    @Override
    public void delete(String name) {
        StoredProvider removed = get(name).orElse(null);
        if (removed == null) {
            return;
        }
        mongo.collection(VERSIONS).deleteMany(Filters.eq("name", name));
        mongo.collection(PROVIDERS).deleteOne(Filters.eq("_id", name));
        table.bump();
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

    /** Makes the provider current and keeps it among its versions, in that order. */
    private void upsert(StoredProvider p) {
        Document doc = mongo.doc(p);
        String name = p.definition().name();
        mongo.collection(PROVIDERS).replaceOne(Filters.eq("_id", name),
                new Document("_id", name).append("updatedAt", Mongo.date(p.updatedAt())).append(Mongo.DOC, doc), new ReplaceOptions().upsert(true));
        keep(p, doc);
    }

    /** Keeps a version by its number, as in memory; a provider without one is only ever current. Returns whether a document was written. */
    private boolean keep(StoredProvider p, Document doc) {
        String version = p.definition().metadata().version();
        if (version == null) {
            return false;
        }
        String name = p.definition().name();
        String key = key(name, version);
        mongo.collection(VERSIONS).replaceOne(Filters.eq("_id", key), new Document("_id", key).append("name", name).append("version", version)
                .append("updatedAt", Mongo.date(p.updatedAt())).append(Mongo.DOC, doc), new ReplaceOptions().upsert(true));
        return true;
    }

    /** The key of a kept version: provider names are slugs, without an {@code @}, so no two versions share one. */
    static String key(String name, String version) {
        return name + "@" + version;
    }

    private Index load() {
        List<StoredProvider> current = new ArrayList<>();
        mongo.collection(PROVIDERS).find().forEach(d -> current.add(mongo.read(d, StoredProvider.class)));
        List<StoredProvider> kept = new ArrayList<>();
        mongo.collection(VERSIONS).find().forEach(d -> kept.add(mongo.read(d, StoredProvider.class)));
        return Index.of(current, kept);
    }

    /**
     * Both collections as the in-memory store holds them: the current providers in name order, and
     * each provider's kept versions newest first. A current version with no document of its own in
     * {@code provider_versions} (a save that stopped between its two writes) counts among the
     * versions, as the port's default reads it.
     */
    private record Index(Map<String, StoredProvider> current, Map<String, List<StoredProvider>> versions) {

        static Index of(List<StoredProvider> currentDocs, List<StoredProvider> keptDocs) {
            Map<String, StoredProvider> current = new TreeMap<>();
            for (StoredProvider p : currentDocs) {
                current.put(p.definition().name(), p);
            }
            Map<String, Map<String, StoredProvider>> kept = new HashMap<>();
            for (StoredProvider p : keptDocs) {
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
