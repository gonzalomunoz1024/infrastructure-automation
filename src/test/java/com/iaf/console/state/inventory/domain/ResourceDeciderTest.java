package com.iaf.console.state.inventory.domain;

import static com.iaf.console.state.inventory.domain.Fixtures.ACCOUNT;
import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static com.iaf.console.state.inventory.domain.Fixtures.t;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;

/** The state machine and the decider: what each command does in each state, and what it refuses. */
class ResourceDeciderTest {

    private static final Duration TOMBSTONE = Duration.ofDays(90);

    private static Resource pending() {
        ResourceIdentity id = Fixtures.vm();
        ResourceEvent requested = ResourceDecider.mint(id, new Commands.Mint("vm", "global", "web-03", "create-vm", Fixtures.input("web-03"), by("dana")),
                t(0));
        return ResourceFold.apply(null, requested);
    }

    private static Resource active() {
        Resource p = pending();
        ResourceEvent started = ResourceDecider.jobStarted(p, new Commands.JobStarted("job_1", "create-vm", "2.0.1", by("platform")), t(1)).orElseThrow();
        Resource s = ResourceFold.apply(p, started);
        ResourceEvent registered = ResourceDecider.registered(s, new Commands.Registration("job_1", "create-vm", "2.0.1", "vm-7", "vm-7", "web-03",
                Fixtures.attributes("web-03", 2, 4), by("platform")), t(2)).orElseThrow();
        return ResourceFold.apply(s, registered);
    }

    private static Resource failed() {
        Resource p = pending();
        Resource s = ResourceFold.apply(p, ResourceDecider.jobStarted(p, new Commands.JobStarted("job_1", "create-vm", "2.0.1", by("platform")), t(1))
                .orElseThrow());
        return ResourceFold.apply(s, ResourceDecider.jobFailed(s, new Commands.JobFailed("job_1", "create-vm", "2.0.1", "failed", "provider.quota",
                "createVm", "quota exceeded", by("platform")), t(5)));
    }

    @Test
    void aMintOpensAPendingResourceAtVersionOne() {
        Resource r = pending();
        assertThat(r.state()).isEqualTo(ResourceState.PENDING);
        assertThat(r.version()).isEqualTo(1);
        assertThat(r.accountId()).isEqualTo(ACCOUNT);
        assertThat(r.type()).isEqualTo("vm");
        assertThat(r.service()).isEqualTo("compute");
        assertThat(r.wfrn()).startsWith("wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-");
        assertThat(r.requestedBy()).isEqualTo("dana");
        assertThat(r.attributes().get("name").asText()).isEqualTo("web-03");
    }

