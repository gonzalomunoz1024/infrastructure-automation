package com.iaf.platform.audit.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.config.PlatformProperties;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns the job events of the runs slice into evidence entries, one chain per job, and the
 * release decisions into entries on the pseudo job {@code release:<releaseId>}. Registered on
 * the bus synchronously, so a record is complete by the time the command that caused the event
 * returns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditListener {

    private static final Set<String> RECORDED_STEP_STATUSES = Set.of("succeeded", "failed", "skipped", "compensated");

    private final EvidenceStore store;
    private final EventBus events;
    private final PlatformProperties props;
    private final Clock clock;
    private final ObjectMapper mapper;

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
            case "job.attention-resolved" -> attentionResolved(event, d);
            case "job.change-opened" -> changeOpened(event, d);
            case "job.finished" -> finished(event, d);
            case "job.cancelled" -> cancelled(event, d);
            case "resource.registered" -> registered(event, d);
            default -> {
                if (event.type().startsWith("release.")) {
                    releaseDecision(event, d);
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

    private void append(String jobId, DomainEvent e, EvidenceKind kind, String actor, JsonNode data) {
        try {
            store.append(jobId, EvidenceEntry.draft(at(e), kind, actor, data));
        } catch (IllegalStateException ex) {
            log.warn("evidence of {} not recorded: {}", jobId, ex.getMessage());
        }
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
