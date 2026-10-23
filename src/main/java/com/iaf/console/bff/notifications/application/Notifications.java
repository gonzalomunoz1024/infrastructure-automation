package com.iaf.console.bff.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iaf.console.bff.common.errors.CodedConflictException;
import com.iaf.console.bff.notifications.domain.Channels;
import com.iaf.console.bff.notifications.domain.Draft;
import com.iaf.console.bff.notifications.domain.FeedQuery;
import com.iaf.console.bff.notifications.domain.Links;
import com.iaf.console.bff.notifications.domain.Notification;
import com.iaf.console.bff.notifications.domain.NotificationIds;
import com.iaf.console.bff.notifications.domain.NotificationKinds;
import com.iaf.console.bff.notifications.domain.NotificationSettings;
import com.iaf.console.bff.notifications.domain.OutboxEntry;
import com.iaf.console.bff.notifications.domain.Recipient;
import com.iaf.console.bff.notifications.domain.Recipients;
import com.iaf.console.bff.notifications.ports.outbound.ChatLinksLookup;
import com.iaf.console.bff.notifications.ports.outbound.NotificationChannel;
import com.iaf.console.bff.notifications.ports.outbound.NotificationFeed;
import com.iaf.console.bff.notifications.ports.outbound.NotificationOutbox;
import com.iaf.console.bff.notifications.ports.outbound.SettingsStore;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.wfrn.Ulid;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Notifications (decision 32): a draft is addressed to its people, and each person gets it on the
 * channels their settings name, once per event, person and channel. The console channel is the
 * feed's own once-only insert, then the person's open streams on this replica; email and chat
 * messages go through the outbox. A replica that finds another one stored a fresh notification
 * first still shows it on its own open streams, once. The feed is the person's own, across the
 * accounts their session holds.
 */
public class Notifications {

    /** How fresh a notification another replica stored must be for this replica to show it on its own open streams. */
    static final Duration LIVE_WINDOW = Duration.ofMinutes(10);
    static final int MAX_READ_IDS = 500;

    private static final Logger log = LoggerFactory.getLogger(Notifications.class);

    private final NotificationFeed feed;
    private final NotificationOutbox outbox;
    private final SettingsStore settings;
    private final People people;
    private final ChatLinksLookup chatLinks;
    private final Map<String, NotificationChannel> channels;
    private final OutboxWorker worker;
    private final Clock clock;
    private final Cache<String, Boolean> shown = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(200_000).build();

    public Notifications(NotificationFeed feed, NotificationOutbox outbox, SettingsStore settings, People people, ChatLinksLookup chatLinks,
            Map<String, NotificationChannel> channels, OutboxWorker worker, Clock clock) {
        this.feed = feed;
        this.outbox = outbox;
        this.settings = settings;
        this.people = people;
        this.chatLinks = chatLinks;
        this.channels = Map.copyOf(channels);
        this.worker = worker;
        this.clock = clock;
    }

    /** One page of a person's feed, with how many of the notifications it covers are unread. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FeedPage(List<Notification> items, String next, long unread) {
    }

    /** What is unread after a mark. */
    public record Unread(long unread) {
    }

    /** A test that went out: the channel and the notification it carried. */
    public record TestSent(String channel, String notificationId) {
    }

    // ---- addressing and delivering

    /** Addresses a draft and delivers it to each of its people on their channels; completes when every delivery is stored or queued. */
    public Mono<Void> accept(Draft draft) {
        return people.of(draft.accountId(), draft.region())
                .flatMapMany(found -> Flux.fromIterable(Recipients.of(draft, found.orElse(null))))
                .concatMap(recipient -> deliver(draft, recipient).onErrorResume(e -> {
                    log.warn("notifying {} of {} failed: {}", recipient.userId(), draft.eventKey(), e.toString());
                    return Mono.empty();
                }))
                .then();
    }

