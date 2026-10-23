package com.iaf.console.bff.common.relay;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a console service answered, to pass back to the browser as it was: the status, the content
 * type, the headers the console relays ({@code ETag}, {@code Content-Disposition} and the like) and
 * the body's bytes.
 */
public record Relayed(int status, String contentType, Map<String, String> headers, byte[] body) {

    public Relayed {
        TreeMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            copy.putAll(headers);
        }
        headers = java.util.Collections.unmodifiableMap(copy);
        body = body == null ? new byte[0] : body;
    }

    public boolean ok() {
        return status >= 200 && status < 300;
    }

    /** A header the service answered, or null. */
    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** The same answer with another body, as JSON. */
    public Relayed withJson(byte[] json) {
        return new Relayed(status, "application/json", headers, json);
    }

    /** The same answer with one more header. */
    public Relayed withHeader(String name, String value) {
        TreeMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        copy.put(name, value);
        return new Relayed(status, contentType, copy, body);
    }

    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
