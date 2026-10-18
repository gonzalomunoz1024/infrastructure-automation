package com.iaf.platform.audit.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.PlatformEvents;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.redaction.SecretValues;
import com.iaf.platform.config.PlatformProperties;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns the job events of the runs slice into evidence entries, one chain per job, the release
 * decisions into entries on the pseudo job {@code release:<releaseId>}, and every other command
 * (drafts, change requests, publishes, provider changes, credentials, service accounts, release
 * decisions, builder sessions) into the platform stream, a chain of its own under
 * {@value EvidenceRecord#PLATFORM}. Every entry is redacted before it is chained (secret-looking
 * fields and resolved secret values become {@code ***}, {@code vault:} references stay) and handed
 * to the SIEM exporter after. Registered on the bus synchronously, so a record is complete by the
 * time the command that caused the event returns.
 */
@Slf4j
@Component
public class AuditListener {

    private static final Set<String> RECORDED_STEP_STATUSES = Set.of("succeeded", "failed", "skipped", "compensated");

    private final EvidenceStore store;
    private final EventBus events;
    private final PlatformProperties props;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final SecretValues secrets;
    private final SiemExporter siem;

    /** {@code name@version} -> where its publish sits on the platform stream (rebuilt from the stream after a restart). */
    private final Map<String, ObjectNode> publishes = new ConcurrentHashMap<>();
    private final Map<String, String> mergedChangeRequests = new ConcurrentHashMap<>();
    private volatile boolean indexed;

    @Autowired
    public AuditListener(EvidenceStore store, EventBus events, PlatformProperties props, Clock clock, ObjectMapper mapper, SecretValues secrets,
            SiemExporter siem) {
        this.store = store;
        this.events = events;
        this.props = props;
        this.clock = clock;
        this.mapper = mapper;
        this.secrets = secrets == null ? new SecretValues() : secrets;
        this.siem = siem == null ? SiemExporter.none() : siem;
    }

    public AuditListener(EvidenceStore store, EventBus events, PlatformProperties props, Clock clock, ObjectMapper mapper) {
        this(store, events, props, clock, mapper, new SecretValues(), SiemExporter.none());
    }

    @PostConstruct
    public void subscribe() {
        events.register(this::on);
    }

    public void on(DomainEvent event) {
        JsonNode d = event.data() == null ? mapper.createObjectNode() : event.data();
        switch (event.type()) {
            case "job.started" -> started(event, d);
            case "job.step" -> step(event, d);
            case "job.approval-requested" -> approvalRequested(event, d);
            case "job.approval-decided" -> approvalDecided(event, d);
            case "job.approval-escalated" -> approvalEscalated(event, d);
            case "job.attention-resolved" -> attentionResolved(event, d);
            case "job.change-opened" -> changeOpened(event, d);
            case "job.change-closed" -> changeClosed(event, d);
            case "job.changed" -> changed(event, d);
            case "job.finished" -> finished(event, d);
            case "job.cancelled" -> cancelled(event, d);
            case "resource.registered" -> registered(event, d);
            default -> {
                if (event.type().startsWith("release.")) {
                    releaseDecision(event, d);
                }
                if (PlatformEvents.recorded(event.type())) {
                    platform(event, d);
                }
            }
        }
    }

    private void started(DomainEvent e, JsonNode d) {
        String jobId = jobId(e, d);
        if (jobId == null) {
            return;
        }
        String requester = first(text(d, "requester"), e.actor());
        ObjectNode data = copy(d, "workflow", "version", "channel", "input", "traceId", "parentId", "kind");
        if (requester != null) {
            data.put("requester", requester);
        }
        ObjectNode publish = publishReference(text(d, "workflow"), text(d, "version"), text(d, "contentHash"));
        if (publish != null) {
            data.set("publish", publish);
        }
        append(jobId, e, EvidenceKind.REQUESTED, requester, data);
        if (d.hasNonNull("preview")) {
            append(jobId, e, EvidenceKind.PREVIEW, requester, d.get("preview"));
        }
        JsonNode policy = d.hasNonNull("policy") ? d.get("policy") : d.path("preview").path("policy");
        if (policy != null && !policy.isMissingNode() && !policy.isNull()) {
            append(jobId, e, EvidenceKind.POLICY, requester, policy);
        }
        if (d.hasNonNull("change") && d.get("change").isObject()) {
            append(jobId, e, EvidenceKind.CHANGE, requester, d.get("change"));
        }
    }

