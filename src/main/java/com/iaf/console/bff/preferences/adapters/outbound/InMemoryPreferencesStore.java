package com.iaf.console.bff.preferences.adapters.outbound;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.preferences.domain.Preferences;
import com.iaf.console.bff.preferences.ports.outbound.PreferencesStore;

import reactor.core.publisher.Mono;

/** Preferences of one instance, in memory. */
@Component
@Profile("!mongo")
public class InMemoryPreferencesStore implements PreferencesStore {

    private final Map<String, Preferences> byUser = new ConcurrentHashMap<>();

    @Override
    public Mono<Preferences> find(String userId) {
        return Mono.justOrEmpty(byUser.get(userId));
    }

    @Override
    public Mono<Void> save(String userId, Preferences preferences) {
        return Mono.fromRunnable(() -> byUser.put(userId, preferences));
    }
}
