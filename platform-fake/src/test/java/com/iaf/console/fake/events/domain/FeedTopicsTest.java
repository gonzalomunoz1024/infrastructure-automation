package com.iaf.console.fake.events.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.iaf.console.fake.common.Json;
import com.iaf.console.kit.events.EventEnvelope;

class FeedTopicsTest {

    private static EventEnvelope event(String type, String aggregate, String aggregateId) {
        return EventEnvelope.of("evt_1", type + ":" + aggregateId + ":1", type, Instant.EPOCH, aggregate, aggregateId, "gonzalo",
                Json.object().put("jobId", "job-9").put("name", "create-vm"));
    }

    @Test
    void eachEventGoesToItsChannelWithItsPartitionKey() {
        assertThat(FeedTopics.route(event("job.started", "job", "job-1"))).contains(new FeedTopics.Target(FeedTopics.JOBS, "job-1"));
        assertThat(FeedTopics.route(event("batch.wave", "batch", "b-1"))).contains(new FeedTopics.Target(FeedTopics.JOBS, "job-9"));
        assertThat(FeedTopics.route(event("resource.registered", "resource", "vm-1"))).contains(new FeedTopics.Target(FeedTopics.RESOURCES, "vm-1"));
        String wfrn = "wfrn:wf:compute:global:123456789012:vm/vm-01abc";
        EventEnvelope named = event("resource.retired", "resource", "vm-1")
                .withContext(new EventEnvelope.Context("123456789012", "global", wfrn, null), null);
        assertThat(FeedTopics.route(named)).contains(new FeedTopics.Target(FeedTopics.RESOURCES, wfrn));
        assertThat(FeedTopics.route(event("audit.sealed", "audit", "job-1"))).contains(new FeedTopics.Target(FeedTopics.AUDIT, "job-1"));
        assertThat(FeedTopics.route(event("workflow.published", "workflow", "create-vm")))
                .contains(new FeedTopics.Target(FeedTopics.CONTENT, "create-vm"));
        assertThat(FeedTopics.route(event("resource-type.changed", "resource-type", "vm"))).contains(new FeedTopics.Target(FeedTopics.CONTENT, "vm"));
        assertThat(FeedTopics.route(event("something.else", "thing", "x"))).isEmpty();
    }
}
