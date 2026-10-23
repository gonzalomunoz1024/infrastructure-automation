package com.iaf.console.state.stack.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.state.stack.ports.outbound.StackStorePort;

import reactor.core.publisher.Mono;

/**
 * The stacks' safety net: every pass advances the stacks still moving (pending, failed, deleting),
 * least recently changed first, so a stack whose child's commit never reached it, or whose ready
 * announcement was lost, moves on anyway. Advancing is idempotent, so the sweeper and the commits of
 * children never undo each other.
 */
public final class StackSweeper {

    private static final Logger log = LoggerFactory.getLogger(StackSweeper.class);

    private final Stacks stacks;
    private final StackStorePort store;
    private final int batch;

    public StackSweeper(Stacks stacks, StackStorePort store, int batch) {
        this.stacks = stacks;
        this.store = store;
        this.batch = batch;
    }

    /** One pass: answers how many stacks it looked at. */
    public Mono<Integer> tick() {
        return store.listInFlight(batch)
                .concatMap(stack -> stacks.advance(stack.wfrn()).onErrorResume(e -> {
                    log.warn("stack {} could not be advanced: {}", stack.wfrn(), e.toString());
                    return Mono.empty();
                }).thenReturn(1))
                .reduce(0, Integer::sum);
    }
}
