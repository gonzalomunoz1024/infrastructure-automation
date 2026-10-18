package com.iaf.provider.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class DispatcherTest {

    private final Inventory inventory = new Inventory();

    static ObjectNode envelope(ObjectNode input) {
        ObjectNode p = Json.MAPPER.createObjectNode();
        p.set("input", input);
        p.put("idempotencyKey", "job-7:create:3");
        ObjectNode job = p.putObject("job");
        job.put("id", "job-7");
        job.put("workflow", "add-item");
        job.put("version", "1.0.0");
        job.putNull("traceId");
        p.put("step", "create");
        p.put("environment", "dev");
        p.put("attempt", 2);
        return p;
    }

    static ObjectNode input(String field, String value) {
        return Json.MAPPER.createObjectNode().put(field, value);
    }

    private static ProviderException failure(Runnable call) {
        try {
            call.run();
        } catch (ProviderException e) {
            return e;
        }
        throw new AssertionError("expected a ProviderException");
    }

    @Test
    void aCallRunsTheHandlerWithTheFactsOfTheEnvelope() {
        AtomicReference<ActionContext> seen = new AtomicReference<>();
        Provider p = Provider.named("probe").owner("team-platform").version("1.0.0")
                .action(Action.json("probe.run", (ctx, in) -> {
                    seen.set(ctx);
                    return in;
                }).effect(Effect.READ))
                .build();

        JsonNode out = new Dispatcher(p).dispatch("probe.run", envelope(input("a", "b")), d -> {
        });

        assertThat(out.toString()).isEqualTo("{\"a\":\"b\"}");
        ActionContext ctx = seen.get();
        assertThat(ctx.action()).isEqualTo("probe.run");
        assertThat(ctx.idempotencyKey()).isEqualTo("job-7:create:3");
        assertThat(ctx.job()).isEqualTo(new JobInfo("job-7", "add-item", "1.0.0", null));
        assertThat(ctx.step()).isEqualTo("create");
        assertThat(ctx.environment()).isEqualTo("dev");
        assertThat(ctx.attempt()).isEqualTo(2);
    }

    @Test
    void theInputIsReadIntoTheHandlersTypeAndTheOutputWrittenBack() {
        JsonNode out = new Dispatcher(inventory.provider().build()).dispatch("item.get", envelope(input("id", "it-1")), d -> {
        });

        assertThat(out.toString()).isEqualTo("{\"id\":\"it-1\",\"name\":\"first\",\"state\":\"active\"}");
    }

    @Test
    void theIdempotencyKeyMakesARepeatedCallReturnTheFirstResult() {
        Dispatcher d = new Dispatcher(inventory.provider().build());

        JsonNode first = d.dispatch("item.create", envelope(input("name", "second")), x -> {
        });
        JsonNode again = d.dispatch("item.create", envelope(input("name", "second")), x -> {
        });

        assertThat(again).isEqualTo(first);
        assertThat(inventory.items).hasSize(2);
    }

    @Test
    void aMissingInputIsAnEmptyObject() {
        Provider p = Provider.named("probe").owner("team-platform").version("1.0.0")
                .action(Action.json("probe.run", (ctx, in) -> in).effect(Effect.READ))
                .build();
        ObjectNode payload = envelope(null);
        payload.remove("input");

        assertThat(new Dispatcher(p).dispatch("probe.run", payload, d -> {
        }).toString()).isEqualTo("{}");
    }

    @Test
    void anUnknownActionIsFatal() {
        ProviderException e = failure(() -> new Dispatcher(inventory.provider().build()).dispatch("item.delete", envelope(input("id", "x")), d -> {
        }));

        assertThat(e.errorClass()).isEqualTo("unknown_action");
        assertThat(e.retryable()).isFalse();
    }

    @Test
    void aCallThatIsNotThePlatformsEnvelopeIsFatal() {
        Dispatcher d = new Dispatcher(inventory.provider().build());

        assertThat(failure(() -> d.dispatch("item.get", null, x -> {
        })).errorClass()).isEqualTo("invalid_request");
        ObjectNode textInput = envelope(null);
        textInput.put("input", "it-1");
        assertThat(failure(() -> d.dispatch("item.get", textInput, x -> {
        })).errorClass()).isEqualTo("invalid_request");
    }

    @Test
    void inputThatBreaksTheSchemaIsFatalAndTheHandlerNeverRuns() {
        List<Object> calls = new ArrayList<>();
        Provider p = inventory.provider()
                .action(Action.of("item.rename", Inventory.Lookup.class, Inventory.Item.class, (ctx, in) -> {
                    calls.add(in);
                    return null;
                }).effect(Effect.UPDATE))
                .build();

        ProviderException e = failure(() -> new Dispatcher(p).dispatch("item.rename", envelope(input("name", "x")), d -> {
        }));

        assertThat(e.errorClass()).isEqualTo("invalid_input");
        assertThat(e.retryable()).isFalse();
        assertThat(e.getMessage()).contains("item.rename").contains("id");
        assertThat(calls).isEmpty();
    }

    @Test
    void outputThatBreaksTheSchemaIsFatal() {
        Provider p = Provider.named("probe").owner("team-platform").version("1.0.0")
                .action(Action.of("item.get", Inventory.Lookup.class, Inventory.Item.class, (ctx, in) -> new Inventory.Item(in.id(), null, "active"))
                        .effect(Effect.READ))
                .build();

        ProviderException e = failure(() -> new Dispatcher(p).dispatch("item.get", envelope(input("id", "it-9")), d -> {
        }));

        assertThat(e.errorClass()).isEqualTo("invalid_output");
        assertThat(e.retryable()).isFalse();
        assertThat(e.getMessage()).contains("name");
    }

    @Test
    void aClassifiedFailureKeepsItsClassAndWhetherItMayBeRetried() {
        Dispatcher d = new Dispatcher(inventory.provider().build());

        ProviderException notFound = failure(() -> d.dispatch("item.get", envelope(input("id", "it-404")), x -> {
        }));

        assertThat(notFound.errorClass()).isEqualTo("not_found");
        assertThat(notFound.retryable()).isFalse();
        assertThat(notFound.getMessage()).isEqualTo("No item it-404");
    }

    @Test
    void anUnclassifiedExceptionIsTheRetryableClassError() {
        Provider p = Provider.named("probe").owner("team-platform").version("1.0.0")
                .action(Action.json("probe.run", (ctx, in) -> {
                    throw new IllegalStateException("connection reset");
                }).effect(Effect.READ))
                .build();

        ProviderException e = failure(() -> new Dispatcher(p).dispatch("probe.run", envelope(input("a", "b")), d -> {
        }));

        assertThat(e.errorClass()).isEqualTo("error");
        assertThat(e.retryable()).isTrue();
        assertThat(e.getMessage()).isEqualTo("connection reset");
    }

    @Test
    void aHandlerThatReturnsNothingAnswersAnEmptyObject() {
        Provider p = Provider.named("probe").owner("team-platform").version("1.0.0")
                .action(Action.json("probe.run", (ctx, in) -> null).effect(Effect.DELETE))
                .build();

        assertThat(new Dispatcher(p).dispatch("probe.run", envelope(input("a", "b")), d -> {
        }).toString()).isEqualTo("{}");
    }

    @Test
    void heartbeatsReachTemporal() {
        List<Object> beats = new ArrayList<>();
        Provider p = Provider.named("probe").owner("team-platform").version("1.0.0")
                .action(Action.json("probe.run", (ctx, in) -> {
                    ctx.heartbeat("half way");
                    ctx.heartbeat(null);
                    return in;
                }).effect(Effect.READ))
                .build();

        new Dispatcher(p).dispatch("probe.run", envelope(input("a", "b")), beats::add);

        assertThat(beats).containsExactly("half way", null);
    }

    @Test
    void aBrokenInputTypeIsReportedAsInvalidInput() {
        Provider p = Provider.named("probe").owner("team-platform").version("1.0.0")
                .action(Action.of("probe.count", Inventory.Item.class, Inventory.Item.class, (ctx, in) -> in)
                        .input(Json.MAPPER.createObjectNode().put("type", "object"))
                        .effect(Effect.READ))
                .build();
        ObjectNode in = Json.MAPPER.createObjectNode();
        in.putArray("id");

        assertThatThrownBy(() -> new Dispatcher(p).dispatch("probe.count", envelope(in), d -> {
        })).isInstanceOf(ProviderException.class).hasMessageContaining("cannot be read");
    }
}
