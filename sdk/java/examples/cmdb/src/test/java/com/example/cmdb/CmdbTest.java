package com.example.cmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.provider.sdk.Envelope;
import com.iaf.provider.sdk.ProviderWorker;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;

/** The provider's actions, called through Temporal's activity test environment the way the platform calls them. */
class CmdbTest {

    /** The actions as the platform schedules them: the activity type is the action id, the argument the platform's envelope. */
    @ActivityInterface
    public interface Calls {
        @ActivityMethod(name = "item.get")
        JsonNode get(JsonNode envelope);

        @ActivityMethod(name = "item.preview")
        JsonNode preview(JsonNode envelope);

        @ActivityMethod(name = "item.register")
        JsonNode register(JsonNode envelope);
    }

    private TestActivityEnvironment env;
    private Calls cmdb;

    @BeforeEach
    void start() {
        env = TestActivityEnvironment.newInstance();
        env.registerActivitiesImplementations(ProviderWorker.activities(new Cmdb(Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC)).provider()));
        cmdb = env.newActivityStub(Calls.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build());
    }

    @AfterEach
    void stop() {
        env.close();
    }

    private static ApplicationFailure failure(Runnable call) {
        return (ApplicationFailure) catchThrowableOfType(ActivityFailure.class, call::run).getCause();
    }

    @Test
    void registeringAnItemMakesItReadable() {
        JsonNode item = cmdb.register(Envelope.of(Map.of("name", "web-01", "kind", "SERVER", "owner", "team-web")).toJson());

        assertThat(item.path("id").asText()).isEqualTo("ci-1001");
        assertThat(item.path("registeredAt").asText()).isEqualTo("2026-03-01T10:00:00Z");
        assertThat(cmdb.get(Envelope.of(Map.of("id", "ci-1001")).toJson())).isEqualTo(item);
    }

    @Test
    void aRetriedRegistrationAnswersTheItemItAlreadyCreated() {
        Envelope call = Envelope.of(Map.of("name", "db-01", "kind", "DATABASE")).withIdempotencyKey("job-3:register:2");

        JsonNode first = cmdb.register(call.toJson());
        JsonNode retried = cmdb.register(call.withAttempt(2).toJson());

        assertThat(retried).isEqualTo(first);
        assertThat(first.path("owner").asText()).isEqualTo("unassigned");
    }

    @Test
    void thePreviewSaysWhatARegistrationWouldDo() {
        JsonNode before = cmdb.preview(Envelope.of(Map.of("name", "api-01", "kind", "SERVICE")).toJson());
        cmdb.register(Envelope.of(Map.of("name", "api-01", "kind", "SERVICE")).toJson());
        JsonNode after = cmdb.preview(Envelope.of(Map.of("name", "api-01", "kind", "SERVICE")).withIdempotencyKey("other").toJson());

        assertThat(before.path("action").asText()).isEqualTo("create");
        assertThat(before.path("summary").asText()).isEqualTo("Registers api-01 as a service");
        assertThat(after.path("action").asText()).isEqualTo("none");
    }

    @Test
    void aSecondItemWithTheSameNameIsRefusedForGood() {
        cmdb.register(Envelope.of(Map.of("name", "web-02", "kind", "SERVER")).withIdempotencyKey("a").toJson());

        ApplicationFailure f = failure(() -> cmdb.register(Envelope.of(Map.of("name", "web-02", "kind", "SERVER")).withIdempotencyKey("b").toJson()));

        assertThat(f.getType()).isEqualTo("duplicate_name");
        assertThat(f.isNonRetryable()).isTrue();
    }

    @Test
    void anUnknownItemIsNotFound() {
        ApplicationFailure f = failure(() -> cmdb.get(Envelope.of(Map.of("id", "ci-404")).toJson()));

        assertThat(f.getType()).isEqualTo("not_found");
        assertThat(f.isNonRetryable()).isTrue();
    }

    @Test
    void anInputOfTheWrongShapeIsRefusedBeforeTheHandlerRuns() {
        ApplicationFailure f = failure(() -> cmdb.register(Envelope.of(Map.of("name", "web-03", "kind", "MAINFRAME")).toJson()));

        assertThat(f.getType()).isEqualTo("invalid_input");
        assertThat(f.isNonRetryable()).isTrue();
    }

    @Test
    void theProviderFileNamesTheWorkerAndEveryAction() {
        JsonNode spec = new Cmdb(Clock.systemUTC()).provider().toDocument().path("spec");

        assertThat(spec.path("type").asText()).isEqualTo("sdk");
        assertThat(spec.path("worker").toString()).isEqualTo("{\"taskQueue\":\"provider-cmdb\",\"heartbeatTimeout\":\"30s\"}");
        assertThat(spec.path("actions")).extracting(a -> a.path("id").asText()).containsExactly("item.get", "item.preview", "item.register");
    }
}
