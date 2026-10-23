package com.iaf.console.fake.runs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.web.reactive.function.server.ServerRequest;

import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.identity.application.Callers;
import com.iaf.console.fake.identity.domain.DevUsers;
import com.iaf.console.fake.identity.domain.RequestContext;
import com.iaf.console.fake.runs.domain.CommandResult;

class CommandsTest {

    private final FakeClock clock = new FakeClock(Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC));
    private final Commands commands = new Commands(clock);
    private final AtomicInteger runs = new AtomicInteger();

    private ServerRequest request(String key, String consoleRequestId) {
        MockServerRequest request = MockServerRequest.builder().method(HttpMethod.POST).uri(URI.create("/api/v1/jobs")).build();
        Callers.attach(request, DevUsers.byName("gonzalo"), new RequestContext(null, consoleRequestId, Map.of(), null, key, null));
        return request;
    }

    private CommandResult run(ServerRequest request, String name) {
        return commands.execute(request, Json.object().put("name", name), body -> {
            runs.incrementAndGet();
            return CommandResult.accepted(Json.object().put("id", "job-" + runs.get()));
        });
    }

    @Test
    void withoutAKeyEveryCallRuns() {
        run(request(null, null), "a");
        run(request(null, null), "a");
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void theSameKeyAndBodyReplaysTheFirstAnswer() {
        CommandResult first = run(request("k1", null), "a");
        CommandResult second = run(request("k1", null), "a");
        assertThat(runs.get()).isEqualTo(1);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers()).containsEntry(Commands.IDEMPOTENT_REPLAY, "true");
        assertThat(first.headers()).doesNotContainKey(Commands.IDEMPOTENT_REPLAY);
    }

    @Test
    void theSameKeyWithAnotherBodyOrContextIsAMismatch() {
        run(request("k2", null), "a");
        assertThatThrownBy(() -> run(request("k2", null), "b")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(422));
        assertThatThrownBy(() -> run(request("k2", "console-req"), "a")).isInstanceOf(ApiException.class)
                .hasMessageContaining("idempotency.mismatch");
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void aFailedCommandFreesItsKey() {
        assertThatThrownBy(() -> commands.execute(request("k3", null), Json.object(), body -> {
            throw ApiException.badRequest("nope");
        })).isInstanceOf(ApiException.class);
        run(request("k3", null), "a");
        assertThat(runs.get()).isEqualTo(1);
    }
}
