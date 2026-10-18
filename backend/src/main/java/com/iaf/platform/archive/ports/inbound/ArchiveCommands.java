package com.iaf.platform.archive.ports.inbound;

import com.fasterxml.jackson.annotation.JsonValue;

import reactor.core.publisher.Mono;

/** Write side of the archive slice: an ended job's logs and preview into the artifact store. */
public interface ArchiveCommands {

    /** What {@link #archive} did. */
    enum Outcome {
        ARCHIVED("archived"),
        NOT_ENDED("not-ended"),
        UNKNOWN("unknown");

        private final String json;

        Outcome(String json) {
            this.json = json;
        }

        @JsonValue
        public String json() {
            return json;
        }
    }

    /**
     * Archive an ended job as it is now, replacing an archive it has from an earlier end (a batch
     * whose failed rows were retried ends again), after archiving its ended children that have none.
     */
    Mono<Outcome> archive(String jobId);

    /** Archive the recent ended jobs that have no archive; the number of jobs it archived (each with its children). */
    Mono<Long> archiveMissing();
}
