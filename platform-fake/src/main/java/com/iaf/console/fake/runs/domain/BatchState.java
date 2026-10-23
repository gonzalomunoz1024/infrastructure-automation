package com.iaf.console.fake.runs.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The rows and waves of a batch job, and why its rollout stopped when it did. */
public final class BatchState {

    /** One row of the batch: its input, the child job that runs it and how that went. */
    public static final class Row {

        private final int index;
        private final JsonNode input;
        private final JobContext context;
        private String jobId;
        private String status = "pending";
        private String error;
        private Integer wave;

        public Row(int index, JsonNode input, JobContext context) {
            this.index = index;
            this.input = input;
            this.context = context;
        }

        public int index() {
            return index;
        }

        public JsonNode input() {
            return input;
        }

        public JobContext context() {
            return context;
        }

        public String jobId() {
            return jobId;
        }

        public String status() {
            return status;
        }

        public Integer wave() {
            return wave;
        }

        public void started(String childJobId, int waveIndex) {
            this.jobId = childJobId;
            this.status = "running";
            this.wave = waveIndex;
        }

        public void ended(String finalStatus, String why) {
            this.status = finalStatus;
            this.error = why;
        }

        public ObjectNode document() {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("index", index);
            out.set("input", input);
            if (jobId != null) {
                out.put("jobId", jobId);
            }
            out.put("status", status);
            if (error != null) {
                out.put("error", error);
            }
            if (wave != null) {
                out.put("wave", wave);
            }
            return out;
        }
    }

    /** One wave of the rollout. */
    public static final class Wave {

        private final int index;
        private final int percent;
        private final int size;
        private String status = "pending";
        private Instant startedAt;
        private Instant endedAt;

        public Wave(int index, int percent, int size) {
            this.index = index;
            this.percent = percent;
            this.size = size;
        }

        public int index() {
            return index;
        }

        public int percent() {
            return percent;
        }

        public int size() {
            return size;
        }

        public String status() {
            return status;
        }

        public void start(Instant at) {
            this.status = "running";
            this.startedAt = at;
        }

        public void end(String finalStatus, Instant at) {
            this.status = finalStatus;
            this.endedAt = at;
        }

        public ObjectNode document() {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("index", index);
            out.put("percent", percent);
            out.put("size", size);
            out.put("status", status);
            if (startedAt != null) {
                out.put("startedAt", startedAt.toString());
            }
            if (endedAt != null) {
                out.put("endedAt", endedAt.toString());
            }
            return out;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<Wave> waves = new ArrayList<>();
    private final JsonNode rollout;
    private String stoppedReason;

    public BatchState(List<Row> rows, JsonNode rollout) {
        this.rows.addAll(rows);
        this.rollout = rollout;
    }

    public List<Row> rows() {
        return rows;
    }

    public List<Wave> waves() {
        return waves;
    }

    public JsonNode rollout() {
        return rollout;
    }

    public String stoppedReason() {
        return stoppedReason;
    }

    public void stop(String reason) {
        this.stoppedReason = reason;
    }

    public int total() {
        return rows.size();
    }

    public long done() {
        return rows.stream().filter(r -> !"pending".equals(r.status) && !"running".equals(r.status)).count();
    }

    public long failed() {
        return rows.stream().filter(r -> "failed".equals(r.status) || "rolled-back".equals(r.status)).count();
    }

    public ObjectNode progress() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("done", done());
        out.put("total", total());
        out.put("failed", failed());
        return out;
    }

    public ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        var rowsNode = out.putArray("rows");
        rows.forEach(r -> rowsNode.add(r.document()));
        var wavesNode = out.putArray("waves");
        waves.forEach(w -> wavesNode.add(w.document()));
        if (rollout != null && rollout.isObject()) {
            out.set("rollout", rollout);
        }
        if (stoppedReason != null) {
            out.put("stoppedReason", stoppedReason);
        }
        return out;
    }
}
