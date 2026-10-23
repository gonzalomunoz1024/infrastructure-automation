package com.iaf.console.state.inventory.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** The names and values the domain tests share. */
public final class Fixtures {

    public static final String ACCOUNT = "123456789012";
    public static final String REGION = "global";
    public static final Instant T0 = Instant.parse("2026-10-23T10:00:00Z");
    public static final ResourceTypeInfo VM = new ResourceTypeInfo("vm", "vms", "compute",
            Map.of("building", "pending", "ready", "active", "deprecated", "active", "retired", "deleted"), "missing", Set.of(), Set.of("cpu", "memoryGb"),
            Set.of("owner"));
    public static final ResourceTypeInfo DATABASE = new ResourceTypeInfo("database", "databases", "data", Map.of(), "missing", Set.of("secret"),
            Set.of(), Set.of("owner", "secret"));

    private Fixtures() {
    }

    public static ResourceIdentity vm() {
        return ResourceIdentity.mint("wf", "compute", REGION, ACCOUNT, "vm");
    }

    public static Commands.Meta by(String actor) {
        return new Commands.Meta(null, actor, "req_1");
    }

    public static Commands.Meta at(long expectedVersion, String actor) {
        return new Commands.Meta(expectedVersion, actor, "req_1");
    }

    public static ObjectNode input(String name) {
        ObjectNode input = Json.object();
        input.put("name", name);
        input.put("image", "img-base-ubuntu");
        input.put("size", "small");
        input.put("environment", "dev");
        return input;
    }

    public static ObjectNode attributes(String name, int cpu, int memoryGb) {
        ObjectNode a = Json.object();
        a.put("name", name);
        a.put("cpu", cpu);
        a.put("memoryGb", memoryGb);
        a.put("environment", "dev");
        return a;
    }

    public static Instant t(int seconds) {
        return T0.plusSeconds(seconds);
    }
}
