package com.iaf.platform.releases.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.releases.adapters.outbound.InMemoryReleaseStore;
import com.iaf.platform.releases.domain.Release;

import reactor.test.StepVerifier;

class ReleaseQueryHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final List<Integer> STAGES = List.of(10, 50, 100);

    final InMemoryReleaseStore store = new InMemoryReleaseStore();
    final ReleaseQueryHandler handler = new ReleaseQueryHandler(store);

    @Test
    void routesThePublishedVersionWhenNoReleaseIsActive() {
        store.save(Release.canary("rel_done", "vm-restart", "1.1.0", "1.2.0", STAGES, NOW, "ana").promoted(NOW, "promoted", "ana"));

        StepVerifier.create(handler.route("vm-restart", "1.2.0")).expectNext("1.2.0").verifyComplete();
        StepVerifier.create(handler.route("create-vm", "2.0.1")).expectNext("2.0.1").verifyComplete();
    }

    @Test
    void sendsTheStagePercentOfNewJobsToTheCandidateEvenlySpread() {
        store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, NOW, "ana"));

        List<String> routed = routeMany("vm-restart", "1.1.0", 100);

        assertThat(routed).filteredOn("1.2.0"::equals).hasSize(10);
        assertThat(routed.get(0)).as("the first job after the canary starts runs the candidate").isEqualTo("1.2.0");
        for (int n = 0; n < 100; n++) {
            assertThat(routed.get(n)).as("job " + n).isEqualTo(n % 10 == 0 ? "1.2.0" : "1.1.0");
        }
    }

    @Test
    void alternatesAtFiftyPercent() {
        store.save(new Release("rel_1", "vm-restart", "1.1.0", "1.2.0", "canary", com.iaf.platform.releases.domain.ReleaseStatus.CANARY, 1, STAGES, 50,
                NOW, NOW, null, List.of(), Release.Jobs.NONE, List.of()));

        List<String> routed = routeMany("vm-restart", "1.1.0", 10);

        assertThat(routed).containsExactly("1.2.0", "1.1.0", "1.2.0", "1.1.0", "1.2.0", "1.1.0", "1.2.0", "1.1.0", "1.2.0", "1.1.0");
    }

    @Test
    void sendsEverythingToTheCurrentVersionWhilePaused() {
        store.save(Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", STAGES, NOW, "ana").paused(NOW, "ana"));

        assertThat(routeMany("vm-restart", "1.2.0", 20)).containsOnly("1.1.0");
    }

    @Test
    void theSpreadIsExactForEveryPercent() {
        for (int percent = 0; percent <= 100; percent++) {
            int candidates = 0;
            for (long n = 37; n < 137; n++) {
                if (ReleaseQueryHandler.candidate(n, percent)) {
                    candidates++;
                }
            }
            assertThat(candidates).as("candidates in 100 jobs at " + percent + "%").isEqualTo(percent);
        }
    }

    @Test
    void listsNewestFirstAndFiltersByWorkflow() {
        store.save(Release.canary("rel_old", "vm-restart", "1.0.0", "1.1.0", STAGES, NOW.minusSeconds(3600), "ana").promoted(NOW, "promoted", "ana"));
        store.save(Release.canary("rel_new", "vm-restart", "1.1.0", "1.2.0", STAGES, NOW, "ana"));
        store.save(Release.canary("rel_other", "create-vm", "1.9.0", "2.0.1", STAGES, NOW, "ana"));

        StepVerifier.create(handler.list("vm-restart").map(Release::id)).expectNext("rel_new", "rel_old").verifyComplete();
        StepVerifier.create(handler.list(null)).expectNextCount(3).verifyComplete();
    }

    @Test
    void getIsNotFoundForAnUnknownId() {
        StepVerifier.create(handler.get("rel_missing")).expectError(NotFoundException.class).verify();
    }

    private List<String> routeMany(String workflow, String published, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(handler.route(workflow, published).block());
        }
        return out;
    }
}
