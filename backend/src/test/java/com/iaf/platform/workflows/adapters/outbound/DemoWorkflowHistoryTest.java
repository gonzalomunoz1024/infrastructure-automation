package com.iaf.platform.workflows.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.common.demo.DemoReleases;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.adapters.outbound.DemoUserDirectory;
import com.iaf.platform.workflows.SampleFixtures;
import com.iaf.platform.workflows.application.WorkflowCommandHandler;
import com.iaf.platform.workflows.application.WorkflowQueryHandler;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.ports.inbound.WorkflowCommands;

/** The memory store's demo history follows the release stories, and its open change request comes from the real publish. */
class DemoWorkflowHistoryTest {

    static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    InMemoryWorkflowStore store = InMemoryWorkflowStore.empty(clock);
    InMemoryChangeRequests changeRequests = new InMemoryChangeRequests();
    PlatformProperties props = new PlatformProperties();

    @BeforeEach
    void seedTheContent() {
        store.seed(SampleFixtures.ROOT);
    }

    DemoWorkflowHistory history(WorkflowCommands commands) {
        return new DemoWorkflowHistory(store, changeRequests, commands, new DemoUserDirectory(), props, clock);
    }

    @Test
    void keepsTheVersionEachReleaseReplacedAsARetiredVersion() {
        history(mock(WorkflowCommands.class)).seed();

        List<WorkflowRecord> mongo = store.versions("mongodb-upgrade");
        assertThat(mongo).extracting(WorkflowRecord::version).contains("3.0.2", "3.1.0");
        WorkflowRecord previous = store.get("mongodb-upgrade", "3.0.2").orElseThrow();
        assertThat(previous.status()).isEqualTo(WorkflowStatus.RETIRED);
        assertThat(previous.definition().version()).isEqualTo("3.0.2");
        assertThat(previous.yaml()).contains("version: 3.0.2");
        assertThat(previous.publishedAt()).isEqualTo(NOW.minus(Duration.ofDays(8)).minus(DemoWorkflowHistory.PREVIOUS_AGE));
        assertThat(previous.publishedBy()).isEqualTo("ana");
        assertThat(previous.path()).isEqualTo(store.get("mongodb-upgrade", "3.1.0").orElseThrow().path());

        assertThat(store.get("create-vm", "1.9.0")).map(WorkflowRecord::status).contains(WorkflowStatus.RETIRED);
        assertThat(store.get("vm-restart", "1.1.0")).map(WorkflowRecord::status).contains(WorkflowStatus.RETIRED);
        assertThat(store.published("vm-restart")).map(WorkflowRecord::version).contains("1.2.0");
    }

    @Test
    void thePublishedVersionCarriesTheReleaseThatPutItOut() {
        history(mock(WorkflowCommands.class)).seed();

        // the republish is the newest story of mongodb-upgrade
        WorkflowRecord current = store.get("mongodb-upgrade", "3.1.0").orElseThrow();
        assertThat(current.status()).isEqualTo(WorkflowStatus.PUBLISHED);
        assertThat(current.publishedAt()).isEqualTo(NOW.minus(Duration.ofDays(6)));
        assertThat(current.publishedBy()).isEqualTo("ana");
        assertThat(current.releaseId()).isEqualTo("rel_demo_mongo_republish");

        WorkflowRecord vmRestart = store.get("vm-restart", "1.2.0").orElseThrow();
        assertThat(vmRestart.publishedAt()).isEqualTo(NOW.minus(Duration.ofMinutes(80)));
        assertThat(vmRestart.publishedBy()).isEqualTo("mateo");
        assertThat(vmRestart.releaseId()).isEqualTo("rel_demo_vm_restart");
    }

