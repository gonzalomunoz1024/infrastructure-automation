package com.example.cmdb;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.provider.sdk.Action;
import com.iaf.provider.sdk.ActionContext;
import com.iaf.provider.sdk.Effect;
import com.iaf.provider.sdk.Field;
import com.iaf.provider.sdk.Provider;

/**
 * An example provider: a configuration management database that keeps configuration items in
 * memory. Three actions show the model: a read, the preview of a create, and the create itself,
 * made idempotent with the platform's idempotency key.
 */
public final class Cmdb {

    public enum Kind {
        SERVER, DATABASE, SERVICE
    }

    public record ItemRef(@Field(description = "The item id, like ci-1001") String id) {
    }

    public record Registration(
            @Field(description = "A unique name, like web-01") String name,
            Kind kind,
            @Field(description = "The owning team; unassigned when left out") Optional<String> owner) {
    }

    public record Item(String id, String name, Kind kind, String owner, String state, Instant registeredAt) {
    }

    public record Plan(String action, String name, boolean exists, String summary) {
    }

    private final Map<String, Item> items = new ConcurrentHashMap<>();
    /** The idempotency key of each registration, so a retried call answers the item it created. */
    private final Map<String, String> registrations = new ConcurrentHashMap<>();
    private final Clock clock;

    public Cmdb(Clock clock) {
        this.clock = clock;
    }

    /** The provider: who owns it, its worker and its actions. Its YAML is what the platform registers. */
    public Provider provider() {
        return Provider.named("cmdb")
                .title("Configuration items")
                .owner("team-platform")
                .version("1.0.0")
                .description("Registers servers, databases and services as configuration items. Example provider of the Java SDK.")
                .heartbeatTimeout(Duration.ofSeconds(30))
                .alerts("#platform-alerts")
                .error("not_found", false, "No configuration item has that id")
                .error("duplicate_name", false, "Another configuration item already has that name")
                .error("store_busy", true, "The database is busy; a retry succeeds")
                .action(Action.of("item.get", ItemRef.class, Item.class, this::get)
                        .title("Get a configuration item")
                        .effect(Effect.READ)
                        .sample(Map.of("id", "{{ $input.id }}", "name", "web-01", "kind", "SERVER", "owner", "team-platform", "state", "active",
                                "registeredAt", "2026-01-05T09:00:00Z")))
                .action(Action.of("item.preview", Registration.class, Plan.class, this::preview)
                        .title("Preview a registration")
                        .effect(Effect.READ)
                        .sample(Map.of("action", "create", "name", "{{ $input.name }}", "exists", false, "summary", "Registers {{ $input.name }}")))
                .action(Action.of("item.register", Registration.class, Item.class, this::register)
                        .title("Register a configuration item")
                        .effect(Effect.CREATE)
                        .plan("item.preview")
                        .sample(Map.of("id", "ci-{{ $job.seq }}", "name", "{{ $input.name }}", "kind", "{{ $input.kind }}", "owner", "team-platform",
                                "state", "active", "registeredAt", "2026-01-05T09:00:00Z")))
                .build();
    }

    Item get(ActionContext ctx, ItemRef in) {
        Item item = items.get(in.id());
        if (item == null) {
            throw ctx.fatal("not_found", "No configuration item " + in.id());
        }
        return item;
    }

    Plan preview(ActionContext ctx, Registration in) {
        boolean exists = byName(in.name()).isPresent();
        String summary = exists
                ? in.name() + " is already registered"
                : "Registers " + in.name() + " as a " + in.kind().name().toLowerCase(Locale.ROOT);
        return new Plan(exists ? "none" : "create", in.name(), exists, summary);
    }

    synchronized Item register(ActionContext ctx, Registration in) {
        String id = registrations.get(ctx.idempotencyKey());
        if (id != null) {
            return items.get(id); // a retry of a call that already registered the item
        }
        if (byName(in.name()).isPresent()) {
            throw ctx.fatal("duplicate_name", in.name() + " is already registered");
        }
        ctx.heartbeat("registering " + in.name());
        String next = "ci-" + (1001 + items.size());
        Item item = new Item(next, in.name(), in.kind(), in.owner().orElse("unassigned"), "active", clock.instant());
        items.put(next, item);
        registrations.put(ctx.idempotencyKey(), next);
        return item;
    }

    private Optional<Item> byName(String name) {
        return items.values().stream().filter(i -> i.name().equals(name)).findFirst();
    }
}
