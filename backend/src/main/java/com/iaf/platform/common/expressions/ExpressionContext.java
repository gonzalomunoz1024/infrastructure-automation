package com.iaf.platform.common.expressions;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * What an expression can see. {@code $json} is the previous step's output, {@code $input} the
 * job input, {@code $('name')} any step by name or id, {@code $vm} an output saved with
 * {@code output: { as: vm }}.
 */
public interface ExpressionContext {

    JsonNode input();

    /** The previous step's output (n8n's {@code $json}). */
    JsonNode json();

    /** A step's output by name or id; null when unknown. */
    JsonNode step(String nameOrId);

    /** An output saved under an alias with {@code output: { as }}; null when unknown. */
    JsonNode alias(String name);

    JsonNode vars();

    JsonNode job();

    JsonNode env();

    JsonNode error();

    /** A lambda parameter ({@code items.map(v => v.id)}); null when unbound. */
    default JsonNode local(String name) {
        return null;
    }

    /** The same context with one lambda parameter bound. */
    default ExpressionContext bind(String name, JsonNode value) {
        ExpressionContext parent = this;
        return new ExpressionContext() {
            @Override
            public JsonNode input() {
                return parent.input();
            }

            @Override
            public JsonNode json() {
                return parent.json();
            }

            @Override
            public JsonNode step(String nameOrId) {
                return parent.step(nameOrId);
            }

            @Override
            public JsonNode alias(String alias) {
                return parent.alias(alias);
            }

            @Override
            public JsonNode vars() {
                return parent.vars();
            }

            @Override
            public JsonNode job() {
                return parent.job();
            }

            @Override
            public JsonNode env() {
                return parent.env();
            }

            @Override
            public JsonNode error() {
                return parent.error();
            }

            @Override
            public JsonNode local(String local) {
                return name.equals(local) ? (value == null ? NullNode.getInstance() : value) : parent.local(local);
            }
        };
    }

    static ExpressionContext of(JsonNode input, JsonNode json) {
        return new Simple(input, json, Map.of(), Map.of(), null, null, null, null);
    }

    static ExpressionContext of(JsonNode input, JsonNode json, Map<String, JsonNode> steps, Map<String, JsonNode> aliases,
            JsonNode vars, JsonNode job, JsonNode env, JsonNode error) {
        return new Simple(input, json, steps, aliases, vars, job, env, error);
    }

    record Simple(JsonNode input, JsonNode json, Map<String, JsonNode> steps, Map<String, JsonNode> aliases,
            JsonNode vars, JsonNode job, JsonNode env, JsonNode error) implements ExpressionContext {

        @Override
        public JsonNode input() {
            return input == null ? NullNode.getInstance() : input;
        }

        @Override
        public JsonNode json() {
            return json == null ? NullNode.getInstance() : json;
        }

        @Override
        public JsonNode step(String nameOrId) {
            return steps.get(nameOrId);
        }

        @Override
        public JsonNode alias(String name) {
            return aliases.get(name);
        }

        @Override
        public JsonNode vars() {
            return vars == null ? NullNode.getInstance() : vars;
        }

        @Override
        public JsonNode job() {
            return job == null ? NullNode.getInstance() : job;
        }

        @Override
        public JsonNode env() {
            return env == null ? NullNode.getInstance() : env;
        }

        @Override
        public JsonNode error() {
            return error == null ? NullNode.getInstance() : error;
        }
    }
}
