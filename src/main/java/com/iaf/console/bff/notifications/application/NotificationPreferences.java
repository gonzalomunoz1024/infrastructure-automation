package com.iaf.console.bff.notifications.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.common.errors.CodedConflictException;
import com.iaf.console.bff.notifications.domain.NotificationSettings;
import com.iaf.console.bff.notifications.domain.SettingsView;
import com.iaf.console.bff.notifications.ports.outbound.ChatLinksLookup;
import com.iaf.console.bff.notifications.ports.outbound.SettingsStore;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

import reactor.core.publisher.Mono;

/**
 * A person's notification settings (decision 32.2): read with their identity-provider address and
 * chat links, and replaced only against the version they were read at: {@code If-Match} is
 * required (400 without), and a version that moved on is a 409 {@code version.moved} naming the
 * current one.
 */
public class NotificationPreferences {

    public static final String VERSION_MOVED = "version.moved";

    private final SettingsStore store;
    private final ChatLinksLookup chatLinks;

    public NotificationPreferences(SettingsStore store, ChatLinksLookup chatLinks) {
        this.store = store;
        this.chatLinks = chatLinks;
    }

    public Mono<SettingsView> get(Session session) {
        return current(session).zipWith(linked(session)).map(t -> SettingsView.of(t.getT1(), session.user().email(), t.getT2()));
    }

    public Mono<SettingsView> replace(Session session, String ifMatch, JsonNode body) {
        return Mono.defer(() -> {
            Long expected = versionOf(ifMatch);
            String userId = session.user().userId();
            return current(session).flatMap(found -> {
                if (expected != null && expected != found.version()) {
                    return Mono.error(moved(found.version()));
                }
                NotificationSettings next = NotificationSettings.from(body, found.version() + 1);
                return store.replace(userId, next, found.version()).flatMap(saved -> saved ? get(session)
                        : current(session).flatMap(now -> Mono.<SettingsView>error(moved(now.version()))));
            });
        });
    }

    private Mono<NotificationSettings> current(Session session) {
        return store.find(session.user().userId()).defaultIfEmpty(NotificationSettings.defaults());
    }

    private Mono<Set<String>> linked(Session session) {
        return chatLinks.linkedPlatforms(session.user().userId()).defaultIfEmpty(Set.of()).onErrorReturn(Set.of());
    }

    /** The version an {@code If-Match} names ({@code "3"}, {@code W/"3"} or {@code 3}); null for {@code *}, which takes any. */
    static Long versionOf(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new InvalidInputException("If-Match is required: the ETag the settings were read with",
                    List.of(Problem.error("required", "If-Match", "If-Match is required: the ETag the settings were read with")));
        }
        String tag = ifMatch.strip();
        tag = (tag.startsWith("W/") ? tag.substring(2) : tag).replace("\"", "").strip();
        if (tag.equals("*")) {
            return null;
        }
        try {
            return Long.parseLong(tag);
        } catch (NumberFormatException e) {
            throw new InvalidInputException("If-Match is the settings' ETag", List.of(Problem.error("type", "If-Match",
                    "If-Match is the ETag the settings were read with, such as \"3\"")));
        }
    }

    private static CodedConflictException moved(long version) {
        return new CodedConflictException(VERSION_MOVED, "the settings moved on to version " + version + "; read them again", Map.of("version", version));
    }
}
