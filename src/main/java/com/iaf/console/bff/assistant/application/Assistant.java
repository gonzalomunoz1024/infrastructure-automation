package com.iaf.console.bff.assistant.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.assistant.domain.AssistantAnswer;
import com.iaf.console.bff.assistant.domain.AssistantAsk;
import com.iaf.console.bff.assistant.domain.AssistantConversation;
import com.iaf.console.bff.assistant.domain.AssistantException;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.bff.assistant.domain.AssistantTurn;
import com.iaf.console.bff.assistant.domain.Conversation;
import com.iaf.console.bff.assistant.domain.PlainText;
import com.iaf.console.bff.assistant.domain.TurnAccepted;
import com.iaf.console.bff.assistant.ports.outbound.AssistantPort;
import com.iaf.console.bff.assistant.ports.outbound.ConversationStore;
import com.iaf.console.bff.assistant.ports.outbound.TurnLimiter;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.errors.TooManyRequestsException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;
import com.iaf.console.wfrn.WfrnException;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

/**
 * The console copilot (WP 9.1, hub decision 27). A turn stores the person's question and an
 * assistant turn that is {@code thinking}, answers 202, and goes on in the background: the BFF
 * gathers what the person may read, tries the {@link FastPath}, asks the {@code AssistantPort},
 * reviews the answer against the person's own permissions and stores it {@code answered}, or
 * {@code failed} with {@code assistant.unavailable}, {@code assistant.timeout} or
 * {@code assistant.refused}. The console polls the conversation. Nothing here acts: no command,
 * no write to the platform or the state service, only reads with the person's session.
 */
