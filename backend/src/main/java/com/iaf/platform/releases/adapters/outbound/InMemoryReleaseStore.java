package com.iaf.platform.releases.adapters.outbound;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.ports.outbound.ReleaseStore;

/** The demo store: a map, newest release first. */
@Profile("!postgres")
@Component
public class InMemoryReleaseStore implements ReleaseStore {

    private static final Comparator<Release> NEWEST_FIRST = Comparator
            .comparing((Release r) -> r.startedAt() == null ? Instant.EPOCH : r.startedAt()).reversed()
            .thenComparing(Release::id);

    private final Map<String, Release> releases = new ConcurrentHashMap<>();

    @Override
    public Release save(Release release) {
        releases.put(release.id(), release);
        return release;
    }

    @Override
    public Optional<Release> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(releases.get(id));
    }

    @Override
    public List<Release> list(String workflow) {
        return releases.values().stream()
                .filter(r -> workflow == null || workflow.isBlank() || workflow.equals(r.workflow()))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public List<Release> listProvider(String provider) {
        return releases.values().stream()
                .filter(r -> provider != null && provider.equals(r.provider()))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public List<Release> active() {
        return releases.values().stream().filter(Release::active).sorted(NEWEST_FIRST).toList();
    }

    @Override
    public Optional<Release> active(String workflow) {
        return releases.values().stream()
                .filter(r -> r.active() && workflow != null && workflow.equals(r.workflow()))
                .min(NEWEST_FIRST);
    }
}
