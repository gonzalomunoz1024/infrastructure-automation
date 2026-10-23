package com.iaf.console.identity.principals.ports.outbound;

import com.iaf.console.identity.principals.domain.DirectoryGroup;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/**
 * The identity provider's directory, which the nightly group sync reads: a group's members by the
 * group's name. Directory groups belong to no account.
 */
public interface DirectoryPort {

    /** The group of this name with its members; empty when the directory has no such group, an error when it cannot be asked. */
    @GlobalRead
    Mono<DirectoryGroup> findGroup(String name);
}
