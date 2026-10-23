package com.iaf.console.state.stack.ports.outbound;

import java.util.List;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.stack.domain.StackEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A stack's log: its events in version order, each written once. */
public interface StackEventStorePort {

    Mono<Void> append(List<StackEvent> events);

    /** The stack's events after a version, oldest first, at most {@code limit}. */
    Flux<StackEvent> list(AccountScope scope, String wfrn, long afterVersion, int limit);
}
