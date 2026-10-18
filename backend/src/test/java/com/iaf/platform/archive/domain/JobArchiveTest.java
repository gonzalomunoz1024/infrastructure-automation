package com.iaf.platform.archive.domain;

import static com.iaf.platform.archive.ArchiveFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.ports.ArtifactStorePort;

class JobArchiveTest {

    static DomainEvent event(String type, String aggregate, String aggregateId, String data) {
        return new DomainEvent("evt_1", Instant.parse("2026-10-18T10:00:00Z"), type, aggregate, aggregateId, "system", json(data));
    }

    @Test
    void theArchiveOfAJobIsNamedAfterItAndAcceptedByTheStore() {
        assertThat(JobArchive.logsName("job_AbC123xyz0")).isEqualTo("jobs/job_AbC123xyz0/logs.ndjson");
        assertThat(JobArchive.planName("job_AbC123xyz0")).isEqualTo("jobs/job_AbC123xyz0/plan.json");
        assertThat(ArtifactStorePort.name(JobArchive.logsName("job_AbC123xyz0"))).isNotNull();
        assertThat(ArtifactStorePort.name(JobArchive.planName("job_AbC123xyz0"))).isNotNull();
    }

    @Test
    void onlyTheEventsThatEndAJobEndOne() {
        assertThat(JobArchive.endedJob(event("job.finished", "job", "j1", "{\"jobId\":\"j1\",\"status\":\"failed\"}"))).contains("j1");
        assertThat(JobArchive.endedJob(event("job.cancelled", "job", "j2", "{\"jobId\":\"j2\",\"by\":\"ana\"}"))).contains("j2");
        assertThat(JobArchive.endedJob(event("job.finished", "job", null, "{\"jobId\":\"j3\"}"))).as("the id from the data").contains("j3");

        for (String type : List.of("job.started", "job.step", "job.log", "job.attention", "job.approval-requested", "job.change-closed", "batch.wave")) {
            assertThat(JobArchive.endedJob(event(type, "job", "j1", "{\"jobId\":\"j1\"}"))).as(type).isEmpty();
        }
        assertThat(JobArchive.endedJob(event("job.finished", "release", "r1", "{}"))).as("another aggregate").isEmpty();
        assertThat(JobArchive.endedJob(event("job.finished", "job", " ", "{}"))).as("no id").isEmpty();
        assertThat(JobArchive.endedJob(null)).isEmpty();
    }

    @Test
    void aJobHasEndedInEachTerminalStatusAndNoOther() {
        for (String status : List.of("succeeded", "failed", "cancelled", "rolled-back")) {
            assertThat(JobArchive.ended(json("{\"status\":\"" + status + "\"}"))).as(status).isTrue();
        }
        for (String status : List.of("queued", "running", "waiting-approval", "needs-attention")) {
            assertThat(JobArchive.ended(json("{\"status\":\"" + status + "\"}"))).as(status).isFalse();
        }
        assertThat(JobArchive.ended(json("{}"))).isFalse();
        assertThat(JobArchive.ended(null)).isFalse();
    }

    @Test
    void thePreviewTheLogLinesAndTheEndedChildrenComeFromTheApiShapes() {
        JsonNode detail = json("""
                {"id":"p1","status":"succeeded","preview":{"changes":[{"target":"vm-1"}]},
                 "children":[{"id":"c1","status":"succeeded"},{"id":"c2","status":"running"},{"id":"c3","status":"cancelled"},{"status":"failed"}]}""");

        assertThat(JobArchive.preview(detail)).contains(json("{\"changes\":[{\"target\":\"vm-1\"}]}"));
        assertThat(JobArchive.preview(json("{\"id\":\"p1\"}"))).isEmpty();
        assertThat(JobArchive.preview(json("{\"preview\":null}"))).isEmpty();
        assertThat(JobArchive.endedChildren(detail)).containsExactly("c1", "c3");
        assertThat(JobArchive.endedChildren(json("{\"id\":\"p1\"}"))).isEmpty();
        assertThat(JobArchive.isLog(json("{\"type\":\"log\",\"data\":{\"message\":\"x\"}}"))).isTrue();
        assertThat(JobArchive.isLog(json("{\"type\":\"step\"}"))).isFalse();
    }

    @Test
    void ndjsonIsOneCompactDocumentPerLineAndReadsBack() {
        List<JsonNode> lines = List.of(
                json("{\"seq\":3,\"jobId\":\"j1\",\"type\":\"log\",\"data\":{\"message\":\"PLAY [restart]\"}}"),
                json("{\"seq\":5,\"jobId\":\"j1\",\"type\":\"log\",\"data\":{\"message\":\"multi\\nline\"}}"));

        byte[] archive = JobArchive.ndjson(lines);

        assertThat(new String(archive, StandardCharsets.UTF_8)).isEqualTo(
                "{\"seq\":3,\"jobId\":\"j1\",\"type\":\"log\",\"data\":{\"message\":\"PLAY [restart]\"}}\n"
                        + "{\"seq\":5,\"jobId\":\"j1\",\"type\":\"log\",\"data\":{\"message\":\"multi\\nline\"}}\n");
        assertThat(JobArchive.lines(archive)).isEqualTo(lines);
        assertThat(JobArchive.ndjson(List.of())).isEmpty();
        assertThat(JobArchive.lines(new byte[0])).isEmpty();
        assertThat(JobArchive.parse(JobArchive.json(lines.get(0)))).isEqualTo(lines.get(0));
    }

    @Test
    void anArchiveLineThatIsNotJsonIsRefused() {
        assertThatThrownBy(() -> JobArchive.lines("{\"seq\":1}\nnot json\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("line 2");
    }
}
