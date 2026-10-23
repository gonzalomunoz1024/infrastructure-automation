package com.iaf.console.bff.assistant.adapters.outbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.assistant.domain.Conversation;
import com.iaf.console.bff.assistant.ports.outbound.ConversationStore;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The conversations of one instance, in memory: each found only by its person in its account and
 * region, and forgotten at its {@code expiresAt} (on the next read of it, and by a sweep at most
 * once a minute on writes).
 */
@Component
@Profile("!mongo")
public class InMemoryConversationStore implements ConversationStore {

    static final Duration SWEEP_EVERY = Duration.ofMinutes(1);

    private final Map<String, Conversation> byId = new ConcurrentHashMap<>();
    private final Clock clock;
    private final AtomicReference<Instant> nextSweep;

    public InMemoryConversationStore(Clock clock) {
        this.clock = clock;
        this.nextSweep = new AtomicReference<>(Instant.MIN);
    }

    @Override
    public Mono<Conversation> find(AccountScope scope, String conversationId) {
        return Mono.fromSupplier(() -> {
            Conversation found = byId.get(conversationId);
            if (found == null) {
                return null;
            }
            if (found.expired(clock.instant())) {
                byId.remove(conversationId, found);
                return null;
            }
            return mine(scope, found) ? found : null;
        });
    }

    @Override
    public Mono<Conversation> insert(AccountScope scope, Conversation conversation) {
        return Mono.fromSupplier(() -> {
            requireMine(scope, conversation);
            sweep();
            if (byId.putIfAbsent(conversation.conversationId(), conversation) != null) {
                throw new IllegalStateException("a conversation " + conversation.conversationId() + " exists");
            }
            return conversation;
        });
    }

    @Override
    public Mono<Conversation> replace(AccountScope scope, Conversation conversation, long expectedVersion) {
        return Mono.fromSupplier(() -> {
            requireMine(scope, conversation);
            boolean[] replaced = {false};
            byId.computeIfPresent(conversation.conversationId(), (id, current) -> {
                if (mine(scope, current) && current.version() == expectedVersion && !current.expired(clock.instant())) {
                    replaced[0] = true;
                    return conversation;
                }
                return current;
            });
            return replaced[0] ? conversation : null;
        });
    }

    /** How many conversations are kept, expired ones not yet swept included. */
    int size() {
        return byId.size();
    }

    private void sweep() {
        Instant now = clock.instant();
        Instant due = nextSweep.get();
        if (now.isBefore(due) || !nextSweep.compareAndSet(due, now.plus(SWEEP_EVERY))) {
            return;
        }
        byId.values().removeIf(c -> c.expired(now));
    }

    private static boolean mine(AccountScope scope, Conversation c) {
        return c.of(scope.principal(), scope.accountId(), scope.region());
    }

    private static void requireMine(AccountScope scope, Conversation c) {
        if (!mine(scope, c)) {
            throw new IllegalStateException("the conversation " + c.conversationId() + " is not the scope's");
        }
    }
}
