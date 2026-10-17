package com.iaf.platform.audit.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The evidence of one job: its entries in chain order plus what they add up to. The record is a
 * projection over its entries (header from the first one, the change link from the {@code change}
 * entry, the retention date from the {@code closed} entry), so a write-once store can rebuild it
 * from the objects alone. Shape as in docs/api.md.
 */
public record EvidenceRecord(
        String jobId,
        String workflow,
        String version,
        String requester,
        String channel,
        List<EvidenceEntry> entries,
        String latestHash,
        Seal sealed,
        Retention retention,
        Change change) {

    public record Seal(Instant at, String signature, String keyId) {
    }

    public record Retention(Instant until) {
    }

    public record Change(String id, String url) {
    }

    /** Pseudo jobs that evidence release decisions: {@code release:<releaseId>}. */
    public static final String RELEASE_PREFIX = "release:";

    /** A record opened by its first entry (already chained). */
    public static EvidenceRecord start(String jobId, EvidenceEntry first) {
        JsonNode d = first.data();
        String version = first(text(d, "version"), text(d, "to"));
        String requester = first(text(d, "requester"), first.actor());
        String channel = first(text(d, "channel"), jobId.startsWith(RELEASE_PREFIX) ? "release" : null);
        return new EvidenceRecord(jobId, text(d, "workflow"), version, requester, channel, List.of(), HashChain.GENESIS, null,
                new Retention(null), null).append(first);
    }

    /** The record with one more chained entry. */
    public EvidenceRecord append(EvidenceEntry entry) {
        List<EvidenceEntry> all = new ArrayList<>(entries);
        all.add(entry);
        JsonNode d = entry.data();
        String wf = workflow;
        String ver = version;
        String req = requester;
        String ch = channel;
        if (entry.kind() == EvidenceKind.REQUESTED) {
            wf = first(wf, text(d, "workflow"));
            ver = first(ver, text(d, "version"));
            req = first(req, text(d, "requester"), entry.actor());
            ch = first(ch, text(d, "channel"));
        }
        Retention keep = retention;
        if (entry.kind() == EvidenceKind.CLOSED && d != null && d.hasNonNull("retention") && d.get("retention").hasNonNull("until")) {
            keep = new Retention(Instant.parse(d.get("retention").get("until").asText()));
        }
        Change link = change;
        if (entry.kind() == EvidenceKind.CHANGE && d != null && d.hasNonNull("id")) {
            link = new Change(d.get("id").asText(), text(d, "url"));
        }
        return new EvidenceRecord(jobId, wf, ver, req, ch, List.copyOf(all), entry.hash(), sealed, keep, link);
    }

    public EvidenceRecord withSeal(Seal seal) {
        return new EvidenceRecord(jobId, workflow, version, requester, channel, entries, latestHash, seal, retention, change);
    }

    /** When the job was requested: the first entry's time. */
    public Instant at() {
        return entries.isEmpty() ? null : entries.get(0).at();
    }

    public boolean closed() {
        return entries.stream().anyMatch(e -> e.kind() == EvidenceKind.CLOSED);
    }

    /** The job's final status from its {@code closed} entry; the last decision of a release record; else running. */
    public String status() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            EvidenceEntry e = entries.get(i);
            if (e.kind() == EvidenceKind.CLOSED) {
                return first(text(e.data(), "status"), "closed");
            }
        }
        if (jobId.startsWith(RELEASE_PREFIX) && !entries.isEmpty()) {
            String event = text(entries.get(entries.size() - 1).data(), "event");
            return event == null ? "decided" : event.substring(event.indexOf('.') + 1);
        }
        return "running";
    }

    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isObject()) {
            JsonNode id = v.hasNonNull("id") ? v.get("id") : v.get("name");
            return id == null ? null : id.asText();
        }
        return v.asText();
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
