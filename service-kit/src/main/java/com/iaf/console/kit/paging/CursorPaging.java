package com.iaf.console.kit.paging;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Opaque cursors over {@code (sortValue, _id)}: a page size capped at 200, a cursor that is base64
 * of the two values so a client cannot build one by hand without meaning to, and a 400 for a
 * cursor that does not decode.
 */
public final class CursorPaging {

    /** The most items one page may hold. */
    public static final int MAX_PAGE_SIZE = 200;
    /** The page size when the caller names none. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private CursorPaging() {
    }

    /** The page size to use: the default when absent, at least 1, at most {@value #MAX_PAGE_SIZE}. */
    public static int pageSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (requested < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    public static String encode(Cursor cursor) {
        ArrayNode pair = JSON.createArrayNode();
        pair.addPOJO(cursor.sortValue());
        pair.add(cursor.id());
        return ENCODER.encodeToString(pair.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String encode(Object sortValue, String id) {
        return encode(new Cursor(sortValue, id));
    }

    /** The cursor a string spells; an IllegalArgumentException (a 400) when it does not. */
    public static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException("the cursor is empty");
        }
        JsonNode pair;
        try {
            pair = JSON.readTree(DECODER.decode(cursor.trim()));
        } catch (Exception e) {
            throw new IllegalArgumentException("the cursor is not one this service issued");
        }
        if (pair == null || !pair.isArray() || pair.size() != 2 || !pair.get(1).isTextual()) {
            throw new IllegalArgumentException("the cursor is not one this service issued");
        }
        return new Cursor(sortValue(pair.get(0)), pair.get(1).asText());
    }

    private static Object sortValue(JsonNode sort) {
        if (sort == null || sort.isNull()) {
            return null;
        }
        if (sort.isIntegralNumber()) {
            return sort.asLong();
        }
        if (sort.isNumber()) {
            return sort.asDouble();
        }
        if (sort.isBoolean()) {
            return sort.asBoolean();
        }
        return sort.asText();
    }
}
