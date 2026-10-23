package com.iaf.console.bff.jobs.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.jobs.domain.FailureAdvice;
import com.iaf.console.bff.jobs.domain.FailureFacts;
import com.iaf.console.bff.jobs.domain.FailureRules;
import com.iaf.console.bff.jobs.domain.JobExplanation;
import com.iaf.console.bff.jobs.domain.JobNotFailedException;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.platform.client.JobFilter;
import com.iaf.console.platform.client.PagedList;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.JobDetail;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.platform.model.JobStep;
import com.iaf.console.platform.model.JobSummary;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Why a job failed, and the fix to try (WP 9.2, hub decision 27). For a failed or rolled-back job,
 * or one waiting on a failed step: the cause from the rules over the platform's error classes, or
 * the copilot's reading when no rule knows the class; the failing step; the fix and the step to
 * run again from; whether the person may retry (the retry's own policy check); and how often the
 * same workflow failed with the same class in the account in the last 30 days, with the latest
 * such failure a console retry put right. Nothing is retried. Any other job is a 409
 * {@code job.not-failed}; another account's is a 404.
 */
public class FailureExplainer {

    static final Duration SIMILAR_WINDOW = Duration.ofDays(30);
    /** The most jobs of the workflow read to count similar failures. */
    static final int SIMILAR_JOBS = 2000;
    /** The most earlier failures whose retries are looked up for the last fix. */
    static final int FIXES_LOOKED_UP = 10;
    private static final int PAGE = 200;

    private static final Logger log = LoggerFactory.getLogger(FailureExplainer.class);

    private final Jobs jobs;
    private final JobRetries retries;
    private final Access access;
    private final PlatformClients clients;
    private final FailureAdvisor advisor;
    private final Clock clock;

    public FailureExplainer(Jobs jobs, JobRetries retries, Access access, PlatformClients clients, FailureAdvisor advisor, Clock clock) {
        this.jobs = jobs;
        this.retries = retries;
        this.access = access;
        this.clients = clients;
        this.advisor = advisor;
        this.clock = clock;
    }

    public Mono<JobExplanation> explain(Session session, String jobId) {
        return jobs.detail(session, jobId).flatMap(job -> {
            if (!explainable(job.status())) {
                return Mono.error(JobNotFailedException.nothingToExplain(job.id(), job.status() == null ? "unknown" : job.status().id()));
            }
            FailureFacts facts = factsOf(job);
            Mono<Advised> advised = FailureRules.forClass(facts).map(a -> Mono.just(new Advised(a, JobExplanation.RULES)))
                    .orElseGet(() -> advisor.advise(session, job, facts).map(a -> new Advised(a, JobExplanation.ASSISTANT))
                            .onErrorResume(e -> {
                                log.warn("the copilot could not explain job {} ({}): {}", job.id(), facts.errorClass(), e.toString());
                                return Mono.empty();
                            })
                            .defaultIfEmpty(new Advised(FailureRules.generic(facts), JobExplanation.RULES)));
            return Mono.zip(advised, retry(session, job), similar(session, job, facts)).map(t -> {
                FailureAdvice advice = t.getT1().advice();
                String fromStep = facts.paused() ? (facts.step() == null ? null : facts.step().id()) : JobRetries.rerunFrom(job).orElse(null);
                List<JobExplanation.Link> links = new ArrayList<>(advice.links());
                if (job.links() != null) {
                    job.links().forEach((name, url) -> {
                        if (url != null && !url.isBlank()) {
                            links.add(new JobExplanation.Link(titleOf(name), url));
                        }
                    });
                }
                return new JobExplanation(job.id(), facts.paused() ? JobExplanation.NEEDS_ATTENTION : JobExplanation.FAILED,
                        new JobExplanation.Cause(FailureFacts.UNKNOWN.equals(facts.errorClass()) ? null : facts.errorClass(), advice.title(), advice.detail()),
                        facts.step(), new JobExplanation.Fix(advice.fixSummary(), advice.input(), fromStep, advice.confidence()), t.getT2(), t.getT3(),
                        t.getT1().source(), links);
            });
        });
    }

    private record Advised(FailureAdvice advice, String source) {
    }

    static boolean explainable(JobStatus status) {
        return status == JobStatus.FAILED || status == JobStatus.ROLLED_BACK || status == JobStatus.NEEDS_ATTENTION;
    }

    /** The class and message of the failure, and the step: the one a paused job waits on, else the failed step, else the last that failed. */
    static FailureFacts factsOf(JobDetail job) {
        boolean paused = job.status() == JobStatus.NEEDS_ATTENTION;
        String stepId = paused && job.attention() != null && job.attention().step() != null ? job.attention().step() : job.failedStep();
        JobStep step = null;
        if (job.steps() != null) {
            for (JobStep s : job.steps()) {
                if (stepId != null ? stepId.equals(s.id()) : "failed".equals(s.status())) {
                    step = s;
                }
            }
        }
        String errorClass = job.errorClass();
        String message = null;
        if (paused && job.attention() != null && job.attention().error() != null) {
            errorClass = errorClass != null ? errorClass : job.attention().error().errorClass();
            message = job.attention().error().message();
        }
        if (step != null && step.error() != null) {
            errorClass = errorClass != null ? errorClass : step.error().errorClass();
            message = message != null ? message : step.error().message();
        }
        JobExplanation.FailedStep failedStep = step != null ? new JobExplanation.FailedStep(step.id(), step.name(), step.attempt(), message)
                : stepId != null ? new JobExplanation.FailedStep(stepId, null, null, message) : null;
        return new FailureFacts(errorClass, message, failedStep, paused);
    }

    /** Whether the person may retry: the job's state and the attention's options allow it, and the retry's own check passes. */
    private Mono<JobExplanation.Retry> retry(Session session, JobDetail job) {
        boolean paused = job.status() == JobStatus.NEEDS_ATTENTION;
        List<String> offered = paused && job.attention() != null && job.attention().options() != null ? job.attention().options() : List.of(JobRetries.VERB);
        if (!JobRetries.retryable(job) || !offered.contains(JobRetries.VERB)) {
            return Mono.just(new JobExplanation.Retry(false, null));
        }
        return retries.target(session, job).flatMap(t -> access.decide(session, List.of(new AccessCheck(t.actionName(), t.resource()))))
                .map(decided -> new JobExplanation.Retry(decided.get(0).allowed(), decided.get(0).allowed() ? null : decided.get(0).denial()));
    }

    /**
     * Jobs of the same workflow that failed with the same class in the account in the last 30 days
     * (this one included), and the latest of them a console retry ran through: the retry's job, who
     * asked for it, and when it ended.
     */
    private Mono<JobExplanation.Similar> similar(Session session, JobDetail job, FailureFacts facts) {
        if (job.workflow() == null || FailureFacts.UNKNOWN.equals(facts.errorClass())) {
            return Mono.just(new JobExplanation.Similar(1, null));
        }
        Instant since = clock.instant().minus(SIMILAR_WINDOW);
        JobFilter first = JobFilter.ofAccount(session.accountId()).withWorkflow(job.workflow())
                .withUpdatedAfter(since.getEpochSecond() * 1_000_000_000L + since.getNano()).withLimit(PAGE);
        return clients.forAccount(CurrentSession.scope(session)).flatMap(p -> p.listJobs(first)
                .expand(page -> page.hasNext() ? p.listJobs(first.withAfter(page.next())) : Mono.empty())
                .concatMapIterable(PagedList::items)
                .take(SIMILAR_JOBS)
                .filter(other -> sameFailure(other, session.accountId(), facts.errorClass(), since))
                .collectList()
                .flatMap(failures -> {
                    long count = failures.size() + (failures.stream().anyMatch(f -> f.id().equals(job.id())) ? 0 : 1);
                    List<JobSummary> earlier = failures.stream().filter(f -> !f.id().equals(job.id()))
                            .sorted(Comparator.comparing(JobSummary::startedAt, Comparator.nullsLast(Comparator.reverseOrder()))).limit(FIXES_LOOKED_UP)
                            .toList();
                    return lastFixed(p, session.accountId(), earlier).map(fixed -> new JobExplanation.Similar(count, fixed))
                            .defaultIfEmpty(new JobExplanation.Similar(count, null));
                }));
    }

    static boolean sameFailure(JobSummary other, String accountId, String errorClass, Instant since) {
        return errorClass.equals(other.errorClass()) && (other.status() == JobStatus.FAILED || other.status() == JobStatus.ROLLED_BACK)
                && other.context() != null && accountId.equals(other.context().accountId()) && other.startedAt() != null && !other.startedAt().isBefore(since);
    }

    /** The first of the failures, latest first, whose console retry succeeded. */
    private Mono<JobExplanation.LastFixed> lastFixed(PlatformPort p, String accountId, List<JobSummary> failures) {
        return Flux.fromIterable(failures)
                .concatMap(failure -> p.listJobs(JobFilter.ofAccount(accountId).withLabel(JobRetries.RETRY_OF_LABEL, failure.id()).withLimit(20))
                        .flatMapMany(page -> Flux.fromIterable(page.items()))
                        .filter(retry -> retry.status() == JobStatus.SUCCEEDED && retry.context() != null && accountId.equals(retry.context().accountId())
                                && retry.context().labels() != null && failure.id().equals(retry.context().labels().get(JobRetries.RETRY_OF_LABEL)))
                        .next())
                .next()
                .map(retry -> new JobExplanation.LastFixed(retry.id(), byOf(retry), retry.endedAt()));
    }

    private static String byOf(JobSummary job) {
        if (job.onBehalfOf() != null) {
            return job.onBehalfOf().name() != null && !job.onBehalfOf().name().isBlank() ? job.onBehalfOf().name() : job.onBehalfOf().id();
        }
        return job.requester();
    }

    /** A platform link's name for people: {@code trace} is Trace, {@code provider-logs} is Provider logs. */
    static String titleOf(String name) {
        String words = name.replace('-', ' ').replace('_', ' ');
        return words.isEmpty() ? name : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
