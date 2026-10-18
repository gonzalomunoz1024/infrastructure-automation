package com.iaf.platform.identity.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.iaf.platform.identity.application.ServiceScopes;
import com.iaf.platform.identity.domain.Scopes;

import reactor.core.publisher.Mono;

/** Service accounts are held to their scopes; people pass untouched. */
class ServiceScopeWebFilterTest {

    final ServiceScopeWebFilter filter = new ServiceScopeWebFilter();

    record Outcome(boolean passed, HttpStatus status, String body) {
    }

    Outcome run(MockServerHttpRequest request, List<String> scopes) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        if (scopes != null) {
            exchange.getAttributes().put(ServiceScopes.ATTRIBUTE, scopes);
        }
        AtomicBoolean passed = new AtomicBoolean();
        filter.filter(exchange, ex -> {
            passed.set(true);
            return Mono.empty();
        }).block();
        String body = passed.get() ? null : exchange.getResponse().getBodyAsString().block();
        return new Outcome(passed.get(), (HttpStatus) exchange.getResponse().getStatusCode(), body);
    }

    @Test
    void theAssistantsTokenReadsValidatesAndDryRunsButWritesNothing() {
        List<String> assistant = Scopes.PRESETS.get("assistant");
        assertThat(run(MockServerHttpRequest.get("/api/v1/registry/providers").build(), assistant).passed()).isTrue();
        assertThat(run(MockServerHttpRequest.post("/api/v1/workflows:validate").build(), assistant).passed()).isTrue();
        assertThat(run(MockServerHttpRequest.post("/api/v1/workflows:dry-run").build(), assistant).passed()).isTrue();

        Outcome save = run(MockServerHttpRequest.put("/api/v1/workflows/vm-restart/draft").build(), assistant);
        assertThat(save.passed()).isFalse();
        assertThat(save.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(save.body()).contains("\"error\"").contains("workflows:write");
        assertThat(run(MockServerHttpRequest.post("/api/v1/jobs").build(), assistant).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(MockServerHttpRequest.post("/api/v1/providers:publish").build(), assistant).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(MockServerHttpRequest.get("/api/v1/service-accounts").build(), assistant).status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void peopleAndAdminAccountsPassAndGeneratedRoutesGoOnToTheGateway() {
        assertThat(run(MockServerHttpRequest.get("/api/v1/service-accounts").build(), null).passed()).isTrue();
        assertThat(run(MockServerHttpRequest.get("/api/v1/service-accounts").build(), List.of(Scopes.ADMIN)).passed()).isTrue();
        assertThat(run(MockServerHttpRequest.post("/api/v1/vms/vm-1:restart").build(), List.of(Scopes.REGISTRY_READ)).passed()).isTrue();
        assertThat(run(MockServerHttpRequest.post("/api/v2/vms/vm-1:restart").build(), List.of(Scopes.REGISTRY_READ)).passed()).isTrue();
        assertThat(run(MockServerHttpRequest.options("/api/v1/jobs").build(), List.of(Scopes.REGISTRY_READ)).passed()).isTrue();
    }
}
