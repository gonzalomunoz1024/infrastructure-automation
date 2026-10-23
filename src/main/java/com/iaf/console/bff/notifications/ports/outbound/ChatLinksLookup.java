package com.iaf.console.bff.notifications.ports.outbound;

import java.util.Set;

import reactor.core.publisher.Mono;

/**
 * Which chat platforms ({@code slack}, {@code teams}) a person linked a chat identity on
 * (decision 31.3), so the settings can say so and their notifications can go there. The ChatOps
 * slice answers it; until it is wired in, nothing is linked.
 */
public interface ChatLinksLookup {

    Mono<Set<String>> linkedPlatforms(String userId);
}
