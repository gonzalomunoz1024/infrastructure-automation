package com.iaf.console.drift.boot;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.fake.content.domain.ResourceTypeDef;
import com.iaf.console.fake.registry.domain.ResourceRecord;

/**
 * The demo's starting truth. The fake answers a single provider read (an {@code observe} action
 * such as {@code cluster.get}) from the action's fixed sample, while its paged reads answer the
 * registry's attributes; a seeded database of tier M10 would read as M30 and open a finding before
 * anyone changed anything. For every type without a paged read, the demo writes each resource's
 * recorded values into the fake's world first, so that only a change made later shows as drift.
 */
final class DemoWorld {

    /** An {@code observe.map} expression that copies one field of the provider's answer. */
    private static final Pattern COPY = Pattern.compile("^\\{\\{\\s*\\$json\\.([A-Za-z0-9_]+)\\s*}}$");

    private DemoWorld() {
    }

    /**
     * The sizes of the VMs vSphere has that no account manages (the {@code vm.list} sample, which
     * gives only their power state), so the shadow-infrastructure finder can price them. Answers
     * how many it sized.
     */
    static int sizeUnmanagedVms(PlatformFake fake) {
        int sized = 0;
        for (Object[] vm : new Object[][] {{"vm-1042", 4, 16}, {"vm-1043", 2, 8}, {"vm-2001", 8, 32}}) {
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("provider", "vsphere");
            body.putObject("match").put("id", (String) vm[0]);
            body.putObject("set").put("cpu", (Integer) vm[1]).put("memoryGb", (Integer) vm[2]);
            fake.world().mutate(body);
            sized++;
        }
        return sized;
    }

    /** Answers how many provider objects now read as the registry records them. */
    static int alignSingleReads(PlatformFake fake) {
        int aligned = 0;
        for (ResourceRecord record : fake.registry().all()) {
            if (record.retired()) {
                continue;
            }
            Optional<ResourceTypeDef> type = fake.content().type(record.type());
            if (type.isEmpty() || !type.get().hasObserve()) {
                continue;
            }
            JsonNode observe = type.get().observe();
            if (observe.has("list") || observe.has("discover")) {
                continue;
            }
            String provider = observe.path("provider").asText("");
            String id = record.providerId() != null ? record.providerId() : record.id();
            ObjectNode set = JsonNodeFactory.instance.objectNode();
            ObjectNode attributes = record.attributes();
            for (var entry : observe.path("map").properties()) {
                Matcher m = COPY.matcher(entry.getValue().asText(""));
                JsonNode value = attributes == null ? null : attributes.get(entry.getKey());
                if (m.matches() && value != null && !value.isNull()) {
                    set.set(m.group(1), value.deepCopy());
                }
            }
            if (provider.isBlank() || set.isEmpty()) {
                continue;
            }
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("provider", provider);
            body.putObject("match").put("id", id);
            body.set("set", set);
            fake.world().mutate(body);
            aligned++;
        }
        return aligned;
    }
}
