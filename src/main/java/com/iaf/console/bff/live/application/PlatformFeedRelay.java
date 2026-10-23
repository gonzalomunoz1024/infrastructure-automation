package com.iaf.console.bff.live.application;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.iaf.console.bff.live.domain.LiveHub;
import com.iaf.console.bff.live.domain.PlatformEvents;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.Regions;
import com.iaf.console.bff.types.application.Types;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Follows the platform's feed with the console-internal client and turns it into live events,
 * refreshing the descriptors on content changes and the regions on region changes. Reconnects on
 * its own; starts from the configured sequence (0 replays what the platform retains).
 */
public class PlatformFeedRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PlatformFeedRelay.class);

    private final PlatformClients clients;
    private final LiveHub hub;
    private final Types types;
    private final Regions regions;
    private final LiveProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong relayed = new AtomicLong();
    private volatile Disposable subscription;

    public PlatformFeedRelay(PlatformClients clients, LiveHub hub, Types types, Regions regions, LiveProperties properties) {
        this.clients = clients;
        this.hub = hub;
        this.types = types;
        this.regions = regions;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        subscription = Mono.defer(clients::internal)
                .flatMapMany(platform -> platform.followEvents(properties.relay().fromSeq(), null))
                .doOnSubscribe(s -> log.info("following the platform feed from seq {}", properties.relay().fromSeq()))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(signal -> log.warn("platform feed interrupted, reconnecting: {}", signal.failure().getMessage())))
                .subscribe(event -> {
                    if (PlatformEvents.changesContent(event)) {
                        types.invalidate();
                    }
                    if (PlatformEvents.changesRegions(event)) {
                        regions.refresh();
                    }
                    PlatformEvents.toLive(event).ifPresent(mapped -> {
                        hub.publish(mapped.accountId(), mapped.event());
                        relayed.incrementAndGet();
                    });
                }, error -> log.error("platform feed relay stopped", error));
    }

    @Override
    public void stop() {
        running.set(false);
        Disposable s = subscription;
        if (s != null) {
            s.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    public long relayed() {
        return relayed.get();
    }
}
