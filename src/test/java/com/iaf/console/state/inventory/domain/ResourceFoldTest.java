package com.iaf.console.state.inventory.domain;

import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static com.iaf.console.state.inventory.domain.Fixtures.t;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The fold: a resource is its events, and a version in the past is the fold up to it. */
class ResourceFoldTest {

    private static List<ResourceEvent> aWholeLife() {
        List<ResourceEvent> log = new ArrayList<>();
        ResourceIdentity id = Fixtures.vm();
        Resource r = null;
        log.add(ResourceDecider.mint(id, new Commands.Mint("vm", "global", "web-03", "create-vm", Fixtures.input("web-03"), by("dana")), t(0)));
        r = ResourceFold.apply(r, log.get(log.size() - 1));
        log.add(ResourceDecider.jobStarted(r, new Commands.JobStarted("job_1", "create-vm", "2.0.1", by("platform")), t(1)).orElseThrow());
        r = ResourceFold.apply(r, log.get(log.size() - 1));
        log.add(ResourceDecider.registered(r, new Commands.Registration("job_1", "create-vm", "2.0.1", "vm-7", "vm-7", "web-03",
                Fixtures.attributes("web-03", 2, 4), by("platform")), t(2)).orElseThrow());
        r = ResourceFold.apply(r, log.get(log.size() - 1));
        log.add(ResourceDecider.tag(r, new Commands.Tag(Map.of("env", "prod"), null, by("dana")), t(3)).orElseThrow());
        r = ResourceFold.apply(r, log.get(log.size() - 1));
        log.add(ResourceDecider.requestDelete(r, new Commands.RequestDelete("job_2", "delete-vm", null, null, by("dana")), t(4)));
        r = ResourceFold.apply(r, log.get(log.size() - 1));
        log.add(ResourceDecider.deleted(r, new Commands.Deletion("job_2", "vm-7", by("platform")), t(5), Duration.ofDays(30)).orElseThrow());
        return log;
    }

    @Test
    void replayGivesTheLastStateAndReplayUntilAnOlderOne() {
        List<ResourceEvent> log = aWholeLife();
        assertThat(log).extracting(ResourceEvent::version).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(log).extracting(ResourceEvent::state).containsExactly(ResourceState.PENDING, ResourceState.PENDING, ResourceState.ACTIVE,
                ResourceState.ACTIVE, ResourceState.DELETING, ResourceState.DELETED);
        Resource last = ResourceFold.replay(log);
        assertThat(last.state()).isEqualTo(ResourceState.DELETED);
        assertThat(last.version()).isEqualTo(6);
        assertThat(last.tags()).containsEntry("env", "prod");
        Resource asOf3 = ResourceFold.replayUntil(log, 3);
        assertThat(asOf3.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(asOf3.tags()).isEmpty();
        assertThat(asOf3.version()).isEqualTo(3);
    }

    @Test
    void keysAreDeterministic() {
        List<ResourceEvent> log = aWholeLife();
        ResourceEvent first = log.get(0);
        assertThat(first.key()).isEqualTo("ResourceRequested:" + first.wfrn() + ":1");
        assertThat(log.get(5).key()).isEqualTo("Deleted:" + first.wfrn() + ":6");
    }

    @Test
    void aGapInTheVersionsIsRefused() {
        List<ResourceEvent> log = aWholeLife();
        Resource one = ResourceFold.apply(null, log.get(0));
        assertThatThrownBy(() -> ResourceFold.apply(one, log.get(2))).isInstanceOf(IllegalStateException.class);
    }
}
