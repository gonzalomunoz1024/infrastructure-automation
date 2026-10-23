package com.iaf.console.drift.drift.adapters.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.console.drift.TestClock;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.StateResource;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.Snapshot;
import com.iaf.console.platform.model.JobDetail;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.platform.model.Resource;

import reactor.core.publisher.Mono;

/** The demo's state stand-in follows a delete request: deleting until its job ends, then deleted, or active again when the job failed. */
class EmbeddedStateServiceDeleteTest {

    private static final String ACCOUNT = "123456789012";
    private static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

    private final TestClock clock = new TestClock(T0);
    private final PlatformPort platform = mock(PlatformPort.class);
    private EmbeddedStateService state;

    private static String wfrn(String id) {
        return "wfrn:wf:compute:global:" + ACCOUNT + ":vm/" + id;
    }

    private static Resource resource(String id) {
        return Json.MAPPER.convertValue(Map.of("id", id, "wfrn", wfrn(id), "accountId", ACCOUNT, "region", "global", "type", "vm", "name", id,
                "attributes", Map.of("cpu", 2)), Resource.class);
    }

    private void job(String id, JobStatus status, boolean finished) {
        JobDetail job = mock(JobDetail.class);
        when(job.status()).thenReturn(status);
        when(job.finished()).thenReturn(finished);
        when(platform.getJob(id)).thenReturn(Mono.just(job));
    }

    @BeforeEach
    void seed() {
        when(platform.snapshotResources(any(), any(), any())).thenReturn(Mono.just(new Snapshot<>(List.of(resource("vm-a"), resource("vm-b")), null, 1)));
        state = new EmbeddedStateService(platform, clock);
        // as the inventory sync does at the start: the stand-in reads the registry on its first list
        assertThat(state.seed().block()).isEqualTo(2);
    }

    @Test
    void aDeleteRequestIsDeletingUntilItsJobSucceeds() {
        StateResource deleting = state.requestDelete(wfrn("vm-a"), "delete-vm", "idle", "dana", "req-1").block();
        assertThat(deleting.state()).isEqualTo("deleting");
        assertThat(deleting.version()).isEqualTo(2);
        assertThatThrownBy(() -> state.requestDelete(wfrn("vm-a"), "delete-vm", "idle", "dana", "req-2").block())
                .isInstanceOfSatisfying(StateServicePort.StateError.class, e -> assertThat(e.status()).isEqualTo(409));

        state.noteJob(wfrn("vm-a"), "job-1").block();
        job("job-1", JobStatus.WAITING_APPROVAL, false);
        assertThat(state.getResource(wfrn("vm-a")).block().state()).isEqualTo("deleting");

        clock.advance(Duration.ofMinutes(5));
        job("job-1", JobStatus.SUCCEEDED, true);
        StateResource gone = state.getResource(wfrn("vm-a")).block();
        assertThat(gone.deleted()).isTrue();
        assertThat(gone.deletedAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
        assertThat(gone.version()).isEqualTo(3);
    }

    @Test
    void aFailedDeleteJobLeavesTheResourceActive() {
        state.requestDelete(wfrn("vm-b"), "delete-vm", null, "olga", null).block();
        state.noteJob(wfrn("vm-b"), "job-2").block();
        job("job-2", JobStatus.FAILED, true);
        StateResource back = state.getResource(wfrn("vm-b")).block();
        assertThat(back.state()).isEqualTo("active");
        assertThat(back.deletedAt()).isNull();
        assertThatThrownBy(() -> state.requestDelete(wfrn("vm-none"), "delete-vm", null, "olga", null).block())
                .isInstanceOfSatisfying(StateServicePort.StateError.class, e -> assertThat(e.notFound()).isTrue());
    }
}
