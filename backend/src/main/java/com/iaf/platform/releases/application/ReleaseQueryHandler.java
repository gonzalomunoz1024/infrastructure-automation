package com.iaf.platform.releases.application;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.domain.ReleaseStatus;
import com.iaf.platform.releases.ports.inbound.ReleaseQueries;
import com.iaf.platform.releases.ports.outbound.ReleaseStore;

import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read side of releases, including the version router the catalog asks before starting a job.
 *
 * <p>Routing is deterministic, not random: each release keeps a counter {@code n} of the jobs it
 * routed, and job {@code n} runs the candidate when {@code (n * stagePercent) mod 100 < stagePercent}.
 * That spreads exactly {@code stagePercent}% of any 100 consecutive jobs over the candidate, evenly
 * (every 10th job at 10%, every other job at 50%), and the first job after a canary starts is a
 * candidate job. Jobs already running keep the version they started with. The counter lives in
 * this instance, one per workflow, and goes when the release ends: at once on its
 * {@code release.promoted} or {@code release.rolled-back} event, and otherwise (a release ended on
 * another instance) the next time the workflow is routed, when no release or another one runs it.
 *
 * <p>Provider releases route calls, not jobs: sandbox use (dev, dry runs) gets the candidate at
 * once, and a job is in the stage's share by a stable hash of its id ({@link #candidateJob}), so all
 * the calls of one job use one version.
 */
@Primary
@Service
public class ReleaseQueryHandler implements ReleaseQueries {

    /** The environment where a provider's candidate is used at once: sandbox workflows run there. */
    public static final String SANDBOX = "dev";
    static final String KIND_WORKFLOW = "workflow";
    static final String KIND_PROVIDER = "provider";
    static final Set<String> KINDS = Set.of(KIND_WORKFLOW, KIND_PROVIDER, "all");
    /** The events of a release that ended, either way. */
    static final Set<String> ENDED = Set.of("release.promoted", "release.rolled-back");

    private final ReleaseStore store;
    private final EventBus events;
    /** The jobs routed by the release running each workflow, by workflow. */
    private final Map<String, Routed> routed = new ConcurrentHashMap<>();

    private record Routed(String releaseId, AtomicLong jobs) {
    }

    /** Without the event bus: an ended release's counter goes when its workflow is next routed. */
    public ReleaseQueryHandler(ReleaseStore store) {
        this(store, null);
    }

    @Autowired
    public ReleaseQueryHandler(ReleaseStore store, EventBus events) {
        this.store = store;
        this.events = events;
    }

    @PostConstruct
    public void subscribe() {
        if (events != null) {
            events.register(this::on);
        }
    }

    /** A release that ended routes no more jobs: its counter goes. */
    void on(DomainEvent event) {
        if (ENDED.contains(event.type()) && event.aggregateId() != null) {
            routed.values().removeIf(r -> r.releaseId().equals(event.aggregateId()));
        }
    }

    @Override
    public Mono<String> route(String workflow, String publishedVersion) {
        return Mono.fromSupplier(() -> {
            Optional<Release> active = store.active(workflow);
            if (active.isEmpty() || active.get().from() == null) {
                routed.remove(workflow);
                return publishedVersion;
            }
            Release release = active.get();
            if (release.status() == ReleaseStatus.PAUSED) {
                return release.from();
            }
            Routed counter = routed.compute(workflow,
                    (k, old) -> old != null && old.releaseId().equals(release.id()) ? old : new Routed(release.id(), new AtomicLong()));
            long n = counter.jobs().getAndIncrement();
            return candidate(n, release.stagePercent()) ? release.to() : release.from();
        });
    }

    /** The releases this instance keeps a job counter for. */
    Set<String> counting() {
        return routed.values().stream().map(Routed::releaseId).collect(Collectors.toSet());
    }

    @Override
    public Mono<String> routeProvider(String provider, String jobId, String environment, boolean dryRun) {
        return Mono.defer(() -> Mono.justOrEmpty(store.activeProvider(provider).map(release -> {
            if (release.from() == null || dryRun || SANDBOX.equals(environment)) {
                return release.to();
            }
            if (release.status() == ReleaseStatus.PAUSED) {
                return release.from();
            }
            return candidateJob(release.id(), jobId, release.stagePercent()) ? release.to() : release.from();
        })));
    }

    @Override
    public Mono<String> providerCandidate(String provider) {
        return Mono.defer(() -> Mono.justOrEmpty(store.activeProvider(provider).map(Release::to)));
    }

    /**
     * Pure routing rule of a provider release: a job runs the candidate when its bucket (a CRC-32 of
     * the release and the job, 0 to 99) is below the stage's percentage. Stateless, so every call of
     * a job lands on the same version on any instance; a call outside a job uses the current version.
     */
    public static boolean candidateJob(String releaseId, String jobId, int stagePercent) {
        if (jobId == null || jobId.isBlank() || stagePercent <= 0) {
            return false;
        }
        if (stagePercent >= 100) {
            return true;
        }
        CRC32 crc = new CRC32();
        crc.update((releaseId + "/" + jobId).getBytes(StandardCharsets.UTF_8));
        return crc.getValue() % 100 < stagePercent;
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

    /** Workflow releases (of one workflow when given). */
    public Flux<Release> list(String workflow) {
        return list(workflow, null, null);
    }

    /**
     * Newest first: the releases of {@code provider} when given, else of {@code workflow} when given,
     * else every release of {@code kind} ({@code workflow}, the default, {@code provider} or {@code all}).
     */
    public Flux<Release> list(String workflow, String provider, String kind) {
        return Flux.defer(() -> {
            if (provider != null && !provider.isBlank()) {
                return Flux.fromIterable(store.listProvider(provider.trim()));
            }
            if (workflow != null && !workflow.isBlank()) {
                return Flux.fromIterable(store.list(workflow.trim())).filter(r -> r.provider() == null);
            }
            String k = kind == null || kind.isBlank() ? KIND_WORKFLOW : kind.trim();
            if (!KINDS.contains(k)) {
                throw new IllegalArgumentException("kind is workflow, provider or all");
            }
            return Flux.fromIterable(store.list(null)).filter(r -> switch (k) {
                case KIND_PROVIDER -> r.provider() != null;
                case KIND_WORKFLOW -> r.provider() == null;
                default -> true;
            });
        });
    }

    public Mono<Release> get(String id) {
        return Mono.defer(() -> Mono.justOrEmpty(store.find(id)))
                .switchIfEmpty(Mono.error(() -> new NotFoundException("release", id)));
    }
}
