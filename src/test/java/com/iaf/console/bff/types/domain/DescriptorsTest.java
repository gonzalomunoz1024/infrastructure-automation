package com.iaf.console.bff.types.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.ConsoleBlock;
import com.iaf.console.platform.model.ResourceType;

/** P-06: the defaults a bare resource type gets, and what a console block overrides. */
class DescriptorsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aBareTypeGetsTheDefaults() throws Exception {
        JsonNode schema = JSON.readTree("""
                {"type":"object","required":["name"],"properties":{
                  "name":{"type":"string","x-console":{"column":true,"searchable":true}},
                  "sizeGb":{"type":"integer","x-console":{"column":true,"mutable":true}},
                  "secret":{"type":"string","x-console":{"sensitive":true}}}}""");
        ResourceType bucket = new ResourceType("bucket", null, "team-storage", "An object store bucket.", "public", schema,
                List.of("building", "ready", "deprecated", "retired", "oops"), "create-bucket", null, null, null, 3, null);
        CatalogEntry create = entry("create-bucket", "bucket", "create", "POST", null, false, false, null, "create");
        CatalogEntry delete = entry("delete-bucket", "bucket", "delete", "DELETE", "bucketId", true, true, null, "delete");
        CatalogEntry resize = entry("resize-bucket", "bucket", "update", null, "bucketId", false, false, List.of("sizeGb"), "change");
        CatalogEntry other = entry("create-vm", "vm", "create", "POST", null, false, false, null, "create");

        ConsoleType type = Descriptors.describe(bucket, List.of(delete, other, resize, create));

        assertEquals("storage/bucket", type.key());
        assertEquals("storage", type.service());
        assertEquals("buckets", type.plural());
        assertEquals("Buckets", type.title());
        assertEquals(Descriptors.DEFAULT_ICON, type.icon());
        assertEquals("An object store bucket.", type.description());
        assertEquals(List.of("name", "status", "sizeGb", "created"), type.list().columns());
        assertEquals("-created", type.list().defaultSort());
        assertEquals(Descriptors.DEFAULT_TABS, type.detail().tabs());
        assertEquals(Map.of("building", "pending", "ready", "active", "deprecated", "active", "retired", "deleted"), type.lifecycle());
        assertFalse(type.observe().supported());
        assertEquals(List.of("create", "update", "delete"), type.actions().stream().map(ConsoleAction::verb).toList());
        ConsoleAction del = type.actions().get(2);
        assertTrue(del.destructive());
        assertTrue(del.approval());
        assertEquals("DELETE", del.method());
        assertEquals("bucketId", del.subjectInput());
        assertFalse(del.input().path("properties").has("bucketId"), "the subject is not asked for");
        assertTrue(del.input().path("properties").has("reason"));
        assertFalse(del.input().path("required").toString().contains("bucketId"));
        assertEquals("PATCH", type.actions().get(1).method());
        assertEquals(List.of("sizeGb"), type.actions().get(1).mutableFields());
        assertNull(type.actions().get(0).allowed(), "not decided yet");
        assertEquals(24, type.etag().length());
    }

    @Test
    void theConsoleBlockWinsAndTheEtagFollowsTheContent() {
        ConsoleBlock console = new ConsoleBlock("Virtual machines", "server", new ConsoleBlock.ListSpec(List.of("name", "cpu"), "name"),
                new ConsoleBlock.DetailSpec(List.of("configuration", "monitoring")), Map.of("deprecated", "updating"));
        ResourceType vm = new ResourceType("vm", "vms", "team-compute", null, "public", null, List.of("ready", "deprecated"), "create-vm", "compute",
                console, null, 1, "h1");
        ConsoleType type = Descriptors.describe(vm, List.of());
        assertEquals("compute/vm", type.key());
        assertEquals("Virtual machines", type.title());
        assertEquals("server", type.icon());
        assertEquals(List.of("name", "cpu"), type.list().columns());
        assertEquals("name", type.list().defaultSort());
        assertEquals(List.of("configuration", "monitoring"), type.detail().tabs());
        assertEquals("updating", type.lifecycle().get("deprecated"), "the block overrides the name rule");
        assertEquals("active", type.lifecycle().get("ready"));
        assertEquals(type.etag(), Descriptors.describe(vm, List.of()).etag(), "the etag is stable");
        ResourceType changed = new ResourceType("vm", "vms", "team-compute", null, "public", null, List.of("ready", "deprecated"), "create-vm", "compute",
                console, null, 2, "h2");
        assertNotEquals(type.etag(), Descriptors.describe(changed, List.of()).etag());
        CatalogEntry restart = entry("vm-restart", "vm", "restart", "POST", "vmId", false, true, null, "change");
        assertNotEquals(type.etag(), Descriptors.describe(vm, List.of(restart)).etag(), "a new verb changes the etag");
    }

    @Test
    void decidedActionsCarryTheDenial() {
        CatalogEntry create = entry("create-bucket", "bucket", "create", "POST", null, false, false, null, "create");
        ConsoleAction action = Descriptors.action(create);
        com.iaf.console.bff.access.domain.Denial denial = com.iaf.console.bff.access.domain.Denial.byPolicy("storage:create", "r", "no", "viewer", "p", "s");
        assertTrue(action.allowed(true, denial).allowed());
        assertNull(action.allowed(true, denial).denied());
        assertFalse(action.allowed(false, denial).allowed());
        assertEquals(denial, action.allowed(false, denial).denied());
    }

    private static CatalogEntry entry(String workflow, String noun, String verb, String method, String subjectInput, boolean destructive, boolean approval,
            List<String> mutableFields, String effect) {
        JsonNode input;
        try {
            input = JSON.readTree("{\"type\":\"object\",\"required\":[\"" + (subjectInput == null ? "name" : subjectInput) + "\",\"reason\"],\"properties\":{\""
                    + (subjectInput == null ? "name" : subjectInput) + "\":{\"type\":\"string\"},\"reason\":{\"type\":\"string\"}}}");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return new CatalogEntry(workflow, null, noun, verb, "developer", method, "/api/v1/" + noun + "s", null, verb.equals("create") ? null : "/p",
                input, null, "team-storage", "1.0.0", "desc", null, null, subjectInput, destructive, approval, mutableFields, null, effect);
    }
}
