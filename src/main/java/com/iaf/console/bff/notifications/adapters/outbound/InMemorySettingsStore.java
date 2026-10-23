package com.iaf.console.bff.notifications.adapters.outbound;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.notifications.domain.NotificationSettings;
import com.iaf.console.bff.notifications.ports.outbound.SettingsStore;

import reactor.core.publisher.Mono;

/** Notification settings of one instance, in memory. */
@Component
@Profile("!mongo")
public class InMemorySettingsStore implements SettingsStore {

    private final Map<String, NotificationSettings> byUser = new HashMap<>();

    @Override
    public Mono<NotificationSettings> find(String userId) {
        return Mono.fromCallable(() -> {
            synchronized (byUser) {
                return byUser.get(userId);
            }
        });
    }

    @Override
    public Mono<Boolean> replace(String userId, NotificationSettings settings, long expectedVersion) {
        return Mono.fromCallable(() -> {
            synchronized (byUser) {
                NotificationSettings current = byUser.get(userId);
                long version = current == null ? 0 : current.version();
                if (version != expectedVersion) {
                    return false;
                }
                byUser.put(userId, settings);
                return true;
            }
        });
    }
}
