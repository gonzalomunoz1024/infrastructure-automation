package com.iaf.console.platform.model;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A job's evidence record: its hashed entries, seal and checkpoints. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
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
        ChangeRef change,
        List<Seal> checkpoints) {

    /** Until when the record is kept. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Retention(Instant until) {
    }

    /** The change record the job opened. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChangeRef(String id, String url) {
    }
}