    private void step(DomainEvent e, JsonNode d) {
        String jobId = jobId(e, d);
        if (jobId == null || !RECORDED_STEP_STATUSES.contains(text(d, "status"))) {
            return;
        }
        append(jobId, e, EvidenceKind.STEP, e.actor(), copy(d, "step", "name", "type", "status", "attempt", "input", "output", "error", "durationMs"));
    }

    private void approvalRequested(DomainEvent e, JsonNode d) {
        String jobId = jobId(e, d);
        if (jobId == null) {
            return;
        }
        append(jobId, e, EvidenceKind.APPROVAL, e.actor(), copy(d, "approvalId", "step", "group", "message", "timeout").put("status", "requested"));
    }

    private void approvalDecided(DomainEvent e, JsonNode d) {
        String jobId = jobId(e, d);
        if (jobId == null) {
            return;
        }
        String by = first(text(d, "by"), e.actor());
        append(jobId, e, EvidenceKind.APPROVAL, by, copy(d, "approvalId", "decision", "by", "comment").put("status", "decided"));
    }

    private void approvalEscalated(DomainEvent e, JsonNode d) {
        String jobId = jobId(e, d);
        if (jobId == null) {
            return;
        }
        append(jobId, e, EvidenceKind.APPROVAL, e.actor(), copy(d, "approvalId", "step", "to").put("status", "escalated"));
    }

    /** The decision on a job that needed attention, with the edited input when the decision was edit-input. */
    private void attentionResolved(DomainEvent e, JsonNode d) {
        String jobId = jobId(e, d);
        if (jobId == null) {
            return;
        }
        String by = first(text(d, "by"), e.actor());
        append(jobId, e, EvidenceKind.DECISION, by, copy(d, "step", "action", "by", "input"));
    }

    private void changeOpened(DomainEvent e, JsonNode d) {
        String jobId = jobId(e, d);
        if (jobId == null) {
            return;
        }
        JsonNode change = d.hasNonNull("change") && d.get("change").isObject() ? d.get("change") : copy(d, "id", "url", "state");
        append(jobId, e, EvidenceKind.CHANGE, e.actor(), change);
    }

    /** The change record closed when the job ended: its id and the state it was closed in. */
    private void changeClosed(DomainEvent e, JsonNode d) {
        String jobId = jobId(e, d);
        if (jobId == null) {
            return;
        }
        ObjectNode change = d.hasNonNull("change") && d.get("change").isObject() ? ((ObjectNode) d.get("change")).deepCopy() : copy(d, "id", "url", "state");
        change.put("event", "closed");
        append(jobId, e, EvidenceKind.CHANGE, e.actor(), change);
    }

    /** What a provider call with a create, change or delete effect did: provider, action, effect, target. */
    private void changed(DomainEvent e, JsonNode d) {
        String jobId = jobId(e, d);
        if (jobId == null) {
            return;
        }
        append(jobId, e, EvidenceKind.CHANGED, e.actor(), copy(d, "step", "provider", "action", "effect", "target"));
    }

    private void finished(DomainEvent e, JsonNode d) {
        String jobId = jobId(e, d);
        if (jobId == null) {
            return;
        }
        ObjectNode data = copy(d, "status", "durationMs", "output", "errorClass", "failedStep");
        append(jobId, e, EvidenceKind.CLOSED, e.actor(), retention(data, at(e)));
    }

    private void cancelled(DomainEvent e, JsonNode d) {
        String jobId = jobId(e, d);
        if (jobId == null) {
            return;
        }
        String by = first(text(d, "by"), e.actor());
        ObjectNode data = mapper.createObjectNode().put("status", "cancelled");
        if (by != null) {
            data.put("by", by);
        }
        append(jobId, e, EvidenceKind.CLOSED, by, retention(data, at(e)));
    }

    private void registered(DomainEvent e, JsonNode d) {
        String jobId = first(text(d, "jobId"), text(d.path("createdBy"), "jobId"));
        if (jobId == null) {
            return;
        }
        append(jobId, e, EvidenceKind.CHANGED, e.actor(), d);
    }

    /** Promotions, rollbacks, pauses and stage changes are decisions about traffic; evidence them. */
    private void releaseDecision(DomainEvent e, JsonNode d) {
        String releaseId = first(text(d, "releaseId"), e.aggregateId());
        if (releaseId == null) {
            return;
        }
        ObjectNode data = d.isObject() ? d.deepCopy() : mapper.createObjectNode();
        data.put("event", e.type());
        append(EvidenceRecord.RELEASE_PREFIX + releaseId, e, EvidenceKind.DECISION, e.actor(), data);
    }