    /** The channels a channel name can go out on here: the console always, the others when an adapter for them is wired. */
    public boolean available(String channel) {
        return Channels.CONSOLE.equals(channel) || channels.containsKey(channel);
    }

    private Mono<Void> deliver(Draft draft, Recipient recipient) {
        Notification notification = draft.forPerson(recipient.userId());
        return Mono.zip(settings.find(recipient.userId()).defaultIfEmpty(NotificationSettings.defaults()), linked(recipient.userId()))
                .flatMapMany(t -> Flux.fromIterable(t.getT1().deliveries(draft.kind(), recipient.hasAddress(), t.getT2())))
                .filter(this::available)
                .concatMap(channel -> send(draft.eventKey(), recipient, notification, channel))
                .then();
    }

    private Mono<Void> send(String eventKey, Recipient recipient, Notification notification, String channel) {
        if (Channels.CONSOLE.equals(channel)) {
            return feed.insert(recipient.userId(), notification)
                    .flatMap(stored -> stored || isFresh(notification) ? show(recipient, notification) : Mono.<Void>empty());
        }
        String key = NotificationIds.deliveryKey(eventKey, recipient.userId(), channel);
        return outbox.enqueue(OutboxEntry.pending(key, channel, recipient, notification, clock.instant())).doOnNext(queued -> {
            if (queued) {
                worker.nudge();
            }
        }).then();
    }

    private boolean isFresh(Notification notification) {
        return notification.createdAt().isAfter(clock.instant().minus(LIVE_WINDOW));
    }

    /** The notification on the person's open streams on this replica, at most once. */
    private Mono<Void> show(Recipient recipient, Notification notification) {
        NotificationChannel console = channels.get(Channels.CONSOLE);
        if (console == null || shown.asMap().putIfAbsent(notification.id(), Boolean.TRUE) != null) {
            return Mono.empty();
        }
        return console.deliver(recipient, notification).onErrorResume(e -> {
            log.warn("showing notification {} on the live stream failed: {}", notification.id(), e.toString());
            return Mono.empty();
        });
    }

    private Mono<Set<String>> linked(String userId) {
        return chatLinks.linkedPlatforms(userId).defaultIfEmpty(Set.of()).onErrorResume(e -> {
            log.warn("the chat links of {} could not be read: Slack and Teams are left out: {}", userId, e.toString());
            return Mono.just(Set.of());
        });
    }

    // ---- the feed

    /** {@code GET /console/v1/notifications}: the person's own, newest first, in their accounts or the one asked for. */
    public Mono<FeedPage> feed(Session session, Boolean unread, String kind, String accountId, Integer limit, String after) {
        return Mono.defer(() -> {
            if (kind != null && !kind.isBlank() && !NotificationKinds.isKnown(kind) && !NotificationKinds.TEST.equals(kind)) {
                throw new InvalidInputException("unknown kind " + kind, List.of(Problem.error("unknown-kind", "kind", "the kinds are " + NotificationKinds.ALL)));
            }
            int size = CursorPaging.pageSize(limit);
            Cursor cursor = after == null || after.isBlank() ? null : CursorPaging.decode(after);
            if (cursor != null && !(cursor.sortValue() instanceof Number)) {
                throw new IllegalArgumentException("the cursor is not one this service issued");
            }
            Set<String> accounts = accountsOf(session);
            if (accountId != null && !accountId.isBlank()) {
                accounts = accounts.contains(accountId) ? Set.of(accountId) : Set.of();
            }
            FeedQuery query = new FeedQuery(accounts, kind == null || kind.isBlank() ? null : kind, Boolean.TRUE.equals(unread), size, cursor);
            String userId = session.user().userId();
            return Mono.zip(feed.list(userId, query), feed.countUnread(userId, query.forCount())).map(t -> {
                Page<Notification> page = Page.from(t.getT1(), size, n -> new Cursor(FeedQuery.sortValue(n), n.id()));
                return new FeedPage(page.items(), page.next(), t.getT2());
            });
        });
    }

