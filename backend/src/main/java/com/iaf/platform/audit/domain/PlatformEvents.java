package com.iaf.platform.audit.domain;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Which commands that are not about a job go on the platform audit stream, and what their entry
 * says: the event type, the aggregate it changed, and the event's own fields, except that a file
 * (a workflow or provider YAML) is recorded as its SHA-256 and a credential only by name and type.
 * Pure, no Spring.
 */
public final class PlatformEvents {

    /** Drafts, change requests, publishes, provider changes, credentials, service accounts, release decisions, builder sessions. */
    public static final Set<String> RECORDED = Set.of(
            "workflow.draft-saved", "workflow.deleted", "workflow.published",
            "change-request.opened", "change-request.merged", "change-request.closed",
            "provider.change-requested", "provider.published", "provider.updated",
            "credential.stored", "service-account.created",
            "release.promoted", "release.paused", "release.resumed", "release.rolled-back",
            "assistant.started", "resource.retired");

    public static final String WORKFLOW_PUBLISHED = "workflow.published";
    public static final String CHANGE_REQUEST_MERGED = "change-request.merged";

    private static final Set<String> FILE_FIELDS = Set.of("yaml", "draftYaml", "content");
    private static final Set<String> CREDENTIAL_FIELDS = Set.of("name", "type", "by");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private PlatformEvents() {
    }

    public static boolean recorded(String type) {
        return type != null && RECORDED.contains(type);
    }

    /** The entry data of one platform event. */
    public static ObjectNode entry(String type, String aggregate, String aggregateId, JsonNode data) {
        ObjectNode out = F.objectNode();
        out.put("event", type);
        if (aggregate != null) {
            out.put("aggregate", aggregate);
        }
        if (aggregateId != null) {
            out.put("aggregateId", aggregateId);
        }
        if (data == null || !data.isObject()) {
            return out;
        }
        boolean credential = type.startsWith("credential.");
        for (Map.Entry<String, JsonNode> f : data.properties()) {
            String key = f.getKey();
            if (out.has(key) || (credential && !CREDENTIAL_FIELDS.contains(key))) {
                continue;
            }
            if (FILE_FIELDS.contains(key)) {
                if (f.getValue() != null && f.getValue().isTextual()) {
                    out.put(key + "Sha256", Signatures.sha256Hex(f.getValue().asText().getBytes(StandardCharsets.UTF_8)));
                }
                continue;
            }
            out.set(key, f.getValue());
        }
        return out;
    }

    /** {@code name@version} of a workflow publish or a merged change request, or null. */
    public static String versionKey(JsonNode entryData) {
        if (entryData == null) {
            return null;
        }
        String name = entryData.path("name").asText(null);
        String version = entryData.path("version").asText(null);
        return name == null || version == null ? null : name + "@" + version;
    }
}
