package com.iaf.console.bff.preferences.application;

import com.iaf.console.bff.preferences.domain.Preferences;
import com.iaf.console.bff.preferences.ports.outbound.PreferencesStore;
import com.iaf.console.kit.errors.ConflictException;

import reactor.core.publisher.Mono;

/** Reads and replaces a user's preferences; a stale {@code version} on a replace is a conflict. */
public class PreferencesService {

    private final PreferencesStore store;

    public PreferencesService(PreferencesStore store) {
        this.store = store;
    }

    public Mono<Preferences> get(String userId) {
        return store.find(userId).defaultIfEmpty(Preferences.defaults());
    }

    public Mono<Preferences> replace(String userId, Preferences submitted) {
        return get(userId).flatMap(current -> {
            if (submitted.version() != null && !submitted.version().equals(current.version())) {
                return Mono.error(new ConflictException("the preferences changed since version " + submitted.version(), current));
            }
            Preferences next = submitted.validated(current.version() + 1);
            return store.save(userId, next).thenReturn(next);
        });
    }
}