public class Assistant implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Assistant.class);

    /** The window a person's turns are counted over. */
    static final Duration WINDOW = Duration.ofMinutes(1);
    /** How long past the turn timeout a turn still thinking reads as failed: the instance that ran it stopped. */
    static final Duration GRACE = Duration.ofSeconds(30);
    /** How often a write that met a newer version of the conversation is tried again. */
    static final int ATTEMPTS = 5;
    static final int MAX_MESSAGE_SHOWN = 300;

    private final AssistantPort port;
    private final FastPath fastPath;
    private final ContextGatherer context;
    private final AnswerReview review;
    private final ConversationStore store;
    private final TurnLimiter limiter;
    private final Resources resources;
    private final AssistantProperties properties;
    private final Clock clock;
    private final Disposable.Composite running = Disposables.composite();

    public Assistant(AssistantPort port, FastPath fastPath, ContextGatherer context, AnswerReview review, ConversationStore store, TurnLimiter limiter,
            Resources resources, AssistantProperties properties, Clock clock) {
        this.port = port;
        this.fastPath = fastPath;
        this.context = context;
        this.review = review;
        this.store = store;
        this.limiter = limiter;
        this.resources = resources;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Starts a turn: 400 for a body with problems; 404 for a wfrn of another account or region (or
     * no wfrn at all), for a conversation that is not the person's in this account and region or
     * has expired, and for {@code explain} of a resource that is not there (403 with its Denial
     * for one the person may not read, as its page answers); 429 with {@code Retry-After} past
     * the person's turns a minute. The answer is the conversation and the assistant turn that
     * will carry the answer.
     */
    public Mono<TurnAccepted> ask(Session session, AssistantAsk body) {
        AssistantAsk sent = body == null ? AssistantAsk.empty() : body;
        List<Problem> problems = sent.problems();
        if (!problems.isEmpty()) {
            return Mono.error(AssistantAsk.invalid(problems));
        }
        AssistantAsk ask = sent.sanitized();
        Wfrn subject;
        try {
            subject = subjectOf(session, ask.wfrn());
        } catch (NotFoundException e) {
            return Mono.error(e);
        }
        AccountScope scope = CurrentSession.scope(session);
        return Mono.deferContextual(request -> {
            Instant now = clock.instant();
            String correlationId = Correlation.id(request).orElse(null);
            String traceparent = Correlation.traceparent(request).orElse(null);
            return conversationFor(session, scope, ask, now).flatMap(conversation -> {
                if (conversation.full()) {
                    return Mono.error(full());
                }
                return limiter.take(session.user().userId(), now, properties.turnsPerMinute(), WINDOW).flatMap(allowance -> {
                    if (!allowance.taken()) {
                        return Mono.error(new TooManyRequestsException("at most " + properties.turnsPerMinute() + " copilot turns a minute",
                                allowance.retryAfterSeconds()));
                    }
                    Mono<Optional<Resources.Detail>> read = ask.explains()
                            ? resources.get(session, subject.type(), subject.format(), null).map(Optional::of)
                            : Mono.just(Optional.empty());
                    return read.flatMap(detail -> {
                        String personTurn = Conversation.newTurnId();
                        String answerTurn = Conversation.newTurnId();
                        AssistantTurn person = AssistantTurn.person(personTurn, now, personText(ask, detail.orElse(null)));
                        AssistantTurn thinking = AssistantTurn.thinking(answerTurn, now);
                        return add(scope, conversation, person, thinking, 1).map(saved -> {
                            start(session, scope, saved, ask, personTurn, answerTurn, detail.orElse(null), correlationId, traceparent);
                            return new TurnAccepted(saved.conversationId(), answerTurn);
                        });
                    });
                });
            });
        });
    }

    /** The person's conversation as the console reads it; 404 for another person's, another account's or region's, or one that expired. */
    public Mono<AssistantConversation> conversation(Session session, String conversationId) {
        Instant now = clock.instant();
        if (conversationId == null || !conversationId.startsWith(Conversation.ID_PREFIX) || conversationId.length() > 64) {
            return Mono.error(NotFoundException.of("conversation", String.valueOf(conversationId)));
        }
        return store.find(CurrentSession.scope(session), conversationId)
                .filter(c -> c.of(session.user().userId(), session.accountId(), session.region()) && !c.expired(now))
                .switchIfEmpty(Mono.error(NotFoundException.of("conversation", conversationId)))
                .map(c -> c.view(now, properties.turnTimeout().plus(GRACE)));
    }

    /** Stops the turns under way in this instance; their conversations read them as timed out once the grace has passed. */
    @Override
    public void close() {
        running.dispose();
    }

    /** How many turns this instance is answering now. */
    public int underWay() {
        return running.size();
    }

    // ---- a turn

    /** The resource the ask is about; 404 for a wfrn that is none, or of another account or region. */
    static Wfrn subjectOf(Session session, String wfrn) {
        if (wfrn == null) {
            return null;
        }
        Wfrn name;
        try {
            name = Wfrn.parse(wfrn);
        } catch (WfrnException e) {
            throw NotFoundException.of("resource", wfrn);
        }
        if (!name.in(session.accountId(), session.region())) {
            throw NotFoundException.of("resource", wfrn);
        }
        return name;
    }

    private Mono<Conversation> conversationFor(Session session, AccountScope scope, AssistantAsk ask, Instant now) {
        if (ask.conversationId() == null) {
            return Mono.just(Conversation.start(session.user().userId(), session.accountId(), session.region(), now, properties.conversationTtl()));
        }
        return store.find(scope, ask.conversationId())
                .filter(c -> c.of(session.user().userId(), session.accountId(), session.region()) && !c.expired(now))
                .switchIfEmpty(Mono.error(NotFoundException.of("conversation", ask.conversationId())));
    }

    /** The question as the conversation shows it: the message, or for {@code explain} without one, "Explain" and the resource's name. */
    static String personText(AssistantAsk ask, Resources.Detail detail) {
        if (ask.message() != null) {
            return ask.message();
        }
        String name = detail != null && detail.view() != null && detail.view().name() != null ? detail.view().name() : ask.wfrn();
        return "Explain " + name;
    }

    /** Adds the question and its thinking turn, again on the newest version when another write came first. */
    private Mono<Conversation> add(AccountScope scope, Conversation conversation, AssistantTurn person, AssistantTurn thinking, int attempt) {
        Conversation next = conversation.asked(person, thinking);
        if (conversation.version() == 0) {
            return store.insert(scope, next);
        }
        return store.replace(scope, next, conversation.version()).switchIfEmpty(Mono.defer(() -> {
            if (attempt >= ATTEMPTS) {
                return Mono.error(new IllegalStateException("the conversation " + conversation.conversationId() + " kept changing"));
            }
            return store.find(scope, conversation.conversationId())
                    .filter(c -> !c.expired(clock.instant()))
                    .switchIfEmpty(Mono.error(NotFoundException.of("conversation", conversation.conversationId())))
                    .flatMap(current -> current.full() ? Mono.error(full()) : add(scope, current, person, thinking, attempt + 1));
        }));
    }

    /** Runs the turn in the background (never on the request's thread, so the 202 does not wait for it), in the request's correlation, and stores how it ended. */
    private void start(Session session, AccountScope scope, Conversation conversation, AssistantAsk ask, String personTurn, String turnId,
            Resources.Detail detail, String correlationId, String traceparent) {
        Mono<AssistantTurn> outcome = context.gather(session, ask, conversation, personTurn, turnId, detail)
                .onErrorMap(e -> !(e instanceof AssistantException),
                        e -> new AssistantException(AssistantException.UNAVAILABLE, "the console could not read what the question needs", e))
                .flatMap(request -> answer(session, scope, request).flatMap(answer -> answered(session, request, answer, turnId)))
                .timeout(properties.turnTimeout())
                .onErrorResume(e -> Mono.just(failed(turnId, e)));
        Disposable.Swap slot = Disposables.swap();
        running.add(slot);
        slot.update(outcome
                .flatMap(turn -> settle(scope, conversation.conversationId(), turn, 1))
                .subscribeOn(Schedulers.parallel())
                .contextWrite(ctx -> withRequest(ctx, session, correlationId, traceparent))
                .doFinally(signal -> running.remove(slot))
                .subscribe(
                        settled -> log.debug("copilot turn {} of {} ended", turnId, conversation.conversationId()),
                        e -> log.warn("the copilot could not store the end of turn {} of {}: {}", turnId, conversation.conversationId(), e.toString())));
    }

    /** The fast path's answer, else the assistant's. */
    private Mono<AssistantAnswer> answer(Session session, AccountScope scope, AssistantRequest request) {
        return Mono.defer(() -> fastPath.answer(session, request))
                .onErrorResume(e -> {
                    log.warn("the copilot's fast path failed on turn {}, the assistant answers: {}", request.turnId(), e.toString());
                    return Mono.empty();
                })
                .switchIfEmpty(Mono.defer(() -> port.answer(scope, request)));
    }

    /** The reviewed answer as an answered turn; a refusal, or nothing left to show, is {@code assistant.refused}. */
    private Mono<AssistantTurn> answered(Session session, AssistantRequest request, AssistantAnswer answer, String turnId) {
        if (answer.refused()) {
            String reason = PlainText.line(answer.reason(), MAX_MESSAGE_SHOWN);
            return Mono.error(AssistantException.refused(reason == null ? "the assistant would not answer that" : reason));
        }
        return review.review(session, request, answer).flatMap(reviewed -> reviewed.isEmpty()
                ? Mono.error(AssistantException.refused("the assistant gave no answer the console can show"))
                : Mono.just(AssistantTurn.answered(turnId, clock.instant(), reviewed.text(), reviewed.proposals(), reviewed.explanation())));
    }

    /** How a turn that did not answer ends: its code, and a sentence the person may read. */
    AssistantTurn failed(String turnId, Throwable e) {
        AssistantException cause;
        if (e instanceof AssistantException a) {
            cause = a;
        } else if (e instanceof TimeoutException) {
            cause = AssistantException.notWithin(properties.turnTimeout());
        } else {
            cause = new AssistantException(AssistantException.UNAVAILABLE, "the assistant could not be reached", e);
        }
        if (AssistantException.UNAVAILABLE.equals(cause.code())) {
            log.warn("copilot turn {} failed, {}: {}", turnId, cause.code(), cause.getCause() != null ? cause.getCause().toString() : cause.getMessage());
        } else {
            log.info("copilot turn {} failed, {}: {}", turnId, cause.code(), cause.getMessage());
        }
        String message = PlainText.line(cause.getMessage(), MAX_MESSAGE_SHOWN);
        return AssistantTurn.failed(turnId, clock.instant(), cause.code(), message == null ? cause.code() : message);
    }

    /** Puts a turn's end in place of its thinking turn, again on the newest version when another write came first. */
    private Mono<Conversation> settle(AccountScope scope, String conversationId, AssistantTurn turn, int attempt) {
        return store.find(scope, conversationId).flatMap(current -> {
            Conversation next = current.settled(turn);
            if (next == current) {
                return Mono.just(current);
            }
            return store.replace(scope, next, current.version()).switchIfEmpty(Mono.defer(() -> attempt >= ATTEMPTS
                    ? Mono.error(new IllegalStateException("the conversation " + conversationId + " kept changing"))
                    : settle(scope, conversationId, turn, attempt + 1)));
        });
    }

    private static Context withRequest(Context ctx, Session session, String correlationId, String traceparent) {
        Context c = ctx.put(CurrentSession.CONTEXT_KEY, session);
        if (correlationId != null) {
            c = c.put(Correlation.KEY, correlationId);
        }
        if (traceparent != null) {
            c = c.put(Correlation.TRACEPARENT_KEY, traceparent);
        }
        return c;
    }

    private static InvalidInputException full() {
        String sentence = "the conversation holds " + (Conversation.MAX_TURNS / 2) + " questions, which is all it may: start a new one";
        return new InvalidInputException(sentence, List.of(Problem.error("conversation.full", "/conversationId", sentence)));
    }
}
