package com.iaf.platform.gateway.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The answer a keyed request got the first time: status, the response headers worth repeating
 * ({@code Content-Type}, {@code Location} and the deprecation headers) and the body as text.
 */
public record StoredAnswer(int status, Map<String, String> headers, String body) {

    public StoredAnswer {
        headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
        body = body == null ? "" : body;
    }

    /** Only a success is kept: a failed request frees its key so the caller can try again with it. */
    public boolean succeeded() {
        return status >= 200 && status < 300;
    }
}
