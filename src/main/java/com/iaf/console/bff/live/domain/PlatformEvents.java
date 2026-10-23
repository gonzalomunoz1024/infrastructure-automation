package com.iaf.console.bff.live.domain;

import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.platform.model.FeedEvent;

/**
 * The platform's feed (P-01) as the console's live events: every job event of a console job is a
 * {@code job.updated} for its account, a registry event keeps its type, content and region changes
 * reach every account as {@code types.changed} and {@code region.changed}; audit events stay out.
 */
public final class PlatformEvents {

    /** A live event and the account it is for. */
    public record Mapped(String accountId, LiveEvent event) {
    }

    private PlatformEvents() {
    }

    public static Optional<Mapped> toLive(FeedEvent event) {
        if (event == null || event.type() == null) {
            return Optional.empty();
        }
        String type = event.type();
        Instant at = event.at() == null ? Instant.now() : event.at();
        String accountId = event.context() == null ? null : event.context().accountId();
        String wfrn = event.context() == null ? null : event.context().wfrn();
        if (type.startsWith("job.") || type.startsWith("batch.")) {
            if (accountId == null) {
                return Optional.empty();
            }
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("jobId", event.aggregateId());
            data.put("event", type);
            copy(event.data(), data, "status", "step", "kind", "wave", "approvalId");
            return Optional.of(new Mapped(accountId, new LiveEvent(LiveEvent.JOB_UPDATED, wfrn, at, null, event.key(), data)));
        }
        if (type.startsWith("resource.")) {
            if (accountId == null && event.data() != null) {
                accountId = text(event.data(), "accountId");
            }
            if (wfrn == null && event.data() != null) {
                wfrn = text(event.data(), "wfrn");
            }
            if (accountId == null) {
                return Optional.empty();
            }
            return Optional.of(new Mapped(accountId, new LiveEvent(type, wfrn, at, null, event.key(), event.data())));
        }
        if (type.startsWith("workflow.") || type.startsWith("provider.") || type.startsWith("content.") || type.startsWith("resource-type.")
                || type.startsWith("release.")) {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("event", type);
            data.put("subject", event.aggregateId() == null ? "" : event.aggregateId());
            return Optional.of(new Mapped(Sequenced.EVERY_ACCOUNT, new LiveEvent(LiveEvent.TYPES_CHANGED, null, at, null, event.key(), data)));
        }
        if (type.startsWith("region.")) {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("event", type);
            data.put("region", event.aggregateId() == null ? "" : event.aggregateId());
            return Optional.of(new Mapped(Sequenced.EVERY_ACCOUNT, new LiveEvent(LiveEvent.REGION_CHANGED, null, at, null, event.key(), data)));
        }
        return Optional.empty();
    }

    /** Whether a platform event changes what the descriptors say. */
    public static boolean changesContent(FeedEvent event) {
        String type = event.type() == null ? "" : event.type();
        return type.startsWith("workflow.") || type.startsWith("provider.") || type.startsWith("content.") || type.startsWith("resource-type.")
                || type.startsWith("release.");
    }

    public static boolean changesRegions(FeedEvent event) {
        return event.type() != null && event.type().startsWith("region.");
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        if (from == null || !from.isObject()) {
            return;
        }
        for (String field : fields) {
            JsonNode value = from.get(field);
            if (value != null && !value.isNull() && !to.has(field)) {
                to.set(field, value);
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
