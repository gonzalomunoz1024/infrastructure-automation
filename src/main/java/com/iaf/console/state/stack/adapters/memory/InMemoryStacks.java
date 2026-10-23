package com.iaf.console.state.stack.adapters.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.application.InventoryService;
import com.iaf.console.state.stack.domain.Stack;
import com.iaf.console.state.stack.domain.StackQuery;
import com.iaf.console.state.stack.ports.outbound.StackStorePort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Stacks in memory, for the demo and the tests; the same answers as MongoDB's ({@code StackStoreContract}). */
public final class InMemoryStacks implements StackStorePort {

    private static final Comparator<Stack> NEWEST_FIRST = Comparator.comparing(Stack::createdAt).thenComparing(Stack::wfrn).reversed();

    private final Map<String, Stack> byWfrn = new ConcurrentHashMap<>();

    @Override
    public synchronized Mono<Stack> insert(Stack stack) {
        boolean taken = byWfrn.values().stream().anyMatch(s -> !s.deleted() && s.accountId().equals(stack.accountId())
                && s.region().equals(stack.region()) && s.name().equals(stack.name()));
        if (taken || byWfrn.containsKey(stack.wfrn())) {
            return Mono.error(new ConflictException("stack.nameTaken: the account has a stack named " + stack.name(), Map.of("name", stack.name())));
        }
        byWfrn.put(stack.wfrn(), stack);
        return Mono.just(stack);
    }

    @Override
    public synchronized Mono<Stack> update(Stack next, long expectedVersion) {
        Stack current = byWfrn.get(next.wfrn());
        if (current == null || current.version() != expectedVersion) {
            return Mono.error(new ConflictException(InventoryService.VERSION_CONFLICT + ": stack " + next.wfrn() + " moved on"));
        }
        byWfrn.put(next.wfrn(), next);
        return Mono.just(next);
    }

    @Override
    public Mono<Stack> find(AccountScope scope, String wfrn) {
        return Mono.justOrEmpty(byWfrn.get(wfrn)).filter(s -> scope.covers(s.accountId(), s.region()));
    }

    @Override
    public Mono<Stack> findByWfrn(String wfrn) {
        return Mono.justOrEmpty(byWfrn.get(wfrn));
    }

    @Override
    public Mono<Page<Stack>> list(AccountScope scope, StackQuery query) {
        Cursor after = query.after();
        List<Stack> matching = new ArrayList<>(byWfrn.values().stream()
                .filter(s -> scope.covers(s.accountId(), s.region()))
                .filter(s -> query.state() == null || s.state() == query.state())
                .filter(s -> query.blueprint() == null || s.blueprint().equals(query.blueprint()))
                .filter(s -> query.namePrefix() == null || s.name().startsWith(query.namePrefix()))
                .filter(s -> after == null || before(s, after))
                .sorted(NEWEST_FIRST)
                .limit(query.limit() + 1L)
                .toList());
        return Mono.just(Page.from(matching, query.limit(), s -> new Cursor(s.createdAt().toString(), s.wfrn())));
    }

    @Override
    public Flux<Stack> listInFlight(int limit) {
        return Flux.fromStream(byWfrn.values().stream().filter(Stack::inFlight).sorted(Comparator.comparing(Stack::updatedAt)).limit(limit));
    }

    /** Whether a stack comes after the cursor, newest first. */
    private static boolean before(Stack s, Cursor after) {
        int c = s.createdAt().compareTo(Instant.parse(String.valueOf(after.sortValue())));
        return c < 0 || c == 0 && s.wfrn().compareTo(after.id()) < 0;
    }
}
