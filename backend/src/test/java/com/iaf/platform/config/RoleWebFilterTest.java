package com.iaf.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import com.iaf.platform.common.adapters.mongo.BlockingStoresWebFilter;
import com.iaf.platform.identity.adapters.inbound.IdentityWebFilter;

import reactor.core.publisher.Mono;

class RoleWebFilterTest {

    static PlatformProperties temporal() {
        PlatformProperties props = new PlatformProperties();
        props.getExecution().setMode("temporal");
        return props;
    }

    /** Runs the filter on one GET; true when the request went on to the routes. */
    static boolean passes(RoleWebFilter filter, MockServerWebExchange exchange) {
        AtomicBoolean reached = new AtomicBoolean();
        WebFilterChain chain = ex -> {
            reached.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return reached.get();
    }

    static MockServerWebExchange get(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path));
    }

    @Test
    void anInstanceWithTheApiRoleServesEverything() {
        RoleWebFilter filter = new RoleWebFilter(temporal(), new MockEnvironment().withProperty("platform.roles", "api"));

        assertThat(passes(filter, get("/api/v1/workflows"))).isTrue();
        assertThat(passes(filter, get("/actuator/health"))).isTrue();
    }

    @Test
    void aWorkerOnlyInstanceServesTheActuatorAndNothingElse() {
        RoleWebFilter filter = new RoleWebFilter(temporal(), new MockEnvironment().withProperty("platform.roles", "worker"));

        assertThat(passes(filter, get("/actuator/health"))).isTrue();
        assertThat(passes(filter, get("/actuator/prometheus"))).isTrue();
        for (String path : new String[] {"/api/v1/workflows", "/api/v1/jobs/job_1/stream", "/mcp", "/actuatorx", "/"}) {
            MockServerWebExchange exchange = get(path);
            assertThat(passes(filter, exchange)).as(path).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exchange.getResponse().getBodyAsString().block()).as(path).isEqualTo(RoleWebFilter.WORKER_ONLY);
        }
    }

    @Test
    void theActuatorKeepsItsConfiguredBasePath() {
        MockEnvironment env = new MockEnvironment().withProperty("platform.roles", "worker").withProperty("management.endpoints.web.base-path", "/manage");
        RoleWebFilter filter = new RoleWebFilter(temporal(), env);

        assertThat(passes(filter, get("/manage/health"))).isTrue();
        assertThat(passes(filter, get("/actuator/health"))).isFalse();
    }

    @Test
    void theContextRefusesRolesThatCannotWork() {
        assertThatThrownBy(() -> new RoleWebFilter(new PlatformProperties(), new MockEnvironment().withProperty("platform.roles", "api")))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("platform.roles [api] needs the temporal profile");
    }


    @Test
    void itRunsRightAfterTheBlockingStoresFilterAndBeforeTheIdentityFilter() {
        int role = OrderUtils.getOrder(RoleWebFilter.class, Ordered.LOWEST_PRECEDENCE);

        assertThat(OrderUtils.getOrder(BlockingStoresWebFilter.class, Ordered.LOWEST_PRECEDENCE)).as("the chain moves off the event loop first").isLessThan(role);
        assertThat(role).as("a worker-only instance answers before an identity is looked up").isLessThan(OrderUtils.getOrder(IdentityWebFilter.class, Ordered.LOWEST_PRECEDENCE));
    }
}
