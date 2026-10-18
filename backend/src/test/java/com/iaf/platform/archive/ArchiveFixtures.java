package com.iaf.platform.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;

/** Jobs and job events in the API shapes of docs/api.md, as the runs slice's query port answers them. */
public final class ArchiveFixtures {

    static final ObjectMapper JSON = new ObjectMapper();

    private ArchiveFixtures() {
    }

    public static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(s, e);
        }
    }

    /** A JobDetail; {@code extra} is more fields, each starting with a comma. */
    public static JsonNode detail(String id, String status, String extra) {
        return json("{\"id\":\"" + id + "\",\"workflow\":\"vm-restart\",\"status\":\"" + status + "\"" + extra + "}");
    }

    public static JsonNode logLine(String id, long seq, String message) {
        return json("{\"seq\":" + seq + ",\"at\":\"2026-10-18T10:00:0" + seq + "Z\",\"jobId\":\"" + id
                + "\",\"type\":\"log\",\"data\":{\"event\":\"job.log\",\"jobId\":\"" + id + "\",\"message\":\"" + message + "\"}}");
    }

    /** The JobEvents of an ended job: started, a step, two log lines, finished. */
    public static Flux<JsonNode> history(String id) {
        return Flux.just(
                json("{\"seq\":1,\"at\":\"2026-10-18T10:00:01Z\",\"jobId\":\"" + id + "\",\"type\":\"job\",\"data\":{\"event\":\"job.started\"}}"),
                json("{\"seq\":2,\"at\":\"2026-10-18T10:00:02Z\",\"jobId\":\"" + id + "\",\"type\":\"step\",\"data\":{\"event\":\"job.step\"}}"),
                logLine(id, 3, "PLAY [restart]"),
                logLine(id, 4, "ok: [vm-1]"),
                json("{\"seq\":5,\"at\":\"2026-10-18T10:00:05Z\",\"jobId\":\"" + id + "\",\"type\":\"job\",\"data\":{\"event\":\"job.finished\",\"status\":\"succeeded\"}}"));
    }
}
