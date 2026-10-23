package com.iaf.console.bff.notifications.adapters.outbound;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.iaf.console.bff.notifications.ports.outbound.ChatLinksLookup;

import reactor.core.publisher.Mono;

/**
 * Until the ChatOps slice (decision 31.3) is wired in, no one has linked Slack or Teams, so those
 * channels reach no one. Its own lookup replaces this one (marked {@code @Primary}, or this class
 * removed).
 */
@Component
public class NoChatLinks implements ChatLinksLookup {

    @Override
    public Mono<Set<String>> linkedPlatforms(String userId) {
        return Mono.just(Set.of());
    }
}
