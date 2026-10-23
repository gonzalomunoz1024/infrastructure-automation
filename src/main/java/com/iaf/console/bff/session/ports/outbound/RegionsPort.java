package com.iaf.console.bff.session.ports.outbound;

import java.util.List;

import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** The names of the platform's regions, for validating a session switch. */
@GlobalRead("regions are platform content, the same for every account")
public interface RegionsPort {

    Mono<List<String>> listRegionNames();
}