    @Test
    void recordsOneMergedChangeRequestPerReleasedVersion() {
        history(mock(WorkflowCommands.class)).seed();

        List<ChangeRequest> merged = changeRequests.all().stream().filter(cr -> cr.status() == ChangeRequest.Status.MERGED).toList();
        assertThat(merged).extracting(ChangeRequest::name).containsExactlyInAnyOrder("mongodb-upgrade", "create-vm", "vm-restart");
        ChangeRequest mongo = merged.stream().filter(cr -> cr.name().equals("mongodb-upgrade")).findFirst().orElseThrow();
        assertThat(mongo.version()).isEqualTo("3.1.0");
        assertThat(mongo.title()).isEqualTo("Update MongoDB upgrade 3.1.0");
        assertThat(mongo.author()).isEqualTo("ana");
        assertThat(mongo.mergedBy()).isEqualTo("ana");
        assertThat(mongo.mergedAt()).as("merged when its first release started").isEqualTo(NOW.minus(Duration.ofDays(8)));
        assertThat(mongo.createdAt()).isBefore(mongo.mergedAt());
        assertThat(mongo.strategy()).isEqualTo(DemoReleases.CANARY);
        assertThat(mongo.checks()).extracting(Check::id).containsExactly("schema", "checks", "policy", "replay", "diff", "yaml");
        assertThat(mongo.checks()).allMatch(Check::passed);
        assertThat(mongo.diff()).contains("-  version: 3.0.2", "+  version: 3.1.0");
        assertThat(mongo.yaml()).isEqualTo(store.get("mongodb-upgrade", "3.1.0").orElseThrow().yaml());
    }

    @Test
    void seedingTwiceChangesNothing() {
        DemoWorkflowHistory history = history(mock(WorkflowCommands.class));
        history.seed();
        int versions = store.all().size();
        int requests = changeRequests.all().size();
        history.seed();
        assertThat(store.all()).hasSize(versions);
        assertThat(changeRequests.all()).hasSize(requests);
    }

    @Test
    void leavesOneChangeRequestOpenThroughTheRealPublish() {
        EventBus events = new EventBus(clock);
        WorkflowQueryHandler queries = new WorkflowQueryHandler(store, changeRequests, SampleFixtures.providerQueries(), SampleFixtures.resourceQueries(),
                SampleFixtures.credentials());
        WorkflowCommandHandler commands = new WorkflowCommandHandler(store, changeRequests, queries, SampleFixtures.providerQueries(),
                SampleFixtures.resourceQueries(), SampleFixtures.runs(), SampleFixtures.runsQueries(), SampleFixtures.policy(), events, clock);
        commands.init();
        DemoWorkflowHistory history = history(commands);
        history.seed();

        history.openDraft();

        DemoWorkflowHistory.OpenDraft draft = DemoWorkflowHistory.OPEN_DRAFT;
        WorkflowRecord next = store.get(draft.workflow(), draft.version()).orElseThrow();
        assertThat(next.status()).isNotEqualTo(WorkflowStatus.PUBLISHED);
        assertThat(next.definition().metadata().description()).isEqualTo(draft.description());
        assertThat(store.published(draft.workflow())).map(WorkflowRecord::version).as("the current version stays published").contains("1.4.0");
        List<ChangeRequest> open = changeRequests.all().stream().filter(cr -> cr.status() == ChangeRequest.Status.OPEN).toList();
        assertThat(open).singleElement().satisfies(cr -> {
            assertThat(cr.name()).isEqualTo(draft.workflow());
            assertThat(cr.version()).isEqualTo(draft.version());
            assertThat(cr.author()).isEqualTo(draft.author());
            assertThat(cr.checks()).isNotEmpty().allMatch(Check::passed);
            assertThat(cr.diff()).contains("+  version: " + draft.version());
        });

        history.openDraft();
        assertThat(changeRequests.all().stream().filter(cr -> cr.status() == ChangeRequest.Status.OPEN)).as("opened once").hasSize(1);
    }

    @Test
    void doesNothingWhenTheHistoryIsNotWanted() {
        props.getDemo().setSeedHistory(false);
        DemoWorkflowHistory history = history(mock(WorkflowCommands.class));
        history.seed();
        history.openDraft();
        assertThat(store.get("mongodb-upgrade", "3.0.2")).isEmpty();
        assertThat(changeRequests.all()).isEmpty();
    }
}
