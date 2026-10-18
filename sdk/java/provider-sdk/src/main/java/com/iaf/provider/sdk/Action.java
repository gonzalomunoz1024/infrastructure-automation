package com.iaf.provider.sdk;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;

/**
 * One operation a provider offers: its id (the activity type the platform schedules), what it
 * does, the JSON Schemas of its input and output (derived from the handler's types or given
 * explicitly), an optional plan action that previews it, the demo answer and the handler.
 *
 * <pre>
 * Action.of("item.register", Register.class, Item.class, cmdb::register)
 *       .title("Register a configuration item")
 *       .effect(Effect.CREATE)
 *       .plan("item.preview")
 * </pre>
 *
 * @param <I> the input type
 * @param <O> the output type
 */
public final class Action<I, O> {

    private final String id;
    private final String title;
    private final Effect effect;
    private final String description;
    private final String plan;
    private final ObjectNode input;
    private final ObjectNode output;
    private final JsonNode sample;
    private final Class<I> inputType;
    private final Handler<I, O> handler;
    private final JsonSchema inputCheck;
    private final JsonSchema outputCheck;

    private Action(Builder<I, O> b) {
        this.id = b.id;
        this.title = b.title == null || b.title.isBlank() ? b.id : b.title;
        this.effect = b.effect;
        this.description = b.description;
        this.plan = b.plan;
        this.input = b.input;
        this.output = b.output;
        this.sample = b.sample == null ? null : Json.MAPPER.valueToTree(b.sample);
        this.inputType = b.inputType;
        this.handler = b.handler;
        this.inputCheck = Schemas.compile(input);
        this.outputCheck = Schemas.compile(output);
    }

    /** An action whose input and output schemas are derived from record types. */
    public static <I, O> Builder<I, O> of(String id, Class<I> input, Class<O> output, Handler<I, O> handler) {
        return new Builder<>(id, input, Schemas.of(input), Schemas.of(output), handler);
    }

    /** An action on plain JSON: give its schemas with {@link Builder#input} and {@link Builder#output} (an open object until then). */
    public static Builder<JsonNode, JsonNode> json(String id, Handler<JsonNode, JsonNode> handler) {
        ObjectNode open = JsonNodeFactory.instance.objectNode().put("type", "object");
        return new Builder<>(id, JsonNode.class, open, open.deepCopy(), handler);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public Effect effect() {
        return effect;
    }

    public String description() {
        return description;
    }

    /** The id of the action that previews this one, or null. */
    public String plan() {
        return plan;
    }

    public ObjectNode inputSchema() {
        return input.deepCopy();
    }

    public ObjectNode outputSchema() {
        return output.deepCopy();
    }

    /** The answer the platform's demo mode gives for this action, or null. */
    public JsonNode sample() {
        return sample == null ? null : sample.deepCopy();
    }

    Class<I> inputType() {
        return inputType;
    }

    Handler<I, O> handler() {
        return handler;
    }

    JsonSchema inputCheck() {
        return inputCheck;
    }

    JsonSchema outputCheck() {
        return outputCheck;
    }

    public static final class Builder<I, O> {
        private final String id;
        private final Class<I> inputType;
        private final Handler<I, O> handler;
        private String title;
        private Effect effect;
        private String description;
        private String plan;
        private ObjectNode input;
        private ObjectNode output;
        private Object sample;

        private Builder(String id, Class<I> inputType, ObjectNode input, ObjectNode output, Handler<I, O> handler) {
            this.id = id;
            this.inputType = Objects.requireNonNull(inputType, "input type");
            this.input = input;
            this.output = output;
            this.handler = Objects.requireNonNull(handler, "handler");
        }

        public Builder<I, O> title(String title) {
            this.title = title;
            return this;
        }

        public Builder<I, O> effect(Effect effect) {
            this.effect = effect;
            return this;
        }

        public Builder<I, O> description(String description) {
            this.description = description;
            return this;
        }

        /** The id of another action of the provider that previews this one (what it would do, without doing it). */
        public Builder<I, O> plan(String actionId) {
            this.plan = actionId;
            return this;
        }

        /** An explicit input schema, replacing the derived one. */
        public Builder<I, O> input(JsonNode schema) {
            this.input = object(schema, "input");
            return this;
        }

        /** An explicit output schema, replacing the derived one. */
        public Builder<I, O> output(JsonNode schema) {
            this.output = object(schema, "output");
            return this;
        }

        /**
         * The answer the platform's demo mode gives for this action (a record or JSON), published
         * as the action's {@code sample}. Text in it may use expressions like
         * <code>{{ $input.name }}</code> or <code>{{ $job.seq }}</code>.
         */
        public Builder<I, O> sample(Object output) {
            this.sample = output;
            return this;
        }

        public Action<I, O> build() {
            return new Action<>(this);
        }

        String id() {
            return id;
        }

        private ObjectNode object(JsonNode schema, String which) {
            if (schema == null || !schema.isObject()) {
                throw new IllegalArgumentException("The " + which + " schema of " + id + " is a JSON object");
            }
            return ((ObjectNode) schema).deepCopy();
        }
    }
}