    @Test
    void theHappyPathIsRequestedStartedSucceeded() {
        Resource r = active();
        assertThat(r.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(r.version()).isEqualTo(3);
        assertThat(r.registryId()).isEqualTo("vm-7");
        assertThat(r.lastJobId()).isEqualTo("job_1");
        assertThat(r.attributes().get("cpu").asInt()).isEqualTo(2);
    }

    @Test
    void aFailedJobMakesAPendingResourceFailedAndEndsAnUpdateOrADelete() {
        Resource p = pending();
        ResourceEvent failed = ResourceDecider.jobFailed(p, new Commands.JobFailed("job_1", "create-vm", null, "failed", "provider.quota", "createVm",
                "quota exceeded", by("platform")), t(5));
        Resource f = ResourceFold.apply(p, failed);
        assertThat(f.state()).isEqualTo(ResourceState.FAILED);
        assertThat(f.lastError().errorClass()).isEqualTo("provider.quota");

        Resource a = active();
        ObjectNode changes = Json.object().put("cpu", 4);
        Resource updating = ResourceFold.apply(a, ResourceDecider.requestUpdate(a, new Commands.RequestUpdate("job_2", "update-vm", null, changes, by("dana")), t(6)));
        assertThat(updating.state()).isEqualTo(ResourceState.UPDATING);
        assertThat(updating.changes().get("cpu").asInt()).isEqualTo(4);
        Resource back = ResourceFold.apply(updating, ResourceDecider.jobFailed(updating, new Commands.JobFailed("job_2", "update-vm", null, "cancelled",
                null, null, null, by("platform")), t(7)));
        assertThat(back.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(back.changes()).isNull();
        assertThat(back.attributes().get("cpu").asInt()).isEqualTo(2);
    }

    @Test
    void oneChangeAtATime() {
        Resource a = active();
        Resource updating = ResourceFold.apply(a, ResourceDecider.requestUpdate(a, new Commands.RequestUpdate(null, "update-vm", null,
                Json.object().put("cpu", 4), by("dana")), t(6)));
        assertThatThrownBy(() -> ResourceDecider.requestDelete(updating, new Commands.RequestDelete(null, "delete-vm", null, null, by("dana")), t(7)))
                .isInstanceOf(ConflictException.class).hasMessageStartingWith("resource.busy");
        assertThatThrownBy(() -> ResourceDecider.requestUpdate(updating, new Commands.RequestUpdate(null, "update-vm", null,
                Json.object().put("cpu", 8), by("dana")), t(7)))
                .isInstanceOf(ConflictException.class).hasMessageStartingWith("resource.busy");
        Resource p = pending();
        assertThatThrownBy(() -> ResourceDecider.requestUpdate(p, new Commands.RequestUpdate(null, "update-vm", null, Json.object().put("cpu", 8),
                by("dana")), t(7))).isInstanceOf(ConflictException.class).hasMessageStartingWith("resource.busy");
    }

    @Test
    void anExpectedVersionThatMovedOnIsAVersionMismatch() {
        Resource a = active();
        assertThatThrownBy(() -> ResourceDecider.tag(a, new Commands.Tag(Map.of("env", "prod"), null, Fixtures.at(1, "dana")), t(6)))
                .isInstanceOf(ConflictException.class).hasMessageStartingWith("version.mismatch")
                .extracting(e -> ((ConflictException) e).detail()).isEqualTo(Map.of("expectedVersion", 1L, "version", 3L));
        assertThat(ResourceDecider.tag(a, new Commands.Tag(Map.of("env", "prod"), null, Fixtures.at(3, "dana")), t(6))).isPresent();
    }

    @Test
    void tagsAreCheckedAndAnUnchangedSetIsNoEvent() {
        Resource a = active();
        Resource tagged = ResourceFold.apply(a, ResourceDecider.tag(a, new Commands.Tag(Map.of("env", "prod", "team", "web"), null, by("dana")), t(6))
                .orElseThrow());
        assertThat(tagged.tags()).containsEntry("env", "prod").containsEntry("team", "web");
        assertThat(ResourceDecider.tag(tagged, new Commands.Tag(Map.of("env", "prod"), List.of("nothing"), by("dana")), t(7))).isEmpty();
        Resource removed = ResourceFold.apply(tagged, ResourceDecider.tag(tagged, new Commands.Tag(null, List.of("team"), by("dana")), t(8)).orElseThrow());
        assertThat(removed.tags()).containsOnlyKeys("env");
        assertThatThrownBy(() -> ResourceDecider.tag(a, new Commands.Tag(Map.of("Env", "prod"), null, by("dana")), t(6)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> ResourceDecider.tag(a, new Commands.Tag(Map.of("env", "x".repeat(257)), null, by("dana")), t(6)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void anObservationThatRepeatsTheLastIsNoEvent() {
        Resource a = active();
        ObjectNode observed = Json.object().put("cpu", 2).put("memoryGb", 4).put("powerState", "poweredOn");
        Commands.RecordObservation first = new Commands.RecordObservation("found", observed, t(10), "vsphere", "vm.get", by("drift"));
        Resource o = ResourceFold.apply(a, ResourceDecider.recordObservation(a, first, t(10)).orElseThrow());
        assertThat(o.observed().get("powerState").asText()).isEqualTo("poweredOn");
        assertThat(o.observedStatus()).isEqualTo("found");
        assertThat(ResourceDecider.recordObservation(o, new Commands.RecordObservation("found", observed.deepCopy(), t(20), "vsphere", "vm.get",
                by("drift")), t(20))).isEmpty();
        Optional<ResourceEvent> changed = ResourceDecider.recordObservation(o, new Commands.RecordObservation("found",
                observed.deepCopy().put("powerState", "poweredOff"), t(30), "vsphere", "vm.get", by("drift")), t(30));
        assertThat(changed).isPresent();
    }

    @Test
    void driftOpensOnceAndAcceptingItWritesTheObservedValues() {
        Resource a = active();
        DriftField cpu = new DriftField("/cpu", Json.node(2), Json.node(4));
        Resource drifted = ResourceFold.apply(a, ResourceDecider.recordDrift(a, new Commands.RecordDrift("fnd_1", "warning", List.of(cpu), by("drift")), t(10)));
        assertThat(drifted.drift().findingId()).isEqualTo("fnd_1");
        assertThat(drifted.state()).isEqualTo(ResourceState.ACTIVE);
        assertThatThrownBy(() -> ResourceDecider.recordDrift(drifted, new Commands.RecordDrift("fnd_2", null, List.of(cpu), by("drift")), t(11)))
                .isInstanceOf(ConflictException.class).hasMessageStartingWith("drift.open");
        assertThatThrownBy(() -> ResourceDecider.resolveDrift(drifted, new Commands.ResolveDrift("fnd_2", "ignored", by("dana")), t(11)))
                .isInstanceOf(ConflictException.class).hasMessageStartingWith("drift.mismatch");
        Resource accepted = ResourceFold.apply(drifted, ResourceDecider.resolveDrift(drifted, new Commands.ResolveDrift("fnd_1", "accepted", by("dana")), t(12)));
        assertThat(accepted.drift()).isNull();
        assertThat(accepted.attributes().get("cpu").asInt()).isEqualTo(4);
    }

    @Test
    void anEventIsNamedForItsEnvelopeWhenItIsDecided() {
        Resource p = pending();
        ResourceEvent requested = ResourceDecider.mint(Fixtures.vm(), new Commands.Mint("vm", "global", "web-04", "create-vm", Fixtures.input("web-04"),
                by("dana")), t(0));
        assertThat(requested.id()).startsWith("evt_");
        assertThat(requested.id()).isNotEqualTo(ResourceDecider.mint(Fixtures.vm(), new Commands.Mint("vm", "global", "web-05", "create-vm",
                Fixtures.input("web-05"), by("dana")), t(0)).id());
        assertThat(p.version()).isEqualTo(1);
        assertThat(requested.withData(Json.object()).id()).isEqualTo(requested.id());
        assertThat(requested.key()).isEqualTo("ResourceRequested:" + requested.wfrn() + ":1");
    }

    @Test
    void aJobOnAResourceThatWaitsForNoneIsNoEvent() {
        Resource a = active();
        assertThat(ResourceDecider.jobStarted(a, new Commands.JobStarted("job_2", "update-vm", null, by("platform")), t(10))).isEmpty();
        Resource updating = ResourceFold.apply(a, ResourceDecider.requestUpdate(a, new Commands.RequestUpdate(null, "update-vm", null,
                Json.object().put("cpu", 4), by("dana")), t(11)));
        assertThat(updating.lastJobId()).as("a new change has no job until one is known").isNull();
        assertThat(updating.workflow()).isEqualTo("update-vm");
        ResourceEvent started = ResourceDecider.jobStarted(updating, new Commands.JobStarted("job_2", "update-vm", null, by("platform")), t(12))
                .orElseThrow();
        assertThat(ResourceFold.apply(updating, started).lastJobId()).isEqualTo("job_2");
        Resource named = ResourceFold.apply(a, ResourceDecider.requestUpdate(a, new Commands.RequestUpdate("job_3", "update-vm", null,
                Json.object().put("cpu", 4), by("dana")), t(11)));
        assertThat(named.lastJobId()).isEqualTo("job_3");
        assertThat(ResourceDecider.jobStarted(named, new Commands.JobStarted("job_3", "update-vm", null, by("platform")), t(12)))
                .as("the job the request named is already recorded").isEmpty();
    }

    @Test
    void aRegistrationDuringADeleteIsNoEvent() {
        Resource a = active();
        Resource deleting = ResourceFold.apply(a, ResourceDecider.requestDelete(a, new Commands.RequestDelete("job_9", "delete-vm", null, "done with it",
                by("dana")), t(20)));
        assertThat(ResourceDecider.registered(deleting, new Commands.Registration("job_1", "create-vm", "2.0.1", "vm-7", "vm-7", "web-03",
                Fixtures.attributes("web-03", 4, 8), by("platform")), t(21))).isEmpty();
        assertThat(ResourceDecider.registered(a, new Commands.Registration("job_1", "create-vm", "2.0.1", "vm-7", "vm-7", "web-03",
                Fixtures.attributes("web-03", 4, 8), by("platform")), t(21))).isPresent();
    }

    @Test
    void aDeleteEndsInATombstoneThatRefusesEverything() {
        Resource a = active();
        Resource deleting = ResourceFold.apply(a, ResourceDecider.requestDelete(a, new Commands.RequestDelete("job_9", "delete-vm", null, "done with it",
                by("dana")), t(20)));
        assertThat(deleting.state()).isEqualTo(ResourceState.DELETING);
        Resource gone = ResourceFold.apply(deleting, ResourceDecider.deleted(deleting, new Commands.Deletion("job_9", "vm-7", by("platform")), t(21),
                TOMBSTONE).orElseThrow());
        assertThat(gone.state()).isEqualTo(ResourceState.DELETED);
        assertThat(gone.deletedAt()).isEqualTo(t(21));
        assertThat(gone.tombstoneUntil()).isEqualTo(t(21).plus(TOMBSTONE));
        assertThat(ResourceDecider.deleted(gone, new Commands.Deletion("job_9", "vm-7", by("platform")), t(22), TOMBSTONE)).isEmpty();
        assertThatThrownBy(() -> ResourceDecider.tag(gone, new Commands.Tag(Map.of("a", "b"), null, by("dana")), t(23)))
                .isInstanceOf(TombstoneException.class);
        assertThatThrownBy(() -> ResourceDecider.requestUpdate(gone, new Commands.RequestUpdate(null, "update-vm", null, Json.object().put("cpu", 1),
                by("dana")), t(23))).isInstanceOf(TombstoneException.class);
    }

    @Test
    void aRetryMakesAFailedResourcePendingForTheJobThatRerunsTheCreate() {
        Resource f = failed();
        ResourceEvent retry = ResourceDecider.requestRetry(f, new Commands.RequestRetry(null, null, null, by("dana")), t(10)).orElseThrow();
        assertThat(retry.type()).isEqualTo(ResourceEventType.RETRY_REQUESTED);
        assertThat(retry.state()).isEqualTo(ResourceState.PENDING);
        assertThat(retry.actor()).isEqualTo("dana");
        assertThat(retry.data().get("retryOf").asText()).isEqualTo("job_1");
        assertThat(retry.data().get("workflow").asText()).as("the create workflow when the request names none").isEqualTo("create-vm");
        Resource retrying = ResourceFold.apply(f, retry);
        assertThat(retrying.state()).isEqualTo(ResourceState.PENDING);
        assertThat(retrying.lastJobId()).as("no job until the rerun is known").isNull();
        assertThat(retrying.lastError().jobId()).as("the failure stays on record until the retry succeeds").isEqualTo("job_1");

        assertThat(ResourceDecider.jobStarted(retrying, new Commands.JobStarted("job_1", "create-vm", "2.0.1", by("platform")), t(11)))
                .as("the failed job cannot pass for the retry").isEmpty();
        Resource rerun = ResourceFold.apply(retrying, ResourceDecider.jobStarted(retrying, new Commands.JobStarted("job_2", "create-vm", "2.0.1",
                by("platform")), t(12)).orElseThrow());
        assertThat(rerun.state()).isEqualTo(ResourceState.PENDING);
        assertThat(rerun.lastJobId()).isEqualTo("job_2");

        Resource failedAgain = ResourceFold.apply(rerun, ResourceDecider.jobFailed(rerun, new Commands.JobFailed("job_2", "create-vm", "2.0.1", "failed",
                "provider.timeout", "waitReady", null, by("platform")), t(20)));
        assertThat(failedAgain.state()).isEqualTo(ResourceState.FAILED);
        assertThat(failedAgain.lastError().jobId()).as("the second failure is recorded").isEqualTo("job_2");
        assertThat(failedAgain.lastError().failedStep()).isEqualTo("waitReady");

        Resource done = ResourceFold.apply(rerun, ResourceDecider.registered(rerun, new Commands.Registration("job_2", "create-vm", "2.0.1", "vm-8", "vm-8",
                "web-03", Fixtures.attributes("web-03", 2, 4), by("platform")), t(20)).orElseThrow());
        assertThat(done.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(done.lastError()).isNull();
        assertThat(done.registryId()).isEqualTo("vm-8");
    }

    @Test
    void onlyAFailedResourceCanBeRetried() {
        assertThatThrownBy(() -> ResourceDecider.requestRetry(active(), new Commands.RequestRetry(null, null, null, by("dana")), t(10)))
                .isInstanceOf(ConflictException.class).hasMessageStartingWith("resource.notFailed")
                .extracting(e -> ((ConflictException) e).detail()).isEqualTo(Map.of("state", "active", "jobId", "job_1"));
        assertThatThrownBy(() -> ResourceDecider.requestRetry(pending(), new Commands.RequestRetry(null, null, null, by("dana")), t(10)))
                .isInstanceOf(ConflictException.class).hasMessageStartingWith("resource.notFailed");
        Resource f = failed();
        assertThatThrownBy(() -> ResourceDecider.requestRetry(f, new Commands.RequestRetry(null, null, null, Fixtures.at(1, "dana")), t(10)))
                .isInstanceOf(ConflictException.class).hasMessageStartingWith("version.mismatch");
        Resource retrying = ResourceFold.apply(f, ResourceDecider.requestRetry(f, new Commands.RequestRetry(null, "create-vm-v2", "3.0.0", Fixtures.at(3,
                "dana")), t(10)).orElseThrow());
        assertThat(retrying.workflow()).as("a retry may name another workflow").isEqualTo("create-vm-v2");
        assertThatThrownBy(() -> ResourceDecider.requestRetry(retrying, new Commands.RequestRetry(null, null, null, by("dana")), t(11)))
                .as("a retry is already under way").isInstanceOf(ConflictException.class).hasMessageStartingWith("resource.notFailed");
        Resource deleting = ResourceFold.apply(f, ResourceDecider.requestDelete(f, new Commands.RequestDelete("job_9", "delete-vm", null, null, by("dana")),
                t(12)));
        Resource gone = ResourceFold.apply(deleting, ResourceDecider.deleted(deleting, new Commands.Deletion("job_9", null, by("platform")), t(13), TOMBSTONE)
                .orElseThrow());
        assertThatThrownBy(() -> ResourceDecider.requestRetry(gone, new Commands.RequestRetry(null, null, null, by("dana")), t(14)))
                .isInstanceOf(TombstoneException.class);
    }

    @Test
    void aRetryNamingAJobAlreadyOnRecordChangesNothing() {
        // the BFF reran the failed job before recording the retry, and the rerun's success came first
        Resource f = failed();
        Resource active = ResourceFold.apply(f, ResourceDecider.registered(f, new Commands.Registration("job_2", "create-vm", "2.0.1", "vm-8", "vm-8",
                "web-03", Fixtures.attributes("web-03", 2, 4), by("platform")), t(20)).orElseThrow());
        assertThat(ResourceDecider.requestRetry(active, new Commands.RequestRetry("job_2", null, null, Fixtures.at(3, "dana")), t(21)))
                .as("its outcome stands, whatever version the caller saw").isEmpty();
        assertThatThrownBy(() -> ResourceDecider.requestRetry(active, new Commands.RequestRetry("job_3", null, null, by("dana")), t(21)))
                .as("a job not on record does not make an active resource retryable").isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("resource.notFailed");

        // named before its outcome: the retry records the job, and the job's start is no second event
        Resource named = ResourceFold.apply(f, ResourceDecider.requestRetry(f, new Commands.RequestRetry("job_2", null, null, by("dana")), t(10))
                .orElseThrow());
        assertThat(named.state()).isEqualTo(ResourceState.PENDING);
        assertThat(named.lastJobId()).isEqualTo("job_2");
        assertThat(ResourceDecider.jobStarted(named, new Commands.JobStarted("job_2", "create-vm", "2.0.1", by("platform")), t(11))).isEmpty();
    }

    @Test
    void aRegistrationAfterAFailureMakesTheResourceActive() {
        Resource p = pending();
        Resource f = ResourceFold.apply(p, ResourceDecider.jobFailed(p, new Commands.JobFailed("job_1", "create-vm", null, "failed", "x", null, null,
                by("platform")), t(5)));
        assertThat(ResourceDecider.requestDelete(f, new Commands.RequestDelete(null, "delete-vm", null, null, by("dana")), t(6)).state())
                .as("a failed create can be deleted").isEqualTo(ResourceState.DELETING);
        Resource retried = ResourceFold.apply(f, ResourceDecider.registered(f, new Commands.Registration("job_2", "create-vm", null, "vm-8", null,
                "web-03", Fixtures.attributes("web-03", 2, 4), by("platform")), t(7)).orElseThrow());
        assertThat(retried.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(retried.lastError()).isNull();
    }

    @Test
    void anImportOpensAnActiveResourceAndRefusesADuplicate() {
        ResourceIdentity id = ResourceIdentity.of("wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-legacy-1");
        Commands.Import command = new Commands.Import("vm", "global", id.wfrn(), "vm-legacy-1", "vm-legacy-1", "legacy", Fixtures.attributes("legacy", 1, 2),
                "backfill", by("import"));
        Resource imported = ResourceFold.apply(null, ResourceDecider.importResource(null, id, command, t(0)));
        assertThat(imported.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(imported.source()).isEqualTo("backfill");
        assertThat(imported.registryId()).isEqualTo("vm-legacy-1");
        assertThatThrownBy(() -> ResourceDecider.importResource(imported, id, command, t(1))).isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("resource.exists");
        assertThatThrownBy(() -> ResourceDecider.importResource(null, id, new Commands.Import("vm", "global", id.wfrn(), "x", null, null, null, "manual",
                by("import")), t(1))).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void anEventNeverGoesBackInTime() {
        Resource a = active();
        ResourceEvent e = ResourceDecider.tag(a, new Commands.Tag(Map.of("env", "prod"), null, by("dana")), t(-100)).orElseThrow();
        assertThat(e.at()).isEqualTo(a.updatedAt());
    }
}
