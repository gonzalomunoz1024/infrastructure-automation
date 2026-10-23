package com.iaf.console.search.index.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.events.EventEnvelope;

/** Events and documents as the state service emits them, for the tests. */
public final class Fixtures {

    public static final String ACCOUNT = "123456789012";
    public static final String OTHER_ACCOUNT = "210987654321";
    public static final String VM = "wfrn:wf:compute:global:123456789012:vm/vm-01jbx7q2m4k9d3c8f5r6t1w0ya";
    public static final String VM2 = "wfrn:wf:compute:global:123456789012:vm/vm-01jbx7q2m4k9d3c8f5r6t1w0yb";
    public static final String DB = "wfrn:wf:data:global:123456789012:database/db-01jbx7q2m4k9d3c8f5r6t1w0yc";
    public static final String OTHERS_VM = "wfrn:wf:compute:global:210987654321:vm/vm-01jbx7q2m4k9d3c8f5r6t1w0yd";
    public static final Instant T0 = Instant.parse("2026-10-23T02:10:40Z");

    public static final ResourceTypeHints VM_HINTS = new ResourceTypeHints("vm", "vms", "Virtual machines", "compute", Set.of("name", "environment"),
            Set.of());
    public static final ResourceTypeHints DB_HINTS = new ResourceTypeHints("database", "databases", "Databases", "data",
            Set.of("name", "environment", "secret"), Set.of("secret"));

    private Fixtures() {
    }

    public static ObjectNode data() {
        return Json.object();
    }

    /** A state.resources event: the P-01 envelope with the console's fields in data. */
    public static EventEnvelope resourceEvent(String type, String wfrn, long version, String state, Instant at, ObjectNode data) {
        ObjectNode d = data == null ? Json.object() : data;
        d.put("version", version);
        d.put("state", state);
        String resourceType = wfrn.substring(wfrn.lastIndexOf(':') + 1, wfrn.indexOf('/'));
        d.put("resourceType", resourceType);
        String account = wfrn.split(":")[4];
        EventEnvelope e = EventEnvelope.of("evt_" + type + version + wfrn.hashCode(), type + ":" + wfrn + ":" + version, type, at, "resource", wfrn, "dana",
                d);
        return e.withContext(new EventEnvelope.Context(account, "global", wfrn, "req_1"), null);
    }

    public static EventEnvelope requested(String wfrn, String name, Map<String, Object> input) {
        ObjectNode d = data().put("name", name).put("workflow", "create-vm");
        ObjectNode in = d.putObject("input");
        input.forEach((k, v) -> in.set(k, Json.MAPPER.valueToTree(v)));
        return resourceEvent("ResourceRequested", wfrn, 1, "pending", T0, d);
    }

    public static EventEnvelope succeeded(String wfrn, long version, Map<String, Object> attributes, Instant at) {
        ObjectNode d = data().put("jobId", "job-" + version).put("workflow", "create-vm").put("registryId", "reg-1");
        ObjectNode attrs = d.putObject("attributes");
        attributes.forEach((k, v) -> attrs.set(k, Json.MAPPER.valueToTree(v)));
        return resourceEvent("ProvisioningSucceeded", wfrn, version, "active", at, d);
    }

    public static EventEnvelope tagged(String wfrn, long version, Map<String, String> set, java.util.List<String> removed, Instant at) {
        ObjectNode d = data();
        ObjectNode s = d.putObject("set");
        set.forEach(s::put);
        var r = d.putArray("removed");
        removed.forEach(r::add);
        return resourceEvent("Tagged", wfrn, version, "active", at, d);
    }

    public static EventEnvelope deleted(String wfrn, long version, Instant at) {
        ObjectNode d = data().put("jobId", "job-del").put("tombstoneUntil", at.plusSeconds(90L * 86400).toString());
        return resourceEvent("Deleted", wfrn, version, "deleted", at, d);
    }

    /** A drift.findings event. */
    public static EventEnvelope finding(String type, String wfrn, String findingId, long version, String severity, Instant at) {
        ObjectNode d = data().put("version", version).put("findingId", findingId);
        if (severity != null) {
            d.put("severity", severity);
        }
        if ("FindingClosed".equals(type)) {
            d.put("resolution", "reverted");
        }
        String account = wfrn.split(":")[4];
        EventEnvelope e = EventEnvelope.of("evt_" + type + findingId + version, type + ":" + wfrn + ":" + findingId + ":" + version, type, at, "finding",
                findingId, "drift", d);
        return e.withContext(new EventEnvelope.Context(account, "global", wfrn, null), null);
    }

    /** A resource as the state service serves it. */
    public static ObjectNode stateResource(String wfrn, String name, long version, String state, Map<String, Object> attributes, Map<String, String> tags,
            Instant updatedAt) {
        String resourceType = wfrn.substring(wfrn.lastIndexOf(':') + 1, wfrn.indexOf('/'));
        String account = wfrn.split(":")[4];
        ObjectNode r = Json.object().put("wfrn", wfrn).put("accountId", account).put("region", "global").put("type", resourceType)
                .put("service", wfrn.split(":")[2]).put("name", name).put("state", state).put("version", version)
                .put("createdAt", T0.toString()).put("updatedAt", updatedAt.toString());
        ObjectNode attrs = r.putObject("attributes");
        attributes.forEach((k, v) -> attrs.set(k, Json.MAPPER.valueToTree(v)));
        ObjectNode t = r.putObject("tags");
        tags.forEach(t::put);
        if ("deleted".equals(state)) {
            r.put("deletedAt", updatedAt.toString()).put("tombstoneUntil", updatedAt.plusSeconds(90L * 86400).toString());
        }
        return r;
    }

    public static IndexedResource document(String wfrn, String name, long version, Map<String, String> tags, Instant updatedAt) {
        String resourceType = wfrn.substring(wfrn.lastIndexOf(':') + 1, wfrn.indexOf('/'));
        return IndexedResource.builder().wfrn(wfrn).accountId(wfrn.split(":")[4]).region("global").type(resourceType).service(wfrn.split(":")[2])
                .name(name).state(ResourceState.ACTIVE).version(version).tags(tags).createdAt(T0).updatedAt(updatedAt).build();
    }
}
