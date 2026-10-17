package com.iaf.platform.catalog.domain;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JSON-RPC 2.0 envelopes and error codes, as the MCP server speaks them. */
public final class JsonRpc {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public static final String VERSION = "2.0";
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private JsonRpc() {
    }

    public static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode o = F.objectNode();
        o.put("jsonrpc", VERSION);
        o.set("id", id == null ? F.nullNode() : id);
        o.set("result", result == null ? F.objectNode() : result);
        return o;
    }

    public static ObjectNode error(JsonNode id, int code, String message, JsonNode data) {
        ObjectNode o = F.objectNode();
        o.put("jsonrpc", VERSION);
        o.set("id", id == null ? F.nullNode() : id);
        ObjectNode e = o.putObject("error");
        e.put("code", code);
        e.put("message", message == null ? "error" : message);
        if (data != null && !data.isNull()) {
            e.set("data", data);
        }
        return o;
    }

    /** A notification is a request without an {@code id} member; it never gets a response. */
    public static boolean isNotification(JsonNode request) {
        return request != null && request.isObject() && !request.has("id");
    }

    /** Why a request is not a valid JSON-RPC 2.0 call, or empty when it is. */
    public static Optional<String> invalidReason(JsonNode request) {
        if (request == null || !request.isObject()) {
            return Optional.of("A JSON-RPC request is an object");
        }
        if (!VERSION.equals(request.path("jsonrpc").asText(null))) {
            return Optional.of("jsonrpc must be \"2.0\"");
        }
        JsonNode method = request.get("method");
        if (method == null || !method.isTextual() || method.asText().isBlank()) {
            return Optional.of("method is required");
        }
        JsonNode id = request.get("id");
        if (id != null && !(id.isTextual() || id.isNumber() || id.isNull())) {
            return Optional.of("id is a string, a number or null");
        }
        JsonNode params = request.get("params");
        if (params != null && !params.isNull() && !params.isObject() && !params.isArray()) {
            return Optional.of("params is an object or an array");
        }
        return Optional.empty();
    }

    public static JsonNode id(JsonNode request) {
        return request == null || !request.isObject() ? null : request.get("id");
    }

    public static ObjectNode params(JsonNode request) {
        JsonNode p = request == null ? null : request.get("params");
        return p != null && p.isObject() ? (ObjectNode) p : F.objectNode();
    }
}
