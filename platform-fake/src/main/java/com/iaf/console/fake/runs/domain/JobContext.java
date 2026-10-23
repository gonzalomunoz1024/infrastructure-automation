package com.iaf.console.fake.runs.domain;

import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The console account, region, resource and request a job is about (P-04); present on console-started jobs only. */
public record JobContext(String accountId, String region, String wfrn, String consoleRequestId, Map<String, String> labels) {

    public JobContext {
        labels = labels == null ? Map.of() : Map.copyOf(new TreeMap<>(labels));
    }

    /** The context a child job inherits: everything but the wfrn, which names the parent's resource. */
    public JobContext forChild() {
        return new JobContext(accountId, region, null, consoleRequestId, labels);
    }

    public JobContext withWfrn(String resourceWfrn) {
        return new JobContext(accountId, region, resourceWfrn, consoleRequestId, labels);
    }

    public JobContext withLabels(Map<String, String> rowLabels) {
        Map<String, String> merged = new TreeMap<>(labels);
        if (rowLabels != null) {
            merged.putAll(rowLabels);
        }
        return new JobContext(accountId, region, wfrn, consoleRequestId, merged);
    }

    public ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("accountId", accountId);
        out.put("region", region);
        if (wfrn != null) {
            out.put("wfrn", wfrn);
        }
        if (consoleRequestId != null) {
            out.put("consoleRequestId", consoleRequestId);
        }
        ObjectNode labelsNode = out.putObject("labels");
        labels.forEach(labelsNode::put);
        return out;
    }
}
