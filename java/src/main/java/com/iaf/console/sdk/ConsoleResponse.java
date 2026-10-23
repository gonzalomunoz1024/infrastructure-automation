package com.iaf.console.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpHeaders;

/** A success of {@link ConsoleClient#request}: its status, its headers and its body as text. */
public final class ConsoleResponse {
    private final int status;
    private final HttpHeaders headers;
    private final String body;
    private final ObjectMapper mapper;

    ConsoleResponse(int status, HttpHeaders headers, String body, ObjectMapper mapper) {
        this.status = status;
        this.headers = headers;
        this.body = body;
        this.mapper = mapper;
    }

    public int status() {
        return status;
    }

    public HttpHeaders headers() {
        return headers;
    }

    /** The body as the BFF sent it: JSON, CSV or text; empty for a 204. */
    public String body() {
        return body;
    }

    /** The body as JSON, or null when there is none. */
    public JsonNode json() {
        if (body == null || body.isBlank()) return null;
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException("the body is not JSON: " + e.getMessage(), e);
        }
    }

    /** The body as one of the generated models: {@code response.as(JobDetail.class)}. */
    public <T> T as(Class<T> type) {
        if (body == null || body.isBlank()) return null;
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new UncheckedIOException("the body is not a " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public <T> T as(TypeReference<T> type) {
        if (body == null || body.isBlank()) return null;
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new UncheckedIOException("the body does not read as " + type.getType() + ": " + e.getMessage(), e);
        }
    }
}
