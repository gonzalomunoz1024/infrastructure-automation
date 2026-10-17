package com.iaf.platform.providers.adapters.outbound;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.content.ContentFiles.ContentFile;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The demo store: every {@code kind: Provider} file under {@code platform.content.dir}, kept in
 * memory. With {@code platform.content.git} on, a save also writes the file back to
 * {@code providers/<owner>/<name>.yaml} (the commit is the workflows slice's job).
 */
@Slf4j
@Component
public class InMemoryProviderStore implements ProviderStorePort {

    static final String SEED_AUTHOR = "content";

    private final PlatformProperties props;
    private final Clock clock;
    private final Map<String, StoredProvider> providers = new ConcurrentHashMap<>();

    public InMemoryProviderStore(PlatformProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    public void seed() {
        Path root = root();
        if (root == null) {
            log.warn("content dir {} not found; no providers seeded", props.getContent().getDir());
            return;
        }
        int seeded = 0;
        for (ContentFile f : ContentFiles.ofKind(root, ProviderDefinition.KIND)) {
            try {
                ProviderDefinition def = ProviderYaml.parse(f.yaml());
                providers.put(def.name(), new StoredProvider(def, f.yaml(), f.relative(root), modifiedAt(f.path()), SEED_AUTHOR));
                seeded++;
            } catch (IllegalArgumentException e) {
                log.warn("skipping provider file {}: {}", f.relative(root), e.getMessage());
            }
        }
        log.info("seeded {} providers from {}", seeded, root);
    }

    @Override
    public List<StoredProvider> all() {
        return providers.values().stream().sorted(Comparator.comparing(p -> p.definition().name())).toList();
    }

    @Override
    public Optional<StoredProvider> get(String name) {
        return Optional.ofNullable(name == null ? null : providers.get(name));
    }

    @Override
    public void save(StoredProvider provider) {
        providers.put(provider.definition().name(), provider);
        if (props.getContent().isGit()) {
            write(provider);
        }
    }

    @Override
    public void delete(String name) {
        StoredProvider removed = providers.remove(name);
        if (removed != null && props.getContent().isGit()) {
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

    private void write(StoredProvider provider) {
        Path file = fileOf(provider);
        if (file == null) {
            log.warn("content dir {} not found; provider {} kept in memory only", props.getContent().getDir(), provider.definition().name());
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
            return root.resolve(provider.path()).normalize();
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
