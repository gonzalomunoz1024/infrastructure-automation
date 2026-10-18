package com.iaf.platform.archive.domain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.events.DomainEvent;

/**
 * What an ended job leaves in the artifact store: its log lines as {@code jobs/<id>/logs.ndjson}
 * (one log JobEvent per line, as {@code GET /jobs/{id}/events} serves them) and the preview it was
 * started with as {@code jobs/<id>/plan.json}. The logs are written last, so their presence says
 * the job is archived. Pure: reads the JobDetail and JobEvent shapes of docs/api.md, no I/O.
 */
public final class JobArchive {

    /** The JobStatus values of a job that has ended. */
    public static final List<String> ENDED = List.of("succeeded", "failed", "cancelled", "rolled-back");

    /** The domain events that end a job. */
    static final Set<String> ENDING = Set.of("job.finished", "job.cancelled");

    static final String JOB_AGGREGATE = "job";

    private static final ObjectMapper JSON = new ObjectMapper();

    private JobArchive() {
    }

    public static String logsName(String jobId) {
        return "jobs/" + jobId + "/logs.ndjson";
    }

    public static String planName(String jobId) {
        return "jobs/" + jobId + "/plan.json";
    }

    /** The job an event ends ({@code job.finished}, {@code job.cancelled}); empty for any other event. */
    public static Optional<String> endedJob(DomainEvent event) {
        if (event == null || !JOB_AGGREGATE.equals(event.aggregate()) || !ENDING.contains(event.type())) {
            return Optional.empty();
        }
        String id = event.aggregateId();
        if ((id == null || id.isBlank()) && event.data() != null) {
            id = event.data().path("jobId").asText(null);
        }
        return Optional.ofNullable(id).filter(s -> !s.isBlank());
    }

    /** Whether a JobSummary or JobDetail has ended. */
    public static boolean ended(JsonNode job) {
        return job != null && ENDED.contains(job.path("status").asText(""));
    }

    /** Whether a JobEvent is a log line. */
    public static boolean isLog(JsonNode event) {
        return event != null && "log".equals(event.path("type").asText(""));
    }

    /** The preview a JobDetail was started with; empty when it had none. */
    public static Optional<JsonNode> preview(JsonNode job) {
        JsonNode preview = job == null ? null : job.get("preview");
        return preview == null || preview.isNull() ? Optional.empty() : Optional.of(preview);
    }

    /** The ids of a JobDetail's children that have ended, in order. */
    public static List<String> endedChildren(JsonNode job) {
        List<String> ids = new ArrayList<>();
        if (job != null) {
            for (JsonNode child : job.path("children")) {
                String id = child.path("id").asText("");
                if (!id.isBlank() && ended(child)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** NDJSON: each node as one line of compact JSON, every line ended by a newline. */
    public static byte[] ndjson(List<JsonNode> lines) {
        StringBuilder out = new StringBuilder();
        for (JsonNode line : lines) {
            out.append(line.toString()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The documents of an NDJSON archive, blank lines skipped; a line that is not JSON is an IllegalStateException. */
    public static List<JsonNode> lines(byte[] ndjson) {
        List<JsonNode> out = new ArrayList<>();
        String[] lines = new String(ndjson, StandardCharsets.UTF_8).split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                try {
                    out.add(JSON.readTree(lines[i]));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("line " + (i + 1) + " of the archive is not JSON: " + e.getOriginalMessage(), e);
                }
            }
        }
        return out;
    }

    public static byte[] json(JsonNode node) {
        return node.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static JsonNode parse(byte[] json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException("the archived plan is not JSON", e);
        }
    }
}
