package com.iaf.console.fake.content.domain;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A region (P-07): its partition, status, environments and whether it is the default. */
public record RegionDef(
        String name,
        String title,
        String description,
        String partition,
        String status,
        List<String> environments,
        boolean isDefault,
        String hash) {

    /** The region as GET /api/v1/regions/{name} answers it. */
    public ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("name", name);
        out.put("title", title);
        if (description != null) {
            out.put("description", description);
        }
        out.put("partition", partition);
        out.put("status", status);
        var envs = out.putArray("environments");
        environments.forEach(envs::add);
        out.put("default", isDefault);
        out.put("hash", hash);
        return out;
    }

    /** What a region's observe reads see as {@code $region}: its name and its first environment. */
    public ObjectNode scope(String environment) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("name", name);
        out.put("partition", partition);
        out.put("environment", environment != null ? environment : environments.isEmpty() ? "dev" : environments.get(0));
        return out;
    }

    public boolean hasEnvironment(String environment) {
        return environment == null || environments.contains(environment);
    }

    /** A region from its YAML document, hashed over the document itself. */
    public static RegionDef of(JsonNode document, boolean isDefault, String hash) {
        JsonNode metadata = document.path("metadata");
        JsonNode spec = document.path("spec");
        List<String> envs = new java.util.ArrayList<>();
        for (JsonNode env : spec.path("environments")) {
            envs.add(env.asText());
        }
        return new RegionDef(
                metadata.path("name").asText(),
                metadata.path("title").asText(metadata.path("name").asText()),
                metadata.path("description").asText(null),
                spec.path("partition").asText("wf"),
                spec.path("status").asText("available"),
                List.copyOf(envs),
                isDefault,
                hash);
    }
}
