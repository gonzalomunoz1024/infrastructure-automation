package com.iaf.console.fake.common;

import com.fasterxml.jackson.databind.JsonNode;

/** An answer with a status and the platform's error body, thrown from a handler and written by the error filter. */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient Object detail;
    private final Integer retryAfter;
    private final transient JsonNode body;

    public ApiException(int status, String error) {
        this(status, error, null, null);
    }

    public ApiException(int status, String error, Object detail, Integer retryAfter) {
        this(status, error, detail, retryAfter, null);
    }

    public ApiException(int status, String error, Object detail, Integer retryAfter, JsonNode body) {
        super(error);
        this.status = status;
        this.detail = detail;
        this.retryAfter = retryAfter;
        this.body = body;
    }

    /** The whole body to answer with, when the platform's shape is not {@code { error, detail }}. */
    public JsonNode body() {
        return body;
    }

    public static ApiException withBody(int status, String error, JsonNode body) {
        return new ApiException(status, error, null, null, body);
    }

    public int status() {
        return status;
    }

    public Object detail() {
        return detail;
    }

    public Integer retryAfter() {
        return retryAfter;
    }

    public static ApiException badRequest(String error) {
        return new ApiException(400, error);
    }

    public static ApiException unauthorized(String error) {
        return new ApiException(401, error);
    }

    public static ApiException forbidden(String error) {
        return new ApiException(403, error);
    }

    public static ApiException notFound(String what, String id) {
        return new ApiException(404, what + " '" + id + "' not found");
    }

    public static ApiException conflict(String error) {
        return new ApiException(409, error);
    }

    public static ApiException unprocessable(String error, Object detail) {
        return new ApiException(422, error, detail, null);
    }

    public static ApiException tooManyRequests(String error, int retryAfterSeconds) {
        return new ApiException(429, error, null, Math.max(1, retryAfterSeconds));
    }
}
