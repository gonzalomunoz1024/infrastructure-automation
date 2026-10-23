package com.iaf.console.fake.identity.domain;

import java.util.Map;
import java.util.TreeMap;

/** The P-04 context headers of a call: the resource's wfrn, the console request id, the labels, and the trace and idempotency headers. */
public record RequestContext(String wfrn, String consoleRequestId, Map<String, String> labels, String traceparent, String idempotencyKey,
        String onBehalfOfRaw) {

    public static final RequestContext NONE = new RequestContext(null, null, Map.of(), null, null, null);

    public RequestContext {
        labels = labels == null ? Map.of() : Map.copyOf(new TreeMap<>(labels));
    }

    public boolean hasContext() {
        return wfrn != null || consoleRequestId != null || !labels.isEmpty();
    }
}
