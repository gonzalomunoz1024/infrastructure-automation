package com.iaf.console.bff.assistant.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.bff.assistant.domain.Conversation;
import com.iaf.console.bff.assistant.domain.InputForm;
import com.iaf.console.bff.assistant.ports.outbound.AssistantPort;
import com.iaf.console.bff.assistant.ports.outbound.TurnLimiter;
import com.iaf.console.bff.jobs.application.FailureAdvisor;
import com.iaf.console.bff.jobs.domain.FailureAdvice;
import com.iaf.console.bff.jobs.domain.FailureFacts;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.platform.model.JobDetail;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The copilot's reading of a failure no rule explains, for the failure explainer (WP 9.2): the
 * assistant gets the job's facts as the person reads them (its workflow, the error class and
 * message, the failing step, its input without secret fields, the workflow's form without the
 * subject) and answers a cause and a fix, which the review reduces to the form. A reading takes
 * one of the person's turns. The explainer waits {@code failure-wait} for it and answers with its
 * rules when it is not there yet; the reading goes on, and the next explanation of the same job and
 * failure shows it (kept an hour per account, job, class and step). It reads; it never retries.
 */
public class CopilotFailureAdvisor implements FailureAdvisor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CopilotFailureAdvisor.class);

    static final Duration KEPT = Duration.ofHours(1);
    static final int READINGS = 10_000;

    /** Null when no assistant is configured: then there is never a reading. */
    private final AssistantPort port;
    private final Types types;
    private final TurnLimiter limiter;
    private final AnswerReview review;
    private final AssistantProperties properties;
    private final Clock clock;
    private final Cache<String, Mono<FailureAdvice>> readings = Caffeine.newBuilder().expireAfterWrite(KEPT).maximumSize(READINGS).build();
    private final Disposable.Composite running = Disposables.composite();

    public CopilotFailureAdvisor(AssistantPort port, Types types, TurnLimiter limiter, AnswerReview review, AssistantProperties properties, Clock clock) {
        this.port = port;
        this.types = types;
        this.limiter = limiter;
        this.review = review;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<FailureAdvice> advise(Session session, JobDetail job, FailureFacts facts) {
        if (port == null || job == null || job.id() == null) {
            return Mono.empty();
        }
        String key = String.join("|", session.accountId(), session.region(), job.id(), facts.errorClass(), facts.step() == null ? "" : facts.step().id());
        Mono<FailureAdvice> reading = readings.getIfPresent(key);
        if (reading == null) {
            Sinks.One<FailureAdvice> sink = Sinks.one();
            Mono<FailureAdvice> mine = sink.asMono();
            reading = readings.asMap().putIfAbsent(key, mine);
            if (reading == null) {
                reading = mine;
                read(key, session, job, facts, sink);
            }
        }
        return reading.timeout(properties.failureWait(), Mono.empty());
    }

    /** Reads the failure in the background, so a slow assistant still answers the next explanation. */
    private void read(String key, Session session, JobDetail job, FailureFacts facts, Sinks.One<FailureAdvice> sink) {
        Mono<FailureAdvice> advice = limiter.take(session.user().userId(), clock.instant(), properties.turnsPerMinute(), Assistant.WINDOW)
                .flatMap(allowance -> {
                    if (!allowance.taken()) {
                        log.debug("no copilot reading of job {}: {} is past their turns a minute", job.id(), session.user().userId());
                        readings.invalidate(key);
                        return Mono.empty();
                    }
                    return request(session, job, facts).flatMap(request -> port.answer(CurrentSession.scope(session), request)
                            .timeout(properties.turnTimeout())
                            .flatMap(answer -> Mono.justOrEmpty(answer.refused() ? Optional.<FailureAdvice>empty() : review.failure(request, answer))));
                });
        Disposable.Swap slot = Disposables.swap();
        running.add(slot);
        slot.update(advice.doFinally(signal -> running.remove(slot)).subscribe(
                sink::tryEmitValue,
                e -> {
                    log.info("no copilot reading of job {} ({}): {}", job.id(), facts.errorClass(), e.toString());
                    readings.invalidate(key);
                    sink.tryEmitEmpty();
                },
                sink::tryEmitEmpty));
    }

    /** The job as the assistant reads it, with the workflow's form when the catalog has it. */
    Mono<AssistantRequest> request(Session session, JobDetail job, FailureFacts facts) {
        Mono<InputForm> form = job.workflow() == null ? Mono.just(InputForm.of(null))
                : types.entry(session, job.workflow()).map(entry -> InputForm.of(entry.input()).without(entry.subjectInput()))
                        .onErrorResume(e -> Mono.just(InputForm.of(null)));
        return form.map(f -> new AssistantRequest(null, Conversation.newTurnId(), AssistantRequest.FAILURE, null, null, ContextGatherer.account(session),
                List.of(), List.of(), null,
                new AssistantRequest.Failure(job.id(), job.workflow(), job.title(), job.status() == null ? null : job.status().id(), facts.errorClass(),
                        facts.message(), facts.step() == null ? null : facts.step().id(), facts.step() == null ? null : facts.step().name(),
                        facts.step() == null ? null : facts.step().attempt(), facts.paused(), f.shown(job.input()), f.forAgent()),
                List.of()));
    }

    @Override
    public void close() {
        running.dispose();
    }
}
