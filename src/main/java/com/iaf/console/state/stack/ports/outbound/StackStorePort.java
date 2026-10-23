package com.iaf.console.state.stack.ports.outbound;

import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;
import com.iaf.console.state.stack.domain.Stack;
import com.iaf.console.state.stack.domain.StackQuery;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Where stacks are kept. The version is the optimistic lock: {@link #update} writes only over the
 * version the caller read. A name is unique among an account's stacks that are not deleted.
 */
public interface StackStorePort {

    /** Stores a new stack; 409 {@code stack.nameTaken} when a live stack of the account has its name. */
    Mono<Stack> insert(Stack stack);

    /** Replaces the stack at {@code expectedVersion}; 409 {@code version.conflict} when it moved on. */
    Mono<Stack> update(Stack next, long expectedVersion);

    Mono<Stack> find(AccountScope scope, String wfrn);

    /** A stack by its wfrn alone, for the runner, which follows a stack from its children. */
    @GlobalRead("the runner advances the stack a child names; nothing it reads is answered to a caller")
    Mono<Stack> findByWfrn(String wfrn);

    Mono<Page<Stack>> list(AccountScope scope, StackQuery query);

    /** Stacks still moving (pending, failed, deleting), least recently changed first, for the sweeper. */
    @GlobalRead("the sweeper advances every account's stacks")
    Flux<Stack> listInFlight(int limit);
}
