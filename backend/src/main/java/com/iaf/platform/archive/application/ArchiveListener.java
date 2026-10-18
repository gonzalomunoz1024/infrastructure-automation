package com.iaf.platform.archive.application;

import java.time.Duration;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.iaf.platform.archive.domain.JobArchive;
import com.iaf.platform.archive.ports.inbound.ArchiveCommands;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Archives a job each time it ends ({@code job.finished}, {@code job.cancelled}), and at start every
 * recent ended job whose archive is missing. It registers after the job projection it reads
 * through (the runs slice's query handler needs the projection first), so the job has ended there
 * by the time it is read. Archiving is asynchronous and one job at a time, in the order the jobs
 * ended, so two archives of a job that ended twice never overlap. It never fails the publisher or
 * the job: a failure is logged, and the next start archives the jobs that have no archive.
 */
@Slf4j
@Component
public class ArchiveListener {

    private final EventBus events;
    private final ArchiveCommands archive;
    private final Sinks.Many<String> ended = Sinks.many().unicast().onBackpressureBuffer();
    private Disposable archiving;
    private Disposable sweep;

    public ArchiveListener(EventBus events, ArchiveCommands archive) {
        this.events = events;
        this.archive = archive;
    }

    @PostConstruct
    public void subscribe() {
        archiving = ended.asFlux()
                .concatMap(id -> Mono.defer(() -> archive.archive(id))
                        .doOnNext(outcome -> log.debug("job {}: {}", id, outcome.json()))
                        .onErrorResume(e -> {
                            log.warn("job {} could not be archived (the next start archives the jobs that have no archive): {}", id, e.toString());
                            return Mono.empty();
                        }))
                .subscribe();
        events.register(this::on);
    }

    void on(DomainEvent event) {
        JobArchive.endedJob(event).ifPresent(id -> {
            try {
                ended.emitNext(id, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
            } catch (Sinks.EmissionException e) {
                log.debug("job {} is not queued for the archive ({}), the next start archives it", id, e.getReason());
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void archiveMissing() {
        sweep = archive.archiveMissing().subscribe(
                n -> {
                    if (n > 0) {
                        log.info("archived {} ended jobs whose archive was missing", n);
                    }
                },
                e -> log.warn("the archive sweep stopped, the next start tries again: {}", e.toString()));
    }

    @PreDestroy
    public void stop() {
        if (sweep != null) {
            sweep.dispose();
        }
        if (archiving != null) {
            archiving.dispose();
        }
    }
}
