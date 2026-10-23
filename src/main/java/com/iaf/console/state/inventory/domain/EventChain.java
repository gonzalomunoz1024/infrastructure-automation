package com.iaf.console.state.inventory.domain;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The hash chain of a resource's log, the scheme of the platform's evidence records applied to the
 * console's own events: an event's {@code hash} is the SHA-256 of its stored form ({@link #body},
 * written by {@link CanonicalJson}), which holds the {@code previous} event's hash. Sensitive values
 * are hashed sealed, so the chain still verifies after an account's key is destroyed. Events
 * written before the chain existed carry no hash; the chain starts at the first event that does.
 */
public final class EventChain {

    private EventChain() {
    }

    /** What an event's hash covers: every stored field but the hash itself. */
    public static ObjectNode body(ResourceEvent e) {
        ObjectNode b = Json.object();
        b.put("id", e.id());
        b.put("type", e.type().wire());
        b.put("wfrn", e.wfrn());
        b.put("accountId", e.accountId());
        b.put("region", e.region());
        b.put("version", e.version());
        b.put("key", e.key());
        b.put("at", e.at().toString());
        b.put("state", e.state().wire());
        Json.putIf(b, "actor", e.actor());
        Json.putIf(b, "jobId", e.jobId());
        Json.putIf(b, "requestId", e.requestId());
        if (e.data() != null) {
            b.set("data", e.data());
        }
        Json.putIf(b, "previous", e.previous());
        return b;
    }

    /** The stored form with its hash, as an evidence pack carries it. */
    public static ObjectNode stored(ResourceEvent e) {
        ObjectNode b = body(e);
        b.put("hash", e.hash() != null ? e.hash() : hash(e));
        return b;
    }

    public static String hash(ResourceEvent e) {
        return CanonicalJson.hash(body(e));
    }

    /** The event linked after the event whose hash is {@code previous} (null for a chain's first event). */
    public static ResourceEvent link(ResourceEvent e, String previous) {
        ResourceEvent linked = e.chained(previous, null);
        return linked.chained(previous, hash(linked));
    }

    /** What checking a log found: {@code brokenAt} is the first version that does not verify. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Check(boolean valid, int entries, int chained, Long brokenAt, String reason, String head) {
    }

    /** Checks a whole log, in version order: consecutive versions, each hash recomputed, each link to the event before. */
    public static Check verify(List<ResourceEvent> log) {
        String previous = null;
        boolean started = false;
        int chained = 0;
        long expected = 1;
        for (ResourceEvent e : log) {
            if (e.version() != expected) {
                return new Check(false, log.size(), chained, expected, "version " + expected + " is missing from the log", previous);
            }
            expected++;
            if (e.hash() == null) {
                if (started) {
                    return new Check(false, log.size(), chained, e.version(), "the event carries no hash, after the chain began", previous);
                }
                continue;
            }
            if (!Objects.equals(e.previous(), previous)) {
                return new Check(false, log.size(), chained, e.version(), "previous is not the hash of the event before", previous);
            }
            if (!hash(e).equals(e.hash())) {
                return new Check(false, log.size(), chained, e.version(), "the hash does not match the event", previous);
            }
            started = true;
            previous = e.hash();
            chained++;
        }
        return new Check(true, log.size(), chained, null, null, previous);
    }
}
