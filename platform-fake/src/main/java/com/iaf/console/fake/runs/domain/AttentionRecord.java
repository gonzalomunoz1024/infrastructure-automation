package com.iaf.console.fake.runs.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A paused step that a person must decide on: retry, edit the input, skip or abort. */
public record AttentionRecord(String step, StepError error, List<String> options, Instant since, String timeout) {

    public static final List<String> OPTIONS = List.of("retry", "edit-input", "skip", "abort");

    public static AttentionRecord of(String step, StepError error, Instant since) {
        return new AttentionRecord(step, error, OPTIONS, since, "24h");
    }

    public ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("step", step);
        if (error != null) {
            out.set("error", error.document());
        }
        var optionsNode = out.putArray("options");
        options.forEach(optionsNode::add);
        out.put("since", since.toString());
        out.put("timeout", timeout);
        return out;
    }
}