    /** {@code POST /console/v1/notifications:read}: {@code {ids}} or {@code {all: true}}; answers what is still unread. */
    public Mono<Unread> markRead(Session session, JsonNode body) {
        return Mono.defer(() -> {
            String userId = session.user().userId();
            Instant now = clock.instant();
            boolean all = body != null && body.path("all").asBoolean(false);
            if (all) {
                return feed.markAllRead(userId, now).then(unread(session));
            }
            JsonNode ids = body == null ? null : body.get("ids");
            if (ids == null || !ids.isArray() || ids.isEmpty()) {
                throw new InvalidInputException("name the notifications read", List.of(Problem.error("required", "ids",
                        "ids is a list of notification ids, or send all: true")));
            }
            if (ids.size() > MAX_READ_IDS) {
                throw new InvalidInputException("too many ids", List.of(Problem.error("range", "ids", "at most " + MAX_READ_IDS + " ids at once")));
            }
            Set<String> named = new LinkedHashSet<>();
            for (JsonNode id : ids) {
                if (!id.isTextual() || id.asText().isBlank()) {
                    throw new InvalidInputException("an id is text", List.of(Problem.error("type", "ids", "every id is a notification id")));
                }
                named.add(id.asText());
            }
            return feed.markRead(userId, named, now).then(unread(session));
        });
    }

    private Mono<Unread> unread(Session session) {
        return feed.countUnread(session.user().userId(), new FeedQuery(accountsOf(session), null, true, 1, null)).map(Unread::new);
    }

    private static Set<String> accountsOf(Session session) {
        Set<String> out = new LinkedHashSet<>();
        for (AccountMembership m : session.accounts()) {
            out.add(m.accountId());
        }
        return out;
    }

    // ---- the test

    /**
     * {@code POST /console/v1/notification-settings:test}: a test notification to the person on one
     * channel, whatever their rules say: 400 for an unknown channel, 409 {@code channel.unavailable}
     * when it cannot reach them (no address, no chat link, or no such channel here).
     */
    public Mono<TestSent> sendTest(Session session, String channel) {
        return Mono.defer(() -> {
            if (!Channels.isKnown(channel)) {
                throw new InvalidInputException("unknown channel " + channel, List.of(Problem.error("unknown-channel", "channel",
                        "the channels are " + Channels.ALL)));
            }
            Recipient me = new Recipient(session.user().userId(), session.user().email(), session.user().name());
            Mono<Boolean> reachable = switch (channel) {
                case Channels.CONSOLE -> Mono.just(true);
                case Channels.EMAIL -> Mono.just(me.hasAddress() && available(channel));
                default -> linked(me.userId()).map(platforms -> platforms.contains(channel) && available(channel));
            };
            return reachable.flatMap(ok -> {
                if (!ok) {
                    return Mono.error(new CodedConflictException("channel.unavailable", unavailable(channel, me), Map.of("channel", channel)));
                }
                Instant now = clock.instant();
                String eventKey = NotificationKinds.TEST + ":" + Ulid.next().toLowerCase(java.util.Locale.ROOT);
                Draft draft = new Draft(eventKey, NotificationKinds.TEST, Notification.INFO, "Test notification",
                        "This is a test of your " + channel + " notifications from the console.", session.accountId(), session.region(), null, null,
                        Links.settings(), now, List.of());
                Notification notification = draft.forPerson(me.userId());
                return send(eventKey, me, notification, channel).thenReturn(new TestSent(channel, notification.id()));
            });
        });
    }

    private String unavailable(String channel, Recipient me) {
        if (Channels.EMAIL.equals(channel)) {
            return me.hasAddress() ? "no email server is configured" : "the identity provider holds no email address for you";
        }
        return available(channel) ? "you have not linked " + channel + " yet" : channel + " is not set up here";
    }

    /** For the configuration's start-up line. */
    List<String> channelIds() {
        return new ArrayList<>(channels.keySet());
    }
}
