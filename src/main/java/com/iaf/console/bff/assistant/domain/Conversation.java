package com.iaf.console.bff.assistant.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.iaf.console.wfrn.Ulid;

/**
 * A conversation as it is stored: whose it is (the person, in the account and region it started
 * in), when it started and when it is forgotten (a fixed time after the start), its turns oldest
 * first, and a version that every change moves on, so two writers never lose each other's turn.
 */
public record Conversation(
        String conversationId,
        String userId,
        String accountId,
        String region,
        Instant startedAt,
        Instant expiresAt,
        long version,
        List<AssistantTurn> turns) {

    public static final String ID_PREFIX = "conv_";
    /** The most turns one conversation holds, the person's and the assistant's together: 50 questions. */
    public static final int MAX_TURNS = 100;
    static final String STALE = "the assistant did not answer in time";

    public Conversation {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    /** A new conversation of a person in an account and region, kept for {@code ttl} from now; version 0 until its first turn. */
    public static Conversation start(String userId, String accountId, String region, Instant now, Duration ttl) {
        return new Conversation(ID_PREFIX + Ulid.next().toLowerCase(Locale.ROOT), userId, accountId, region, now, now.plus(ttl), 0, List.of());
    }

    /** A new turn id. */
    public static String newTurnId() {
        return AssistantTurn.ID_PREFIX + Ulid.next().toLowerCase(Locale.ROOT);
    }

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Whether the conversation is this person's, in this account and region. */
    public boolean of(String otherUserId, String otherAccountId, String otherRegion) {
        return userId.equals(otherUserId) && accountId.equals(otherAccountId) && region.equals(otherRegion);
    }

    /** No room for another question and its answer. */
    public boolean full() {
        return turns.size() + 2 > MAX_TURNS;
    }

    /** The conversation with a question and the assistant's turn that will answer it. */
    public Conversation asked(AssistantTurn person, AssistantTurn thinking) {
        List<AssistantTurn> next = new ArrayList<>(turns);
        next.add(person);
        next.add(thinking);
        return new Conversation(conversationId, userId, accountId, region, startedAt, expiresAt, version + 1, next);
    }

    /** The conversation with an assistant turn's answer in place of its {@code thinking} turn; unchanged when that turn already ended. */
    public Conversation settled(AssistantTurn answer) {
        List<AssistantTurn> next = new ArrayList<>(turns.size());
        boolean changed = false;
        for (AssistantTurn turn : turns) {
            if (turn.turnId().equals(answer.turnId()) && turn.isThinking()) {
                next.add(answer);
                changed = true;
            } else {
                next.add(turn);
            }
        }
        return changed ? new Conversation(conversationId, userId, accountId, region, startedAt, expiresAt, version + 1, next) : this;
    }

    public Optional<AssistantTurn> turn(String turnId) {
        return turns.stream().filter(t -> t.turnId().equals(turnId)).findFirst();
    }

    /** The last {@code limit} turns before the one of {@code turnId} (every turn when it is not here). */
    public List<AssistantTurn> before(String turnId, int limit) {
        int end = turns.size();
        for (int i = 0; i < turns.size(); i++) {
            if (turns.get(i).turnId().equals(turnId)) {
                end = i;
                break;
            }
        }
        return List.copyOf(turns.subList(Math.max(0, end - limit), end));
    }

    /**
     * The conversation as the console reads it at {@code now}: a turn still {@code thinking}
     * {@code staleAfter} after it started (its instance stopped before it answered) reads as
     * failed with {@code assistant.timeout}; the status is the last assistant turn's.
     */
    public AssistantConversation view(Instant now, Duration staleAfter) {
        List<AssistantTurn> shown = new ArrayList<>(turns.size());
        String status = AssistantConversation.READY;
        for (AssistantTurn turn : turns) {
            AssistantTurn current = turn;
            if (turn.isThinking() && !now.isBefore(turn.at().plus(staleAfter))) {
                current = AssistantTurn.failed(turn.turnId(), turn.at().plus(staleAfter), AssistantException.TIMEOUT, STALE);
            }
            shown.add(current);
            if (current.isAssistant()) {
                status = switch (current.status()) {
                    case AssistantTurn.THINKING -> AssistantConversation.THINKING;
                    case AssistantTurn.FAILED -> AssistantConversation.FAILED;
                    default -> AssistantConversation.READY;
                };
            }
        }
        return new AssistantConversation(conversationId, status, startedAt, shown);
    }
}
