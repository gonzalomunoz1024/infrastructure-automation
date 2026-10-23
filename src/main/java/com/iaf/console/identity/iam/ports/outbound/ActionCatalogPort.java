package com.iaf.console.identity.iam.ports.outbound;

import com.iaf.console.identity.iam.domain.ActionCatalog;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** The actions that exist, from the platform's types and catalog; cached and refreshed on a timer. */
public interface ActionCatalogPort {

    @GlobalRead
    Mono<ActionCatalog> getCatalog();
}
