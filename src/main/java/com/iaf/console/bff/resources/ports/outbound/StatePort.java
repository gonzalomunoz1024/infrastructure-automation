package com.iaf.console.bff.resources.ports.outbound;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.resources.domain.StateAnswer;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.resources.domain.StateCommands;
import com.iaf.console.bff.resources.domain.StateQuery;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The state service, called for a console user in their account: every read and command is scoped
 * to the account and region, and the state service checks the token says the same. Errors come
 * back as the kit's: 404 {@code NotFoundException}, 409 {@code ConflictException}, 400
 * {@code InvalidInputException}, 410 {@code GoneException}; anything else is an upstream failure.
 */
public interface StatePort {

    Mono<Page<StateResource>> list(AccountScope scope, StateCaller caller, StateQuery query);

    /** One resource; {@code ifNoneMatch} is the ETag the page holds, answered with {@code notModified} when it still stands. */
    Mono<StateAnswer> get(AccountScope scope, StateCaller caller, String wfrn, String ifNoneMatch);

    /** The resource as it was at an instant (decision 29), rebuilt from its log; 404 {@code resource.notYet} before it existed. */
    Mono<StateResource> getAt(AccountScope scope, StateCaller caller, String wfrn, String at);

    /** A page of the resource's log, oldest first after {@code after} (a version). */
    Mono<Page<JsonNode>> events(AccountScope scope, StateCaller caller, String wfrn, Integer limit, String after);

    Mono<StateResource> mint(AccountScope scope, StateCaller caller, StateCommands.Mint command, StateCommands.Meta meta);

    Mono<StateResource> requestUpdate(AccountScope scope, StateCaller caller, String wfrn, StateCommands.Update command, StateCommands.Meta meta);

    Mono<StateResource> requestDelete(AccountScope scope, StateCaller caller, String wfrn, StateCommands.Delete command, StateCommands.Meta meta);

    /** A failed resource back to {@code pending}, naming the rerun that retries it (hub decision 24); 409 {@code resource.notFailed} otherwise. */
    Mono<StateResource> requestRetry(AccountScope scope, StateCaller caller, String wfrn, StateCommands.Retry command, StateCommands.Meta meta);

    Mono<StateResource> tag(AccountScope scope, StateCaller caller, String wfrn, StateCommands.Tag command, StateCommands.Meta meta);
}
