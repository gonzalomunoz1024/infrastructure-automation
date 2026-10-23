package com.iaf.console.bff.notifications.ports.outbound;

import com.iaf.console.bff.notifications.domain.NotificationSettings;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** Where each person's notification settings live, keyed by person: they follow them across accounts. */
@GlobalRead("notification settings belong to a person, not to an account")
public interface SettingsStore {

    Mono<NotificationSettings> find(String userId);

    /** Saves settings made against {@code expectedVersion}; false, saving nothing, when the stored ones moved on since. */
    Mono<Boolean> replace(String userId, NotificationSettings settings, long expectedVersion);
}
