package com.iaf.console.state.inventory.ports.outbound;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.StackRef;

import reactor.core.publisher.Mono;

/**
 * The stacks a resource may be created for (hub decision 33), as the inventory needs them; the
 * stack slice implements it, so the inventory never depends on stacks.
 */
public interface StackMembershipPort {

    /**
     * The wfrn a mint for a stack step opens: the ready step takes {@code proposedWfrn} for the
     * attempt, or answers the child it took already for that attempt. 404 for a stack the scope does
     * not see, 409 {@code stack.stepNotReady} for a step that takes no child.
     */
    Mono<String> claim(AccountScope scope, StackRef ref, String type, String region, String proposedWfrn, String actor, String requestId);

    /** A stack's resource changed: the stack looks at its children again. */
    Mono<Void> changed(Resource resource);

    /** Whether a stack that is not deleted still holds the resource. */
    Mono<Boolean> holds(Resource resource);

    /** No stacks: every resource stands alone. */
    StackMembershipPort NONE = new StackMembershipPort() {
        @Override
        public Mono<String> claim(AccountScope scope, StackRef ref, String type, String region, String proposedWfrn, String actor, String requestId) {
            return Mono.error(new com.iaf.console.kit.errors.NotFoundException("stack " + ref.wfrn() + " not found"));
        }

        @Override
        public Mono<Void> changed(Resource resource) {
            return Mono.empty();
        }

        @Override
        public Mono<Boolean> holds(Resource resource) {
            return Mono.just(false);
        }
    };
}
