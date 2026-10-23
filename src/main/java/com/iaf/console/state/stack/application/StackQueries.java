package com.iaf.console.state.stack.application;

import java.util.List;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.stack.domain.Stack;
import com.iaf.console.state.stack.domain.StackEvent;
import com.iaf.console.state.stack.domain.StackQuery;
import com.iaf.console.state.stack.ports.outbound.StackEventStorePort;
import com.iaf.console.state.stack.ports.outbound.StackStorePort;

import reactor.core.publisher.Mono;

/** The read side of stacks: one stack, an account's stacks, a stack's log. */
public final class StackQueries {

    private final StackStorePort store;
    private final StackEventStorePort events;

    public StackQueries(StackStorePort store, StackEventStorePort events) {
        this.store = store;
        this.events = events;
    }

    public Mono<Stack> get(AccountScope scope, String wfrn) {
        ResourceIdentity id = identity(wfrn);
        if (!Stacks.SERVICE.equals(id.service()) || !scope.covers(id.accountId(), id.region())) {
            return Mono.error(NotFoundException.of("stack", wfrn));
        }
        return store.find(scope, wfrn).switchIfEmpty(Mono.error(() -> NotFoundException.of("stack", wfrn)));
    }

    public Mono<Page<Stack>> list(AccountScope scope, StackQuery query) {
        return store.list(scope, query);
    }

    /** The stack's events after a version, oldest first; {@code next} is the last version of the page. */
    public Mono<Page<StackEvent>> events(AccountScope scope, String wfrn, long after, Integer limit) {
        int size = CursorPaging.pageSize(limit);
        return get(scope, wfrn).flatMap(stack -> events.list(scope, wfrn, after, size + 1).collectList().map(list -> {
            if (list.size() <= size) {
                return new Page<>(list, null);
            }
            List<StackEvent> kept = list.subList(0, size);
            return new Page<>(kept, String.valueOf(kept.get(size - 1).version()));
        }));
    }

    private static ResourceIdentity identity(String wfrn) {
        try {
            return ResourceIdentity.of(wfrn);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("not a wfrn", List.of(Problem.error("wfrn", "/wfrn", e.getMessage())));
        }
    }
}
