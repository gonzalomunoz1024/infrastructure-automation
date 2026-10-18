package com.iaf.platform.audit.ports.outbound;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The firm's SIEM. Every evidence entry (job records, release records, the platform stream) is
 * sent once it is chained, already redacted. Adapters are blocking and are only ever called from
 * the exporter's own thread; a send that throws is retried, so an adapter must not half-deliver
 * silently.
 */
public interface SiemPort {

    /**
     * One evidence entry as the SIEM receives it. {@code stream} is job, release or platform;
     * {@code recordId} the job id, {@code release:<id>} or {@code platform}.
     */
    record SiemEvent(String stream, String recordId, String workflow, int seq, Instant at, String kind, String actor, JsonNode data,
            String hash, String previous) {
    }

    /** A short name for logs and metrics: hec, syslog. */
    String name();

    /** Delivers the batch, in order; throws when it cannot say every event arrived. */
    void send(List<SiemEvent> batch) throws Exception;
}
