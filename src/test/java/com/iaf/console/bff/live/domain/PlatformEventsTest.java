package com.iaf.console.bff.live.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.platform.model.FeedEvent;
import com.iaf.console.platform.model.JobContext;

class PlatformEventsTest {

    private static final Instant AT = Instant.parse("2026-10-23T10:00:00Z");

    @Test
    void jobEventsBecomeJobUpdatesOfTheirAccount() {
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("status", "running").put("step", "restart");
        FeedEvent event = feed("job.step.started", "job", "job_1", new JobContext("123456789012", "global", "wfrn:wf:compute:global:123456789012:vm/vm-1",
                "req_1", Map.of()), data);
        Optional<PlatformEvents.Mapped> mapped = PlatformEvents.toLive(event);
        assertTrue(mapped.isPresent());
        assertEquals("123456789012", mapped.get().accountId());
        LiveEvent live = mapped.get().event();
        assertEquals(LiveEvent.JOB_UPDATED, live.type());
        assertEquals("wfrn:wf:compute:global:123456789012:vm/vm-1", live.wfrn());
        assertEquals("job_1", live.data().path("jobId").asText());
        assertEquals("job.step.started", live.data().path("event").asText());
        assertEquals("running", live.data().path("status").asText());
        assertEquals("restart", live.data().path("step").asText());
        assertEquals(AT, live.at());
        assertFalse(PlatformEvents.changesContent(event));
    }

    @Test
    void jobEventsWithoutAnAccountAreDropped() {
        assertTrue(PlatformEvents.toLive(feed("job.succeeded", "job", "job_2", null, null)).isEmpty());
        assertTrue(PlatformEvents.toLive(feed("audit.row", "audit", "x", null, null)).isEmpty());
    }

    @Test
    void contentAndRegionChangesAreBroadcast() {
        FeedEvent published = feed("workflow.published", "workflow", "vm-restart", null, null);
        PlatformEvents.Mapped mapped = PlatformEvents.toLive(published).orElseThrow();
        assertEquals(Sequenced.EVERY_ACCOUNT, mapped.accountId());
        assertEquals(LiveEvent.TYPES_CHANGED, mapped.event().type());
        assertEquals("vm-restart", mapped.event().data().path("subject").asText());
        assertTrue(PlatformEvents.changesContent(published));
        FeedEvent region = feed("region.changed", "region", "global", null, null);
        assertEquals(LiveEvent.REGION_CHANGED, PlatformEvents.toLive(region).orElseThrow().event().type());
        assertTrue(PlatformEvents.changesRegions(region));
        assertFalse(PlatformEvents.changesRegions(published));
    }

    @Test
    void resourceEventsKeepTheirTypeAndFindTheAccountInTheData() {
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("accountId", "210987654321").put("wfrn", "wfrn:wf:data:global:210987654321:database/db-1");
        PlatformEvents.Mapped mapped = PlatformEvents.toLive(feed("resource.registered", "resource", "db-1", null, data)).orElseThrow();
        assertEquals("210987654321", mapped.accountId());
        assertEquals("resource.registered", mapped.event().type());
        assertEquals("wfrn:wf:data:global:210987654321:database/db-1", mapped.event().wfrn());
    }

    private static FeedEvent feed(String type, String aggregate, String aggregateId, JobContext context, JsonNode data) {
        return new FeedEvent("1.0", 1, "evt_" + aggregateId, type + ":" + aggregateId, 7, type, AT, aggregate, aggregateId, "sa-console", null, context, null, data);
    }
}
