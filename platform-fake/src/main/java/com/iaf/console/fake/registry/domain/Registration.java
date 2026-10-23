package com.iaf.console.fake.registry.domain;

import java.time.Instant;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One registration of a resource id: which job wrote it, through which provider, as which type and owner. */
public record Registration(String jobId, String provider, String type, String owner, Instant at) {

    public ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("jobId", jobId);
        if (provider != null) {
            out.put("provider", provider);
        }
        out.put("type", type);
        if (owner != null) {
            out.put("owner", owner);
        }
        out.put("at", at.toString());
        return out;
    }
}
