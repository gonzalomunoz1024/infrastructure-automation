package com.iaf.console.state.inventory.adapters.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.FeedEvent;
import com.iaf.console.state.inventory.application.FeedSource;
import com.iaf.console.state.inventory.application.PlatformEventHandler;
import com.iaf.console.state.inventory.application.RegistryImporter;
import com.iaf.console.state.inventory.ports.outbound.FeedCheckpointPort;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Follows the platform's event feed over HTTP ({@code GET /api/v1/events/stream}, the client
 * reconnects for ever) from the last checkpoint. With no checkpoint at all (a first boot, or a
 * fresh database) it first walks the platform's resource snapshot and starts the feed at the
 * sequence the snapshot was current to, so nothing is missed and nothing is replayed twice.
 */
public final class HttpFeedFollower implements SmartLifecycle, FeedSource {

    /** The checkpoint's name. */
    public static final String FEED = "platform-feed";

    private static final Logger log = LoggerFactory.getLogger(HttpFeedFollower.class);

    private final PlatformPort platform;
    private final PlatformEventHandler handler;
    private final RegistryImporter importer;
    private final FeedCheckpointPort checkpoints;
    private final List<String> types;
    private final boolean rebuildOnEmpty;
    private final AtomicLong lastSeq = new AtomicLong();
    private final AtomicLong handled = new AtomicLong();
    private final AtomicReference<Instant> lastEventAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private volatile Disposable subscription;

    public HttpFeedFollower(PlatformPort platform, PlatformEventHandler handler, RegistryImporter importer, FeedCheckpointPort checkpoints,
            List<String> types, boolean rebuildOnEmpty) {
        this.platform = platform;
        this.handler = handler;
        this.importer = importer;
        this.checkpoints = checkpoints;
        this.types = List.copyOf(types);
        this.rebuildOnEmpty = rebuildOnEmpty;
    }

    @Override
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        subscription = startingPoint()
                .flatMapMany(from -> {
                    lastSeq.set(from);
                    log.info("following the platform feed from sequence {} for {}", from, types);
                    return platform.followEvents(from, types);
                })
                .concatMap(this::apply)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> {
                            lastError.set(String.valueOf(s.failure()));
                            log.warn("the feed follower restarts after: {}", s.failure().toString());
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private Mono<Long> startingPoint() {
        return checkpoints.find(FEED).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(found -> {
            if (found.isPresent()) {
                return Mono.just(found.get());
            }
            if (!rebuildOnEmpty) {
                return Mono.just(0L);
            }
            log.info("no feed checkpoint: rebuilding from the platform's snapshot first");
            return importer.rebuild().map(RegistryImporter.Report::highWaterSeq).flatMap(hw -> checkpoints.save(FEED, hw).thenReturn(hw));
        });
    }

    private Mono<Void> apply(FeedEvent event) {
        return handler.handle(event)
                .retryWhen(Retry.backoff(5, Duration.ofMillis(500)).maxBackoff(Duration.ofSeconds(5)))
                .onErrorResume(e -> {
                    lastError.set(e.toString());
                    log.error("platform event {} ({}) could not be applied after retries and is skipped: {}", event.key(), event.type(), e.toString());
                    return Mono.empty();
                })
                .then(checkpoints.save(FEED, event.seq()))
                .doOnSuccess(v -> {
                    lastSeq.set(event.seq());
                    lastEventAt.set(event.at());
                    handled.incrementAndGet();
                });
    }

    /** Walks the snapshot again, on demand. */
    public Mono<RegistryImporter.Report> rebuild() {
        return importer.rebuild();
    }

    @Override
    public synchronized void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
    }

    @Override
    public boolean isRunning() {
        return subscription != null;
    }

    @Override
    public FeedStatus status() {
        return new FeedStatus("http", isRunning(), lastSeq.get(), lastEventAt.get(), lastError.get(), handled.get());
    }
}
