package com.iaf.platform.releases.application;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.domain.ReleaseStatus;
import com.iaf.platform.releases.ports.inbound.ReleaseQueries;
import com.iaf.platform.releases.ports.outbound.ReleaseStore;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read side of releases, including the version router the catalog asks before starting a job.
 *
 * <p>Routing is deterministic, not random: each release keeps a counter {@code n} of the jobs it
 * routed, and job {@code n} runs the candidate when {@code (n * stagePercent) mod 100 < stagePercent}.
 * That spreads exactly {@code stagePercent}% of any 100 consecutive jobs over the candidate, evenly
 * (every 10th job at 10%, every other job at 50%), and the first job after a canary starts is a
 * candidate job. Jobs already running keep the version they started with.
 */
@Service
@RequiredArgsConstructor
public class ReleaseQueryHandler implements ReleaseQueries {

    private final ReleaseStore store;
    private final Map<String, AtomicLong> routed = new ConcurrentHashMap<>();

    @Override
    public Mono<String> route(String workflow, String publishedVersion) {
        return Mono.fromSupplier(() -> {
            Optional<Release> active = store.active(workflow);
            if (active.isEmpty() || active.get().from() == null) {
                return publishedVersion;
            }
            Release release = active.get();
            if (release.status() == ReleaseStatus.PAUSED) {
                return release.from();
            }
            long n = routed.computeIfAbsent(release.id(), k -> new AtomicLong()).getAndIncrement();
            return candidate(n, release.stagePercent()) ? release.to() : release.from();
        });
    }

    /** Pure routing rule: does job number {@code n} (0-based) of a release run the candidate. */
    static boolean candidate(long n, int stagePercent) {
        if (stagePercent <= 0) {
            return false;
        }
        if (stagePercent >= 100) {
            return true;
        }
        return (n * stagePercent) % 100 < stagePercent;
    }

    public Flux<Release> list(String workflow) {
        return Flux.defer(() -> Flux.fromIterable(store.list(workflow)));
    }

    public Mono<Release> get(String id) {
        return Mono.defer(() -> Mono.justOrEmpty(store.find(id)))
                .switchIfEmpty(Mono.error(() -> new NotFoundException("release", id)));
    }
}
