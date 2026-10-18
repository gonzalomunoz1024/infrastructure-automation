package com.iaf.provider.sdk;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.temporal.client.ActivityCompletionException;

/**
 * Runs one call the way the platform's contract says: find the action by the activity type, read
 * the envelope, check the input against the action's schema, run the handler, check the output.
 * A broken contract is fatal ({@value #UNKNOWN_ACTION}, {@value #INVALID_REQUEST},
 * {@value #INVALID_INPUT}, {@value #INVALID_OUTPUT}); an unclassified exception from a handler is
 * the retryable class {@value #ERROR}. A cancel or timeout Temporal reports through a heartbeat
 * passes through untouched.
 */
final class Dispatcher {

    static final String UNKNOWN_ACTION = "unknown_action";
    static final String INVALID_REQUEST = "invalid_request";
    static final String INVALID_INPUT = "invalid_input";
    static final String INVALID_OUTPUT = "invalid_output";
    static final String ERROR = "error";

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** How a handler's heartbeat reaches Temporal. */
    @FunctionalInterface
    interface Heartbeater {
        void heartbeat(Object details);
    }

    /** The context a handler sees: the envelope's facts and the heartbeat. */
    record Context(String action, String idempotencyKey, JobInfo job, String step, String environment, int attempt, Heartbeater heartbeater)
            implements ActionContext {

        @Override
        public void heartbeat(Object details) {
            heartbeater.heartbeat(details);
        }
    }

    private final Provider provider;

    Dispatcher(Provider provider) {
        this.provider = provider;
    }

    Provider provider() {
        return provider;
    }

    JsonNode dispatch(String activityType, JsonNode payload, Heartbeater heartbeater) {
        Action<?, ?> action = provider.action(activityType)
                .orElseThrow(() -> ProviderException.fatal(UNKNOWN_ACTION, "Provider " + provider.name() + " has no action '" + activityType + "'"));
        Envelope call = read(action.id(), payload);
        Context ctx = new Context(action.id(), call.idempotencyKey(), call.job(), call.step(), call.environment(), call.attempt(), heartbeater);
        JsonNode input = call.input();
        List<String> problems = Schemas.problems(action.inputCheck(), input);
        if (!problems.isEmpty()) {
            throw ProviderException.fatal(INVALID_INPUT, "The input of " + action.id() + " does not match its schema: " + String.join("; ", problems));
        }
        Object out = invoke(action, ctx, input);
        JsonNode json = out == null ? F.objectNode() : Json.MAPPER.valueToTree(out);
        problems = Schemas.problems(action.outputCheck(), json);
        if (!problems.isEmpty()) {
            throw ProviderException.fatal(INVALID_OUTPUT, "The output of " + action.id() + " does not match its schema: " + String.join("; ", problems));
        }
        return json;
    }

    /** The platform's envelope; a missing or null input is an empty object. */
    private static Envelope read(String action, JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw ProviderException.fatal(INVALID_REQUEST, "The call to " + action + " is not the platform's envelope (a JSON object with input)");
        }
        JsonNode input = payload.get("input");
        if (input != null && !input.isNull() && !input.isObject()) {
            throw ProviderException.fatal(INVALID_REQUEST, "The input of " + action + " is a JSON object");
        }
        JsonNode job = payload.path("job");
        return new Envelope(input == null || input.isNull() ? F.objectNode() : input,
                text(payload, "idempotencyKey"),
                new JobInfo(text(job, "id"), text(job, "workflow"), text(job, "version"), text(job, "traceId")),
                text(payload, "step"),
                text(payload, "environment"),
                Math.max(1, payload.path("attempt").asInt(1)));
    }

    @SuppressWarnings("unchecked")
    private static <I, O> O invoke(Action<I, O> action, Context ctx, JsonNode input) {
        I in;
        try {
            in = JsonNode.class.equals(action.inputType()) ? (I) input : Json.MAPPER.treeToValue(input, action.inputType());
        } catch (JsonProcessingException e) {
            throw ProviderException.fatal(INVALID_INPUT, "The input of " + action.id() + " cannot be read: " + e.getOriginalMessage());
        } catch (IllegalArgumentException e) {
            throw ProviderException.fatal(INVALID_INPUT, "The input of " + action.id() + " cannot be read: " + e.getMessage());
        }
        try {
            return action.handler().handle(ctx, in);
        } catch (ProviderException | ActivityCompletionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ERROR, "Interrupted while " + action.id() + " ran", true, e);
        } catch (Exception e) {
            log.warn("action {} failed with an unclassified exception (reported as the retryable class error)", action.id(), e);
            throw new ProviderException(ERROR, e.getMessage() == null ? e.toString() : e.getMessage(), true, e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
