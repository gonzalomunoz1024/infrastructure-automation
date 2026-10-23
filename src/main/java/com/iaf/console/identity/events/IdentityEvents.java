package com.iaf.console.identity.events;

import java.time.Clock;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.outbox.OutboxPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Appends the {@code identity.changes} events to the outbox in the hub's shape: the platform's
 * envelope with {@code aggregate} the subject kind, {@code aggregateId} the subject id,
 * {@code context.accountId} the account, and {@code data} = {@code {version, action, snapshot}}
 * where the snapshot is the subject as this service serves it, without secrets, absent on a
 * delete. The key is {@code <type>:<accountId>:<subjectId>:<version>}; the Kafka record key is
 * the account, so one account's changes stay in order. Every listener runs after the append.
 */
public final class IdentityEvents {

    public static final String TOPIC = "identity.changes";

    /** The subject kinds and their event types. */
    public enum Subject {
        ACCOUNT("account", "AccountChanged"),
        USER("user", "UserChanged"),
        GROUP("group", "GroupChanged"),
        ROLE("role", "RoleChanged"),
        POLICY("policy", "PolicyChanged"),
        ACCESS_KEY("accessKey", "AccessKeyChanged"),
        QUOTA("quota", "QuotaChanged"),
        ACCESS_REQUEST("access-request", "AccessRequestChanged"),
        GUARDRAIL("guardrail", "GuardrailChanged");

        private final String aggregate;
        private final String type;

        Subject(String aggregate, String type) {
            this.aggregate = aggregate;
            this.type = type;
        }

        public String aggregate() {
            return aggregate;
        }

        public String type() {
            return type;
        }
    }

    private final OutboxPort outbox;
    private final Ids ids;
    private final Clock clock;
    private final ObjectMapper json;
    private final List<IdentityChangeListener> listeners;

    public IdentityEvents(OutboxPort outbox, Ids ids, Clock clock, ObjectMapper json, List<IdentityChangeListener> listeners) {
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
        this.listeners = List.copyOf(listeners);
    }

    /** Appends one change; {@code snapshot} is the subject after the change, null on a delete. */
    public Mono<Void> changed(Subject subject, String subjectId, String accountId, String actor, long version, String action, Object snapshot) {
        return Mono.deferContextual(ctx -> {
            ObjectNode data = json.createObjectNode();
            data.put("version", version);
            data.put("action", action);
            if (snapshot != null) {
                data.set("snapshot", json.valueToTree(snapshot));
            }
            String key = subject.type() + ":" + accountId + ":" + subjectId + ":" + version;
            EventEnvelope envelope = EventEnvelope.of(ids.event(), key, subject.type(), clock.instant(), subject.aggregate(), subjectId, actor, data)
                    .withContext(new EventEnvelope.Context(accountId, null, null, Correlation.id(ctx).orElse(null)), null)
                    .withTraceparent(Correlation.traceparent(ctx).orElse(null));
            IdentityChange change = new IdentityChange(subject.type(), subject.aggregate(), subjectId, accountId, version, action);
            return outbox.append(TOPIC, accountId, envelope)
                    .then(Flux.fromIterable(listeners).concatMap(l -> l.changed(change)).then());
        });
    }
}
