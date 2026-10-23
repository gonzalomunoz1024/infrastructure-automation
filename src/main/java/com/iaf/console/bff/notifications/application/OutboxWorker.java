package com.iaf.console.bff.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.iaf.console.bff.notifications.domain.OutboxEntry;
import com.iaf.console.bff.notifications.ports.outbound.NotificationChannel;
import com.iaf.console.bff.notifications.ports.outbound.NotificationOutbox;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Works the outbox (decision 32.2): what is due goes out through its channel, a failed try is due
 * again on the retry schedule, and the fifth failure marks it failed. It looks every
 * {@code outbox.poll}, and at once when this replica queued something; each replica claims what it
 * sends, so a message goes out once however many replicas work the outbox.
 */
public class OutboxWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final NotificationOutbox outbox;
    private final Map<String, NotificationChannel> channels;
    private final NotificationsProperties.Outbox properties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean again = new AtomicBoolean();
    private volatile Disposable ticks;

    public OutboxWorker(NotificationOutbox outbox, Map<String, NotificationChannel> channels, NotificationsProperties.Outbox properties, Clock clock) {
        this.outbox = outbox;
        this.channels = Map.copyOf(channels);
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ticks = Flux.interval(properties.poll(), properties.poll(), Schedulers.parallel()).subscribe(tick -> nudge());
    }

    @Override
    public void stop() {
        running.set(false);
        Disposable t = ticks;
        if (t != null) {
            t.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** Looks at the outbox soon: at once when no look is running, else right after the one that is. */
    public void nudge() {
        if (!running.get()) {
            return;
        }
        again.set(true);
        if (draining.compareAndSet(false, true)) {
            Mono.defer(() -> {
                again.set(false);
                return drain();
            }).repeat(again::get).then().doFinally(signal -> {
                draining.set(false);
                if (again.get()) {
                    nudge();
                }
            }).subscribe(done -> { }, error -> log.warn("working the notification outbox failed: {}", error.toString()));
        }
    }

    /** One look: what is due, each tried once, one after the other. */
    Mono<Void> drain() {
        Instant now = clock.instant();
        return outbox.claimDue(now, properties.lease(), properties.batch()).concatMap(this::attempt).then();
    }

    private Mono<Void> attempt(OutboxEntry entry) {
        NotificationChannel channel = channels.get(entry.channel());
        if (channel == null) {
            return outbox.save(entry.failedTry("no " + entry.channel() + " channel is set up here", clock.instant()));
        }
        return channel.deliver(entry.recipient(), entry.notification())
                .timeout(properties.timeout())
                .then(Mono.defer(() -> outbox.save(entry.sent(clock.instant()))))
                .onErrorResume(e -> {
                    OutboxEntry next = entry.failedTry(e.toString(), clock.instant());
                    if (OutboxEntry.FAILED.equals(next.status())) {
                        log.warn("{} to {} failed for good after {} tries: {}", entry.channel(), entry.recipient().userId(), next.attempts(), e.toString());
                    } else {
                        log.info("{} to {} failed, tried again at {}: {}", entry.channel(), entry.recipient().userId(), next.nextAttemptAt(), e.toString());
                    }
                    return outbox.save(next);
                });
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 200;
    }
}
