package com.iaf.console.bff.preferences.ports.outbound;

import com.iaf.console.bff.preferences.domain.Preferences;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** Where a user's preferences live, keyed by user: they follow the person across accounts. */
@GlobalRead("preferences belong to a user, not to an account")
public interface PreferencesStore {

    Mono<Preferences> find(String userId);

    Mono<Void> save(String userId, Preferences preferences);
}
