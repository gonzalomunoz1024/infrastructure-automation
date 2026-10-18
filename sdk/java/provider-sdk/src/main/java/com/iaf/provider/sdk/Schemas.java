package com.iaf.provider.sdk;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * JSON Schemas of actions: derived from Java types the way the worker reads and writes them, and
 * checked against the values that cross the wire (draft 2020-12).
 *
 * <p>Derivation: a record is an object whose components are its properties (all required unless a
 * component is a {@code java.util.Optional} or marked {@link Field#optional()}); strings, numbers,
 * booleans, enums, lists, maps, {@code Instant}, {@code LocalDate} and {@code UUID} map to their
 * JSON types; {@code JsonNode} is any value. Anything else needs an explicit schema.</p>
 */
public final class Schemas {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private Schemas() {
    }

    /** The JSON Schema of a type; an IllegalArgumentException when it cannot be derived. */
    public static ObjectNode of(Type type) {
        return derive(type, new HashSet<>());
    }

    /** A compiled schema, to check many values against. */
    public static JsonSchema compile(JsonNode schema) {
        return FACTORY.getSchema(schema);
    }

    /** What is wrong with {@code value} against {@code schema}, one line per problem; empty when it conforms. */
    public static List<String> problems(JsonSchema schema, JsonNode value) {
        List<String> out = new ArrayList<>();
        for (ValidationMessage m : schema.validate(value == null ? F.nullNode() : value)) {
            out.add(m.getMessage());
        }
        out.sort(null);
        return out;
    }

    public static List<String> problems(JsonNode schema, JsonNode value) {
        return problems(compile(schema), value);
    }

    private static ObjectNode derive(Type type, Set<Class<?>> seen) {
        if (type instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            Type[] args = pt.getActualTypeArguments();
            if (Optional.class.equals(raw)) {
                return derive(args[0], seen);
            }
            if (Collection.class.isAssignableFrom(raw)) {
                return array(derive(args[0], seen));
            }
            if (Map.class.isAssignableFrom(raw)) {
                ObjectNode o = typed("object");
                o.set("additionalProperties", derive(args[1], seen));
                return o;
            }
            return derive(raw, seen);
        }
        if (type instanceof GenericArrayType ga) {
            return array(derive(ga.getGenericComponentType(), seen));
        }
        if (type instanceof WildcardType w) {
            return derive(w.getUpperBounds()[0], seen);
        }
        if (type instanceof TypeVariable<?>) {
            return F.objectNode();
        }
        Class<?> c = (Class<?>) type;
        if (c == String.class || c == Character.class || c == char.class) {
            return typed("string");
        }
        if (c == UUID.class) {
            return formatted("uuid");
        }
        if (c == Instant.class || c == OffsetDateTime.class || c == ZonedDateTime.class) {
            return formatted("date-time");
        }
        if (c == LocalDate.class) {
            return formatted("date");
        }
        if (c == boolean.class || c == Boolean.class) {
            return typed("boolean");
        }
        if (c == int.class || c == Integer.class || c == long.class || c == Long.class || c == short.class || c == Short.class
                || c == byte.class || c == Byte.class || c == BigInteger.class) {
            return typed("integer");
        }
        if (c == double.class || c == Double.class || c == float.class || c == Float.class || c == BigDecimal.class) {
            return typed("number");
        }
        if (c.isEnum()) {
            ObjectNode o = typed("string");
            ArrayNode values = o.putArray("enum");
            for (Object constant : c.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            return o;
        }
        if (c.isArray()) {
            return array(derive(c.getComponentType(), seen));
        }
        if (ObjectNode.class.isAssignableFrom(c)) {
            return typed("object");
        }
        if (JsonNode.class.isAssignableFrom(c) || c == Object.class) {
            return F.objectNode();
        }
        if (Collection.class.isAssignableFrom(c)) {
            return array(F.objectNode());
        }
        if (Map.class.isAssignableFrom(c)) {
            return typed("object");
        }
        if (c.isRecord()) {
            return record(c, seen);
        }
        throw new IllegalArgumentException("Cannot derive a JSON Schema from " + c.getName() + "; use a record, or give the schema explicitly");
    }

    private static ObjectNode record(Class<?> c, Set<Class<?>> seen) {
        if (!seen.add(c)) {
            throw new IllegalArgumentException(c.getName() + " refers to itself; give its schema explicitly");
        }
        ObjectNode o = typed("object");
        ObjectNode properties = o.putObject("properties");
        ArrayNode required = F.arrayNode();
        for (RecordComponent rc : c.getRecordComponents()) {
            JsonProperty renamed = rc.getAccessor().getAnnotation(JsonProperty.class);
            String name = renamed != null && !renamed.value().isEmpty() ? renamed.value() : rc.getName();
            ObjectNode schema = derive(rc.getGenericType(), seen);
            Field field = rc.getAnnotation(Field.class);
            if (field != null && !field.description().isBlank()) {
                schema.put("description", field.description());
            }
            properties.set(name, schema);
            if (rc.getType() != Optional.class && (field == null || !field.optional())) {
                required.add(name);
            }
        }
        if (!required.isEmpty()) {
            o.set("required", required);
        }
        seen.remove(c);
        return o;
    }

    private static ObjectNode typed(String type) {
        ObjectNode o = F.objectNode();
        o.put("type", type);
        return o;
    }

    private static ObjectNode formatted(String format) {
        ObjectNode o = typed("string");
        o.put("format", format);
        return o;
    }

    private static ObjectNode array(ObjectNode items) {
        ObjectNode o = typed("array");
        o.set("items", items);
        return o;
    }
}