    /** One command on the platform stream; a publish is remembered so the jobs of that version can point at it. */
    private void platform(DomainEvent e, JsonNode d) {
        ObjectNode data = PlatformEvents.entry(e.type(), e.aggregate(), e.aggregateId(), d);
        EvidenceEntry chained = append(EvidenceRecord.PLATFORM, e, EvidenceKind.DECISION, e.actor(), data);
        if (chained != null) {
            index(chained);
        }
    }

    private void index(EvidenceEntry entry) {
        JsonNode d = entry.data();
        String event = text(d, "event");
        String key = PlatformEvents.versionKey(d);
        if (key == null) {
            return;
        }
        if (PlatformEvents.CHANGE_REQUEST_MERGED.equals(event)) {
            String cr = first(text(d, "id"), text(d, "aggregateId"));
            if (cr != null) {
                mergedChangeRequests.put(key, cr);
            }
        } else if (PlatformEvents.WORKFLOW_PUBLISHED.equals(event)) {
            ObjectNode ref = mapper.createObjectNode();
            ref.put("platformSeq", entry.seq());
            ref.put("platformHash", entry.hash());
            ref.put("publishedAt", entry.at() == null ? null : entry.at().toString());
            ref.put("publishedBy", entry.actor());
            String cr = mergedChangeRequests.get(key);
            if (cr != null) {
                ref.put("changeRequestId", cr);
            }
            publishes.put(key, ref);
        }
    }

    /** Where the version a job runs was published: its content hash and the publish entry on the platform stream. */
    private ObjectNode publishReference(String workflow, String version, String contentHash) {
        if (workflow == null || version == null) {
            return null;
        }
        ensureIndexed();
        ObjectNode known = publishes.get(workflow + "@" + version);
        if (known == null && contentHash == null) {
            return null;
        }
        ObjectNode ref = known == null ? mapper.createObjectNode() : known.deepCopy();
        if (contentHash != null) {
            ref.put("contentHash", contentHash);
        }
        return ref;
    }

    /** After a restart the publish index is rebuilt once from the platform stream the store kept. */
    private void ensureIndexed() {
        if (indexed) {
            return;
        }
        synchronized (this) {
            if (indexed) {
                return;
            }
            try {
                store.record(EvidenceRecord.PLATFORM).ifPresent(r -> r.entries().forEach(this::index));
            } catch (RuntimeException ex) {
                log.warn("platform stream not readable for the publish index: {}", ex.toString());
            }
            indexed = true;
        }
    }

    /** Redacts, chains and hands the entry to the SIEM; null when the record refused it. */
    private EvidenceEntry append(String jobId, DomainEvent e, EvidenceKind kind, String actor, JsonNode data) {
        try {
            JsonNode safe = secrets.redact(data);
            EvidenceEntry chained = store.append(jobId, EvidenceEntry.draft(at(e), kind, actor, safe));
            if (siem.enabled()) {
                siem.offer(jobId, workflowOf(jobId, e), chained);
            }
            return chained;
        } catch (IllegalStateException ex) {
            log.warn("evidence of {} not recorded: {}", jobId, ex.getMessage());
            return null;
        }
    }

    private static String workflowOf(String recordId, DomainEvent e) {
        if (EvidenceRecord.PLATFORM.equals(recordId)) {
            return null;
        }
        return e.data() == null ? null : text(e.data(), "workflow");
    }

    private ObjectNode retention(ObjectNode data, Instant closedAt) {
        Instant until = closedAt.atOffset(ZoneOffset.UTC).plusYears(props.getAudit().getRetentionYears()).toInstant();
        data.putObject("retention").put("until", until.toString());
        return data;
    }

    private Instant at(DomainEvent e) {
        return e.at() == null ? clock.instant() : e.at();
    }

    private ObjectNode copy(JsonNode source, String... fields) {
        ObjectNode out = mapper.createObjectNode();
        if (source != null) {
            for (String f : fields) {
                if (source.hasNonNull(f)) {
                    out.set(f, source.get(f));
                }
            }
        }
        return out;
    }

    private static String jobId(DomainEvent e, JsonNode d) {
        String id = text(d, "jobId");
        if (id == null && "job".equals(e.aggregate())) {
            id = e.aggregateId();
        }
        return id;
    }

    /** A text field; an object (an identity, say) is named by its id or name. */
    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isObject()) {
            JsonNode id = v.hasNonNull("id") ? v.get("id") : v.get("name");
            return id == null ? null : id.asText();
        }
        return v.asText();
    }

    private static String first(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
