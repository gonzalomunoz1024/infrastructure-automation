package com.iaf.console.cost.budgets.application;

import java.time.Instant;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.cost.budgets.domain.Budget;
import com.iaf.console.cost.budgets.domain.BudgetPeriod;
import com.iaf.console.cost.budgets.domain.BudgetRules;
import com.iaf.console.cost.pricing.domain.Micros;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.wfrn.Ulid;

import reactor.core.publisher.Mono;

/** The budget events of {@code cost.events}, appended to the outbox; their keys make a repeat a no-op. */
public final class BudgetEvents {

    public static final String TOPIC = "cost.events";
    public static final String REGION = "global";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OutboxPort outbox;

    public BudgetEvents(OutboxPort outbox) {
        this.outbox = outbox;
    }

    public Mono<Void> changed(Budget b, String action, String actor, Instant at) {
        ObjectNode data = JSON.createObjectNode().put("version", b.version()).put("budgetId", b.budgetId()).put("action", action)
                .put("name", b.name()).put("amount", Micros.toCents(b.amountMicros())).put("currency", b.currency()).put("blockCreates", b.blockCreates());
        b.thresholds().forEach(data.putArray("thresholds")::add);
        if (b.scope().tag() != null) {
            data.putObject("tag").put("key", b.scope().tag().key()).put("value", b.scope().tag().value());
        }
        long version = "deleted".equals(action) ? b.version() + 1 : b.version();
        String key = "BudgetChanged:" + b.accountId() + ":" + b.budgetId() + ":" + version;
        return append(b, key, "BudgetChanged", actor, at, data);
    }

    public Mono<Void> crossed(Budget b, BudgetPeriod p, int threshold, Instant at) {
        ObjectNode data = JSON.createObjectNode().put("version", b.version()).put("budgetId", b.budgetId()).put("name", b.name())
                .put("period", p.period()).put("threshold", threshold).put("percent", BudgetRules.percent(p.spentMicros(), p.amountMicros()))
                .put("spent", Micros.toCents(p.spentMicros())).put("amount", Micros.toCents(p.amountMicros()))
                .put("forecast", Micros.toCents(p.forecastMicros())).put("currency", b.currency())
                .put("blockCreates", b.blockCreates() && p.spentMicros() >= p.amountMicros());
        String key = "BudgetThresholdCrossed:" + b.accountId() + ":" + b.budgetId() + ":" + p.period() + "-" + threshold;
        return append(b, key, "BudgetThresholdCrossed", BudgetsService.SELF, at, data);
    }

    private Mono<Void> append(Budget b, String key, String type, String actor, Instant at, ObjectNode data) {
        EventEnvelope event = EventEnvelope.of("evt_" + Ulid.next().toLowerCase(Locale.ROOT), key, type, at, "budget", b.budgetId(), actor, data)
                .withContext(new EventEnvelope.Context(b.accountId(), REGION, null, null), null);
        return outbox.append(TOPIC, b.accountId(), event).then();
    }
}
