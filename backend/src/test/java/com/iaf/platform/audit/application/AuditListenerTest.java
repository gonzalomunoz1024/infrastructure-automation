package com.iaf.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.audit.adapters.outbound.InMemoryEvidenceStore;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.ports.outbound.SiemPort;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.redaction.SecretValues;
import com.iaf.platform.config.PlatformProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AuditListenerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final ObjectMapper mapper = new ObjectMapper();
    final InMemoryEvidenceStore store = new InMemoryEvidenceStore();
    final EventBus events = new EventBus(clock);
    final PlatformProperties props = new PlatformProperties();
    final AuditListener listener = new AuditListener(store, events, props, clock, mapper);

    @BeforeEach
    void wire() {
        props.getAudit().setRetentionYears(7);
        listener.subscribe();
    }

    @Test
    void aJobsEventsBecomeOneChainedRecord() {
        job("job.started", "ana", node().put("jobId", "job_1").put("workflow", "vm-restart").put("version", "1.2.0")
                .put("requester", "ana").put("channel", "catalog").put("traceId", "trace-1")
                .set("input", node().put("vmId", "vm-1")));
        ObjectNode started = (ObjectNode) store.record("job_1").orElseThrow().entries().get(0).data();
        assertThat(started.get("input").get("vmId").asText()).isEqualTo("vm-1");

        job("job.approval-requested", "platform", node().put("approvalId", "apr_1").put("jobId", "job_1").put("workflow", "vm-restart")
                .put("step", "approve").put("group", "compute-approvers").put("message", "Restart web-01?").put("timeout", "4h"));
        job("job.approval-decided", "mateo", node().put("approvalId", "apr_1").put("jobId", "job_1").put("decision", "approved").put("by", "mateo").put("comment", "ok"));
        job("job.step", "platform", node().put("jobId", "job_1").put("step", "restart").put("name", "Restart").put("type", "ansible").put("status", "running").put("attempt", 1));
        job("job.step", "platform", node().put("jobId", "job_1").put("step", "restart").put("name", "Restart").put("type", "ansible").put("status", "failed").put("attempt", 1)
                .put("durationMs", 30000).set("error", node().put("class", "Timeout").put("message", "no answer")));
        job("job.attention-resolved", "ana", node().put("jobId", "job_1").put("step", "restart").put("action", "retry").put("by", "ana"));
        job("job.step", "platform", node().put("jobId", "job_1").put("step", "restart").put("name", "Restart").put("type", "ansible").put("status", "succeeded").put("attempt", 2)
                .put("durationMs", 1200).set("output", node().put("powerState", "poweredOn")));
        job("job.step", "platform", node().put("jobId", "job_1").put("step", "notify").put("status", "skipped"));
        job("job.change-opened", "platform", node().put("jobId", "job_1").set("change", node().put("id", "CHG0001").put("url", "https://snow.example/CHG0001").put("state", "open")));
        job("resource.registered", "platform", node().put("jobId", "job_1").put("id", "vm-1").put("type", "vm").put("name", "web-01"));
        job("job.finished", "platform", node().put("jobId", "job_1").put("workflow", "vm-restart").put("version", "1.2.0").put("status", "succeeded")
                .put("durationMs", 40000).set("output", node().put("restarted", true)));

        EvidenceRecord record = store.record("job_1").orElseThrow();
        assertThat(record.workflow()).isEqualTo("vm-restart");
        assertThat(record.version()).isEqualTo("1.2.0");
        assertThat(record.requester()).isEqualTo("ana");
        assertThat(record.channel()).isEqualTo("catalog");
        assertThat(record.entries()).extracting(EvidenceEntry::kind).containsExactly(
                EvidenceKind.REQUESTED, EvidenceKind.APPROVAL, EvidenceKind.APPROVAL, EvidenceKind.STEP, EvidenceKind.DECISION,
                EvidenceKind.STEP, EvidenceKind.STEP, EvidenceKind.CHANGE, EvidenceKind.CHANGED, EvidenceKind.CLOSED);
        assertThat(record.entries()).extracting(EvidenceEntry::seq).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        EvidenceEntry requested = record.entries().get(0);
        assertThat(requested.actor()).isEqualTo("ana");
        assertThat(requested.data().get("traceId").asText()).isEqualTo("trace-1");
        assertThat(requested.data().get("channel").asText()).isEqualTo("catalog");
        assertThat(requested.previous()).isEqualTo(HashChain.GENESIS);

        assertThat(record.entries().get(1).data().get("status").asText()).isEqualTo("requested");
        assertThat(record.entries().get(1).data().get("group").asText()).isEqualTo("compute-approvers");
        EvidenceEntry decided = record.entries().get(2);
        assertThat(decided.actor()).isEqualTo("mateo");
        assertThat(decided.data().get("decision").asText()).isEqualTo("approved");

        EvidenceEntry failedStep = record.entries().get(3);
        assertThat(failedStep.data().get("status").asText()).isEqualTo("failed");
        assertThat(failedStep.data().get("error").get("class").asText()).isEqualTo("Timeout");
        assertThat(failedStep.data().get("durationMs").asLong()).isEqualTo(30000);

        EvidenceEntry decision = record.entries().get(4);
        assertThat(decision.actor()).isEqualTo("ana");
        assertThat(decision.data().get("action").asText()).isEqualTo("retry");
        assertThat(decision.data().get("step").asText()).isEqualTo("restart");

        assertThat(record.entries().get(5).data().get("output").get("powerState").asText()).isEqualTo("poweredOn");
        assertThat(record.entries().get(5).data().get("attempt").asInt()).isEqualTo(2);
        assertThat(record.entries().get(6).data().get("status").asText()).isEqualTo("skipped");

        assertThat(record.entries().get(7).data().get("id").asText()).isEqualTo("CHG0001");
        assertThat(record.change()).isEqualTo(new EvidenceRecord.Change("CHG0001", "https://snow.example/CHG0001"));
        assertThat(record.entries().get(8).data().get("type").asText()).isEqualTo("vm");

        EvidenceEntry closed = record.entries().get(9);
        assertThat(closed.data().get("status").asText()).isEqualTo("succeeded");
        assertThat(closed.data().get("durationMs").asLong()).isEqualTo(40000);
        assertThat(closed.data().get("output").get("restarted").asBoolean()).isTrue();
        assertThat(closed.data().get("retention").get("until").asText()).isEqualTo("2033-10-17T10:00:00Z");
        assertThat(record.retention().until()).isEqualTo(Instant.parse("2033-10-17T10:00:00Z"));
        assertThat(record.closed()).isTrue();
        assertThat(record.status()).isEqualTo("succeeded");
        assertThat(record.at()).isEqualTo(NOW);

        for (int i = 1; i < record.entries().size(); i++) {
            assertThat(record.entries().get(i).previous()).isEqualTo(record.entries().get(i - 1).hash());
        }
        assertThat(record.latestHash()).isEqualTo(closed.hash());
        assertThat(HashChain.verify(record.entries())).isEqualTo(-1);
    }

    @Test
    void previewPolicyAndChangeOnTheStartEventGetTheirOwnEntries() {
        job("job.started", "ana", node().put("jobId", "job_2").put("workflow", "create-vm").put("version", "2.0.1").put("requester", "ana").put("channel", "rest")
                .<ObjectNode>set("preview", node().put("workflow", "create-vm").set("policy", node().put("decision", "needs-approval")))
                .<ObjectNode>set("policy", node().put("decision", "allow").put("engine", "mock"))
                .set("change", node().put("id", "CHG0002").put("url", "https://snow.example/CHG0002").put("state", "open")));

        EvidenceRecord record = store.record("job_2").orElseThrow();
        assertThat(record.entries()).extracting(EvidenceEntry::kind)
                .containsExactly(EvidenceKind.REQUESTED, EvidenceKind.PREVIEW, EvidenceKind.POLICY, EvidenceKind.CHANGE);
        assertThat(record.entries().get(2).data().get("decision").asText()).isEqualTo("allow");
        assertThat(record.change().id()).isEqualTo("CHG0002");
    }

    @Test
    void thePolicyDecisionFallsBackToThePreviewsPolicy() {
        job("job.started", "ana", node().put("jobId", "job_3").put("workflow", "create-vm").put("version", "2.0.1").put("requester", "ana").put("channel", "rest")
                .set("preview", node().set("policy", node().put("decision", "needs-approval"))));

        EvidenceRecord record = store.record("job_3").orElseThrow();
        assertThat(record.entries()).extracting(EvidenceEntry::kind).containsExactly(EvidenceKind.REQUESTED, EvidenceKind.PREVIEW, EvidenceKind.POLICY);
        assertThat(record.entries().get(2).data().get("decision").asText()).isEqualTo("needs-approval");
    }

    @Test
    void aRequesterGivenAsAnIdentityIsNamedByItsId() {
        job("job.started", "platform", node().put("jobId", "job_4").put("workflow", "vm-restart").put("version", "1.2.0").put("channel", "mcp")
                .set("requester", node().put("id", "mateo").put("name", "Mateo")));

        EvidenceRecord record = store.record("job_4").orElseThrow();
        assertThat(record.requester()).isEqualTo("mateo");
        assertThat(record.entries().get(0).actor()).isEqualTo("mateo");
        assertThat(record.entries().get(0).data().get("requester").asText()).isEqualTo("mateo");
    }

    @Test
    void aCancellationClosesTheRecordWithWhoCancelledIt() {
        job("job.started", "ana", node().put("jobId", "job_5").put("workflow", "vm-restart").put("version", "1.2.0").put("requester", "ana").put("channel", "catalog"));
        job("job.cancelled", "mateo", node().put("jobId", "job_5").put("by", "mateo"));

        EvidenceRecord record = store.record("job_5").orElseThrow();
        EvidenceEntry closed = record.entries().get(1);
        assertThat(closed.kind()).isEqualTo(EvidenceKind.CLOSED);
        assertThat(closed.actor()).isEqualTo("mateo");
        assertThat(closed.data().get("status").asText()).isEqualTo("cancelled");
        assertThat(record.status()).isEqualTo("cancelled");
        assertThat(record.retention().until()).isEqualTo(Instant.parse("2033-10-17T10:00:00Z"));
    }

    @Test
    void releaseDecisionsAreEvidencedOnAPseudoJob() {
        events.publish("release.started", "release", "rel_1", "ana", node().put("releaseId", "rel_1").put("workflow", "create-vm").put("from", "1.9.0").put("to", "2.0.1").put("stagePercent", 10));
        events.publish("release.rolled-back", "release", "rel_1", "platform", node().put("releaseId", "rel_1").put("workflow", "create-vm").put("from", "1.9.0").put("to", "2.0.1")
                .put("reason", "error-types gate failed (new on the candidate: QuotaExceeded)"));

        EvidenceRecord record = store.record("release:rel_1").orElseThrow();
        assertThat(record.workflow()).isEqualTo("create-vm");
        assertThat(record.version()).isEqualTo("2.0.1");
        assertThat(record.requester()).isEqualTo("ana");
        assertThat(record.channel()).isEqualTo("release");
        assertThat(record.entries()).extracting(EvidenceEntry::kind).containsExactly(EvidenceKind.DECISION, EvidenceKind.DECISION);
        assertThat(record.entries().get(0).data().get("event").asText()).isEqualTo("release.started");
        assertThat(record.entries().get(1).actor()).isEqualTo("platform");
        assertThat(record.entries().get(1).data().get("reason").asText()).startsWith("error-types gate failed");
        assertThat(record.status()).isEqualTo("rolled-back");
        assertThat(HashChain.verify(record.entries())).isEqualTo(-1);
    }

    @Test
    void aSealedRecordIsFinalAndLateEventsAreDropped() {
        job("job.started", "ana", node().put("jobId", "job_6").put("workflow", "vm-restart").put("version", "1.2.0").put("requester", "ana").put("channel", "catalog"));
        job("job.finished", "platform", node().put("jobId", "job_6").put("status", "succeeded"));
        store.seal("job_6", new EvidenceRecord.Seal(NOW, "sig", "kabc"));

        job("job.step", "platform", node().put("jobId", "job_6").put("step", "late").put("status", "succeeded"));

        assertThat(store.record("job_6").orElseThrow().entries()).hasSize(2);
    }

    @Test
    void eventsThatAreNotEvidenceAreIgnored() {
        events.publish("batch.wave", "job", "job_7", "platform", node().put("jobId", "job_7").put("wave", 1));
        events.publish("provider.alert", "provider", "vsphere", "platform", node().put("message", "slow"));
        job("job.step", "platform", node().put("jobId", "job_8").put("step", "s").put("status", "running"));
        job("job.approval-requested", "platform", node().put("approvalId", "apr_9"));

        assertThat(store.records(null)).isEmpty();
    }

    @Test
    void everyEntryIsRedactedBeforeItIsChained() {
        SecretValues secrets = new SecretValues();
        secrets.remember("resolved-vault-token");
        EventBus bus = new EventBus(clock);
        InMemoryEvidenceStore own = new InMemoryEvidenceStore();
        new AuditListener(own, bus, props, clock, mapper, secrets, SiemExporter.none()).subscribe();

        bus.publish("job.started", "job", "job_r", "ana", node().put("jobId", "job_r").put("workflow", "vm-restart").put("version", "1.2.0")
                .set("input", node().put("vmId", "vm-1").put("password", "hunter2")));
        bus.publish("job.step", "job", "job_r", "platform", node().put("jobId", "job_r").put("step", "login").put("status", "succeeded")
                .<ObjectNode>set("input", node().put("credential", "vault:ops/token").put("apiKey", "raw-key"))
                .set("output", node().put("header", "Bearer resolved-vault-token").put("echo", "resolved-vault-token").put("vm", "vm-1")));

        EvidenceRecord record = own.record("job_r").orElseThrow();
        JsonNode requested = record.entries().get(0).data();
        assertThat(requested.at("/input/password").asText()).isEqualTo("***");
        assertThat(requested.at("/input/vmId").asText()).isEqualTo("vm-1");
        JsonNode step = record.entries().get(1).data();
        assertThat(step.at("/input/credential").asText()).isEqualTo("vault:ops/token");
        assertThat(step.at("/input/apiKey").asText()).isEqualTo("***");
        assertThat(step.at("/output/header").asText()).isEqualTo("Bearer ***");
        assertThat(step.at("/output/echo").asText()).isEqualTo("***");
        assertThat(step.at("/output/vm").asText()).isEqualTo("vm-1");
        assertThat(record.toString()).doesNotContain("hunter2", "raw-key", "resolved-vault-token");
        assertThat(HashChain.verify(record.entries())).isEqualTo(-1);
    }

    @Test
    void theChangeClosingProviderChangesEscalationsAndEditedInputAreEvidenced() {
        job("job.started", "ana", node().put("jobId", "job_c").put("workflow", "delete-vm").put("version", "1.0.0").put("requester", "ana").put("channel", "rest")
                .set("change", node().put("id", "CHG0042").put("url", "https://snow.example/CHG0042").put("state", "implement")));
        job("job.approval-escalated", "platform", node().put("jobId", "job_c").put("approvalId", "apr_1").put("step", "approve").put("to", "compute-leads"));
        job("job.attention-resolved", "ana", node().put("jobId", "job_c").put("step", "delete").put("action", "edit-input").put("by", "ana")
                .set("input", node().put("id", "vm-2")));
        job("job.changed", "platform", node().put("jobId", "job_c").put("step", "delete").put("provider", "vsphere").put("action", "vm.delete")
                .put("effect", "delete").put("target", "vm-2"));
        job("job.finished", "platform", node().put("jobId", "job_c").put("status", "succeeded"));
        job("job.change-closed", "platform", node().put("jobId", "job_c")
                .set("change", node().put("id", "CHG0042").put("url", "https://snow.example/CHG0042").put("state", "closed")));

        EvidenceRecord record = store.record("job_c").orElseThrow();
        assertThat(record.entries()).extracting(EvidenceEntry::kind).containsExactly(EvidenceKind.REQUESTED, EvidenceKind.CHANGE,
                EvidenceKind.APPROVAL, EvidenceKind.DECISION, EvidenceKind.CHANGED, EvidenceKind.CLOSED, EvidenceKind.CHANGE);
        assertThat(record.entries().get(2).data().get("status").asText()).isEqualTo("escalated");
        assertThat(record.entries().get(2).data().get("to").asText()).isEqualTo("compute-leads");
        assertThat(record.entries().get(3).data().at("/input/id").asText()).isEqualTo("vm-2");
        JsonNode changed = record.entries().get(4).data();
        assertThat(changed.get("provider").asText()).isEqualTo("vsphere");
        assertThat(changed.get("action").asText()).isEqualTo("vm.delete");
        assertThat(changed.get("effect").asText()).isEqualTo("delete");
        assertThat(changed.get("target").asText()).isEqualTo("vm-2");
        JsonNode closing = record.entries().get(6).data();
        assertThat(closing.get("state").asText()).isEqualTo("closed");
        assertThat(closing.get("event").asText()).isEqualTo("closed");
        assertThat(record.change().id()).isEqualTo("CHG0042");
    }

    @Test
    void commandsThatAreNotJobsGoOnThePlatformStream() {
        events.publish("workflow.draft-saved", "workflow", "vm-restart", "ana", node().put("name", "vm-restart").put("version", "1.3.0").put("by", "ana"));
        events.publish("change-request.merged", "change-request", "cr_1", "mateo", node().put("id", "cr_1").put("kind", "workflow")
                .put("name", "vm-restart").put("version", "1.3.0").put("yaml", "kind: Workflow\n").put("by", "mateo"));
        events.publish("credential.stored", "credential", "vsphere/token", "ana", node().put("name", "vsphere/token").put("type", "token")
                .put("value", "should-never-be-here").put("by", "ana"));
        events.publish("service-account.created", "service-account", "sa_1", "ana", null);
        events.publish("release.paused", "release", "rel_9", "mateo", node().put("releaseId", "rel_9").put("workflow", "vm-restart").put("to", "1.3.0"));
        events.publish("assistant.started", "assistant-session", "asst_1", "ana", node().put("sessionId", "asst_1").put("mode", "create"));
        events.publish("provider.alert", "provider", "vsphere", "platform", node().put("message", "slow"));

        EvidenceRecord stream = store.record(EvidenceRecord.PLATFORM).orElseThrow();
        assertThat(stream.channel()).isEqualTo("platform");
        assertThat(stream.workflow()).isNull();
        assertThat(stream.entries()).extracting(e -> e.data().get("event").asText()).containsExactly(
                "workflow.draft-saved", "change-request.merged", "credential.stored", "service-account.created", "release.paused",
                "assistant.started");
        assertThat(stream.entries()).extracting(EvidenceEntry::actor).containsExactly("ana", "mateo", "ana", "ana", "mateo", "ana");
        JsonNode merged = stream.entries().get(1).data();
        assertThat(merged.has("yaml")).isFalse();
        assertThat(merged.get("yamlSha256").asText()).hasSize(64);
        JsonNode credential = stream.entries().get(2).data();
        assertThat(credential.get("name").asText()).isEqualTo("vsphere/token");
        assertThat(credential.get("type").asText()).isEqualTo("token");
        assertThat(credential.has("value")).isFalse();
        assertThat(stream.entries().get(3).data().get("aggregateId").asText()).isEqualTo("sa_1");
        assertThat(store.record("release:rel_9")).isPresent();
        assertThat(HashChain.verify(stream.entries())).isEqualTo(-1);
    }

    @Test
    void aJobPointsAtThePublishOfTheVersionItRuns() {
        events.publish("change-request.merged", "change-request", "cr_7", "mateo", node().put("id", "cr_7").put("kind", "workflow")
                .put("name", "vm-restart").put("version", "1.3.0").put("by", "mateo"));
        events.publish("workflow.published", "workflow", "vm-restart", "mateo", node().put("name", "vm-restart").put("version", "1.3.0").put("by", "mateo"));
        job("job.started", "ana", node().put("jobId", "job_p").put("workflow", "vm-restart").put("version", "1.3.0").put("requester", "ana")
                .put("contentHash", "abc123"));
        job("job.started", "ana", node().put("jobId", "job_q").put("workflow", "other").put("version", "0.1.0").put("requester", "ana"));

        EvidenceEntry published = store.record(EvidenceRecord.PLATFORM).orElseThrow().entries().get(1);
        JsonNode publish = store.record("job_p").orElseThrow().entries().get(0).data().get("publish");
        assertThat(publish.get("contentHash").asText()).isEqualTo("abc123");
        assertThat(publish.get("changeRequestId").asText()).isEqualTo("cr_7");
        assertThat(publish.get("publishedBy").asText()).isEqualTo("mateo");
        assertThat(publish.get("platformSeq").asInt()).isEqualTo(published.seq());
        assertThat(publish.get("platformHash").asText()).isEqualTo(published.hash());
        assertThat(store.record("job_q").orElseThrow().entries().get(0).data().has("publish")).isFalse();
    }

    @Test
    void afterARestartThePublishIndexIsRebuiltFromTheStoredStream() {
        events.publish("workflow.published", "workflow", "vm-restart", "mateo", node().put("name", "vm-restart").put("version", "1.4.0").put("by", "mateo"));
        EventBus fresh = new EventBus(clock);
        new AuditListener(store, fresh, props, clock, mapper).subscribe();

        fresh.publish("job.started", "job", "job_after", "ana", node().put("jobId", "job_after").put("workflow", "vm-restart").put("version", "1.4.0"));

        JsonNode publish = store.record("job_after").orElseThrow().entries().get(0).data().get("publish");
        assertThat(publish.get("platformSeq").asInt()).isEqualTo(1);
        assertThat(publish.get("publishedBy").asText()).isEqualTo("mateo");
    }

    @Test
    void everyChainedEntryIsHandedToTheSiem() throws Exception {
        List<SiemPort.SiemEvent> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        SiemPort port = new SiemPort() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public void send(List<SiemEvent> batch) {
                sent.addAll(batch);
            }
        };
        SiemExporter exporter = new SiemExporter(List.of(port), new SiemProperties(), null);
        EventBus bus = new EventBus(clock);
        InMemoryEvidenceStore own = new InMemoryEvidenceStore();
        new AuditListener(own, bus, props, clock, mapper, new SecretValues(), exporter).subscribe();

        bus.publish("job.started", "job", "job_s", "ana", node().put("jobId", "job_s").put("workflow", "vm-restart").put("version", "1.2.0")
                .set("input", node().put("token", "t")));
        bus.publish("workflow.draft-saved", "workflow", "vm-restart", "ana", node().put("name", "vm-restart"));
        assertThat(exporter.flush(java.time.Duration.ofSeconds(5))).isTrue();

        assertThat(sent).extracting(SiemPort.SiemEvent::stream).containsExactly("job", "platform");
        assertThat(sent.get(0).recordId()).isEqualTo("job_s");
        assertThat(sent.get(0).workflow()).isEqualTo("vm-restart");
        assertThat(sent.get(0).hash()).isEqualTo(own.record("job_s").orElseThrow().latestHash());
        assertThat(sent.get(0).data().at("/input/token").asText()).isEqualTo("***");
        assertThat(sent.get(1).recordId()).isEqualTo(EvidenceRecord.PLATFORM);
    }

    /** A store whose appends fail while {@code failures} lasts. */
    static final class FlakyStore extends InMemoryEvidenceStore {
        final AtomicInteger failures;
        final AtomicInteger calls = new AtomicInteger();

        FlakyStore(int failures) {
            this.failures = new AtomicInteger(failures);
        }

        @Override
        public EvidenceEntry append(String jobId, EvidenceEntry entry) {
            calls.incrementAndGet();
            if (failures.getAndDecrement() > 0) {
                throw new java.io.UncheckedIOException(new java.io.IOException("disk full"));
            }
            return super.append(jobId, entry);
        }
    }

    @Test
    void aFailedAppendIsRetriedOnceThenCountedAndTheJobGoesOn() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FlakyStore once = new FlakyStore(1);
        EventBus bus = new EventBus(clock);
        new AuditListener(once, bus, props, clock, mapper, new SecretValues(), SiemExporter.none(), meters).subscribe();
        bus.publish("job.started", "job", "job_f", "ana", node().put("jobId", "job_f").put("workflow", "vm-restart").put("version", "1.2.0"));

        assertThat(once.calls.get()).as("tried twice").isEqualTo(2);
        assertThat(once.record("job_f").orElseThrow().entries()).hasSize(1);
        assertThat(meters.counter(AuditListener.APPEND_FAILED, "store", "FlakyStore").count()).isZero();

        SimpleMeterRegistry failing = new SimpleMeterRegistry();
        FlakyStore broken = new FlakyStore(2);
        EventBus other = new EventBus(clock);
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        new AuditListener(broken, other, props, clock, mapper, new SecretValues(), SiemExporter.none(), failing).subscribe();
        other.register(e -> seen.add(e.type()));
        other.publish("job.started", "job", "job_g", "ana", node().put("jobId", "job_g").put("workflow", "vm-restart").put("version", "1.2.0"));
        other.publish("job.step", "job", "job_g", "platform", node().put("jobId", "job_g").put("step", "s").put("status", "succeeded"));

        assertThat(seen).as("the bus and the listeners after the audit carry on").containsExactly("job.started", "job.step");
        assertThat(failing.counter(AuditListener.APPEND_FAILED, "store", "FlakyStore").count()).isEqualTo(1.0);
        assertThat(broken.record("job_g").orElseThrow().entries()).extracting(EvidenceEntry::kind).containsExactly(EvidenceKind.STEP);
    }

    @Test
    void aSealedRecordRefusingALateEntryIsNotAFailure() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        InMemoryEvidenceStore own = new InMemoryEvidenceStore();
        EventBus bus = new EventBus(clock);
        new AuditListener(own, bus, props, clock, mapper, new SecretValues(), SiemExporter.none(), meters).subscribe();
        bus.publish("job.started", "job", "job_s", "ana", node().put("jobId", "job_s").put("workflow", "vm-restart").put("version", "1.2.0"));
        own.seal("job_s", new EvidenceRecord.Seal(NOW, "sig", "key-1", 1, own.record("job_s").orElseThrow().latestHash()));

        bus.publish("job.step", "job", "job_s", "platform", node().put("jobId", "job_s").put("step", "late").put("status", "succeeded"));

        assertThat(own.record("job_s").orElseThrow().entries()).hasSize(1);
        assertThat(meters.counter(AuditListener.APPEND_FAILED, "store", "InMemoryEvidenceStore").count()).isZero();
    }

    private void job(String type, String actor, JsonNode data) {
        events.publish(type, "job", data.path("jobId").asText(null), actor, data);
    }

    private ObjectNode node() {
        return mapper.createObjectNode();
    }

    @SuppressWarnings("unused")
    private static List<EvidenceKind> kinds(EvidenceRecord r) {
        return r.entries().stream().map(EvidenceEntry::kind).toList();
    }
}
