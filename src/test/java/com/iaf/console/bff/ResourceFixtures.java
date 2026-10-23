package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.ResourceFilter;
import com.iaf.console.platform.model.Resource;

/** What the phase 4 endpoint tests share: creating a VM through the console, finding a seeded one, reading JSON arrays. */
final class ResourceFixtures {

    static final String VM = "/console/v1/r/vm";

    private ResourceFixtures() {
    }

    /** A VM created through the console and made active by the feed; its wfrn. */
    static String createVm(Console console, PlatformClients clients, Console.Signed as, String name) {
        return createVm(console, clients, as, name, Map.of("environment", "dev", "owner", "team-compute", "cost-centre", "cc-1001",
                "data-class", "internal"));
    }

    static String createVm(Console console, PlatformClients clients, Console.Signed as, String name, Map<String, String> tags) {
        Console.Reply created = console.send(HttpMethod.POST, VM, as, Map.of("input", Map.of("name", name, "image", imageId(clients), "size", "small",
                "environment", "dev"), "tags", tags), Map.of("Idempotency-Key", "create-" + name + "-" + System.nanoTime()));
        assertEquals(202, created.status(), created.body());
        String wfrn = created.json().path("wfrn").asText();
        assertNotNull(created.json().path("jobId").asText(null), created.body());
        awaitState(console, as, wfrn, "active");
        return wfrn;
    }

    static void awaitState(Console console, Console.Signed as, String wfrn, String state) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> state.equals(console.getAt(VM + "/{wfrn}", as, Map.of(), wfrn).json().path("state").asText()));
    }

    /** The wfrn of a seeded VM, by name. */
    static String seededVm(Console console, Console.Signed as, String name) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> stream(console.get(VM + "?limit=200", as).json().path("items")).anyMatch(i -> name.equals(i.path("name").asText())));
        return stream(console.get(VM + "?limit=200", as).json().path("items")).filter(i -> name.equals(i.path("name").asText()))
                .map(i -> i.path("wfrn").asText()).findFirst().orElseThrow();
    }

    static String imageId(PlatformClients clients) {
        PlatformPort platform = clients.forAccount(AccountScope.of(DemoApp.COMPUTE, "global", "user_gonzalo")).block();
        List<Resource> images = platform.listResources(new ResourceFilter("image", null, null, null, null, null, 5, null)).block().items();
        return images.isEmpty() ? "img-missing" : images.get(0).id();
    }

    static Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(items::add);
        }
        return items.stream();
    }
}
