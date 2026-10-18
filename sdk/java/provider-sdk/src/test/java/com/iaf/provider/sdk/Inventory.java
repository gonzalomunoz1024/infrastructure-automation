package com.iaf.provider.sdk;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A small provider the tests serve: items in a map. */
final class Inventory {

    record Lookup(@Field(description = "The item id") String id) {
    }

    record Create(String name, Optional<String> owner) {
    }

    record Item(String id, String name, String state) {
    }

    final Map<String, Item> items = new ConcurrentHashMap<>();
    /** Idempotency key to the id of the item that call created. */
    private final Map<String, String> created = new ConcurrentHashMap<>();

    Inventory() {
        items.put("it-1", new Item("it-1", "first", "active"));
    }

    Item get(ActionContext ctx, Lookup in) {
        Item item = items.get(in.id());
        if (item == null) {
            throw ctx.fatal("not_found", "No item " + in.id());
        }
        return item;
    }

    Item create(ActionContext ctx, Create in) {
        String id = created.computeIfAbsent(ctx.idempotencyKey(), key -> {
            Item item = new Item("it-" + (items.size() + 1), in.name(), "active");
            items.put(item.id(), item);
            return item.id();
        });
        return items.get(id);
    }

    Provider.Builder provider() {
        return Provider.named("inventory")
                .title("Inventory")
                .owner("team-platform")
                .version("1.2.0")
                .description("Items the tests keep in memory")
                .heartbeatTimeout(Duration.ofSeconds(30))
                .alerts("#platform-alerts")
                .error("not_found", false, "No item has that id")
                .error("busy", true, "The inventory is busy; try again")
                .action(Action.of("item.get", Lookup.class, Item.class, this::get)
                        .title("Get an item")
                        .effect(Effect.READ)
                        .sample(Map.of("id", "{{ $input.id }}", "name", "sample", "state", "active")))
                .action(Action.of("item.preview", Create.class, Item.class, (ctx, in) -> new Item("(new)", in.name(), "planned"))
                        .title("Preview an item")
                        .effect(Effect.READ))
                .action(Action.of("item.create", Create.class, Item.class, this::create)
                        .title("Create an item")
                        .effect(Effect.CREATE)
                        .plan("item.preview"));
    }
}
