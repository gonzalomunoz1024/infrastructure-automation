package com.iaf.console.drift.drift.application;

import java.time.Clock;
import java.util.Locale;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.wfrn.Ulid;

import reactor.core.publisher.Mono;

/**
 * The console's drift events on {@code drift.findings} (hub decision 17, {@code contracts/asyncapi/console-events.yaml}):
 * the platform's envelope, aggregate {@code finding}, the finding's id as the aggregate id, the
 * account, region and wfrn in the context, the console's fields in {@code data}, keyed
 * {@code <Type>:<wfrn>:<findingId>:<version>} and partitioned by wfrn, through the kit's outbox.
 */
public final class FindingEvents {

    public static final String TOPIC = "drift.findings";
    public static final String OPENED = "FindingOpened";
    public static final String UPDATED = "FindingUpdated";
    public static final String CLOSED = "FindingClosed";
    public static final String ACTOR = "console-drift-service";

    private final OutboxPort outbox;
    private final Clock clock;

    public FindingEvents(OutboxPort outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    public Mono<Void> opened(Finding f) {
        return emit(OPENED, f);
    }

    public Mono<Void> updated(Finding f) {
        return emit(UPDATED, f);
    }

    public Mono<Void> closed(Finding f) {
        return emit(CLOSED, f);
    }

    private Mono<Void> emit(String type, Finding f) {
        ObjectNode data = Json.object();
        data.put("version", f.version());
        data.put("findingId", f.findingId());
        data.put("severity", f.severity().id());
        data.set("fields", Json.node(f.fields()));
        if (f.observedAt() != null) {
            data.put("observedAt", f.observedAt().toString());
        }
        data.put("type", f.type());
        if (CLOSED.equals(type) && f.resolution() != null) {
            data.put("resolution", f.resolution().id());
            if (f.decidedBy() != null) {
                data.put("by", f.decidedBy());
            }
            if (f.revertJobId() != null) {
                data.put("jobId", f.revertJobId());
            }
        }
        String key = type + ":" + f.wfrn() + ":" + f.findingId() + ":" + f.version();
        EventEnvelope event = EventEnvelope.of("evt_" + Ulid.next().toLowerCase(Locale.ROOT), key, type, clock.instant(), "finding", f.findingId(),
                f.decidedBy() != null ? f.decidedBy() : ACTOR, data)
                .withContext(new EventEnvelope.Context(f.accountId(), f.region(), f.wfrn(), null), null);
        return outbox.append(TOPIC, f.wfrn(), event).then();
    }
}
