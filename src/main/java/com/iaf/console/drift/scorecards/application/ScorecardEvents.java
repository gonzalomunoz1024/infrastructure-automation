package com.iaf.console.drift.scorecards.application;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.scorecards.domain.ResourceEvaluation;
import com.iaf.console.drift.scorecards.domain.ScoreSnapshot;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.wfrn.Ulid;

import reactor.core.publisher.Mono;

/**
 * The compliance events on {@code drift.scorecards} (contracts/asyncapi/console-events.yaml):
 * {@code EvaluationChanged} when a resource's rule statuses change (aggregate {@code evaluation},
 * the wfrn), {@code ScoreSnapshotted} when an account's daily score is recorded (aggregate
 * {@code scorecard}, the account), both partitioned by account through the kit's outbox.
 */
public final class ScorecardEvents {

    public static final String TOPIC = "drift.scorecards";
    public static final String EVALUATION_CHANGED = "EvaluationChanged";
    public static final String SCORE_SNAPSHOTTED = "ScoreSnapshotted";
    static final String ACTOR = "console-drift-service";

    private final OutboxPort outbox;
    private final Clock clock;

    public ScorecardEvents(OutboxPort outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    public Mono<Void> evaluationChanged(ResourceEvaluation e, List<ResourceEvaluation.Change> changes) {
        ObjectNode data = Json.object();
        data.put("version", e.version());
        data.put("type", e.type());
        data.put("passed", e.passed());
        data.put("failed", e.failed());
        ArrayNode list = data.putArray("changes");
        for (ResourceEvaluation.Change c : changes) {
            ObjectNode item = list.addObject();
            item.put("pack", c.key().pack());
            item.put("ruleId", c.key().rule());
            item.put("severity", c.severity());
            item.put("from", c.from());
            item.put("to", c.to());
            if (c.message() != null) {
                item.put("message", c.message());
            }
        }
        String key = EVALUATION_CHANGED + ":" + e.wfrn() + ":" + e.version();
        EventEnvelope event = EventEnvelope.of(id(), key, EVALUATION_CHANGED, clock.instant(), "evaluation", e.wfrn(), ACTOR, data)
                .withContext(new EventEnvelope.Context(e.accountId(), e.region(), e.wfrn(), null), null);
        return outbox.append(TOPIC, e.accountId(), event).then();
    }

    public Mono<Void> scoreSnapshotted(ScoreSnapshot s, Integer previousScore) {
        ObjectNode data = Json.object();
        data.put("version", s.day().toEpochDay() + 1);
        data.put("day", s.day().toString());
        if (s.score() == null) {
            data.putNull("score");
        } else {
            data.put("score", s.score());
        }
        if (previousScore != null) {
            data.put("previousScore", previousScore);
        }
        ObjectNode packs = data.putObject("packs");
        s.packs().forEach((name, score) -> {
            if (score == null) {
                packs.putNull(name);
            } else {
                packs.put(name, score);
            }
        });
        data.put("failed", s.failed());
        String key = SCORE_SNAPSHOTTED + ":" + s.accountId() + ":" + s.day();
        EventEnvelope event = EventEnvelope.of(id(), key, SCORE_SNAPSHOTTED, clock.instant(), "scorecard", s.accountId(), ACTOR, data)
                .withContext(new EventEnvelope.Context(s.accountId(), null, null, null), null);
        return outbox.append(TOPIC, s.accountId(), event).then();
    }

    private static String id() {
        return "evt_" + Ulid.next().toLowerCase(Locale.ROOT);
    }
}
