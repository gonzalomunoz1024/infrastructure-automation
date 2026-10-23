package com.iaf.console.drift.journey;

import java.math.BigDecimal;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.drift.domain.Money;
import com.iaf.console.drift.drift.ports.outbound.CostEstimatesPort;
import com.networknt.schema.JsonSchema;

import reactor.core.publisher.Mono;

/**
 * The service the monitoring and rightsizing API tests run against: the demo, in a context of its
 * own. Applying a recommendation resizes or deletes a demo VM, and the drift journeys share the
 * VMs of the demo context, so these tests get their own fake platform and state instead. The alarm
 * evaluation and the utilisation pass are held back (an hour away), so a test evaluates and reads
 * when it asks to and sees exactly what it caused; and the recommendations are priced by a small
 * price list, since this branch wires no cost estimates of its own. The drift scan and the revert
 * follower of this context are slowed down, since these tests need only the inventory sync.
 */
@TestPropertySource(properties = {
        "console.monitoring.alarms.interval=1h",
        "console.monitoring.rightsizing.initial-delay=1h",
        // nothing here reads drift, so this context's scan and follower stay quiet while the journeys run
        "console.drift.loop.interval=10m",
        "console.drift.follower.interval=10m"})
@Import(MonitoringServiceTest.TestPrices.class)
abstract class MonitoringServiceTest extends DemoServiceTest {

    static final String CONTRACT = "contracts/openapi/console-drift-service.yaml";
    static final JsonSchema ERROR = schema("Error");

    /** The price list's name, as a recommendation's money carries it. */
    static final String PRICE_BASIS = "monitoring-test-prices";

    /**
     * A VM costs 20 a month, plus 10 per CPU and 2.5 per GB of memory, in USD: a small VM (2 CPUs,
     * 8 GB) 60, a medium one (4 CPUs, 16 GB) 100. Other types have no price. Primary, so it wins over
     * the cost estimates the service wires once they are there.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestPrices {

        @Bean
        @Primary
        CostEstimatesPort monitoringTestPrices() {
            return (type, region, attributes) -> {
                if (!"vm".equals(type) || attributes == null || !attributes.path("cpu").isNumber() || !attributes.path("memoryGb").isNumber()) {
                    return Mono.empty();
                }
                double amount = 20 + 10 * attributes.path("cpu").asDouble() + 2.5 * attributes.path("memoryGb").asDouble();
                return Mono.just(new Money(BigDecimal.valueOf(amount), "USD", PRICE_BASIS));
            };
        }
    }

    static JsonSchema schema(String name) {
        return ContractSchemas.schema(CONTRACT, "/components/schemas/" + name);
    }

    /** The first watch of a type in an account, as the internal API lists it. */
    protected JsonNode watchOfType(String accountId, String type) {
        return await(() -> {
            JsonNode page = body(service().get().uri("/internal/watches?accountId=" + accountId + "&type=" + type + "&limit=1").exchange()
                    .expectStatus().isOk());
            return page.path("items").isEmpty() ? null : page.path("items").get(0);
        }, w -> w.hasNonNull("wfrn"), "a watch of type " + type + " in " + accountId);
    }

    /** Asserts an error answer: the contract's Error, its code first. */
    static void assertError(JsonNode error, String code) {
        ContractSchemas.assertValid(ERROR, error, "the error");
        org.assertj.core.api.Assertions.assertThat(error.path("error").asText()).startsWith(code);
    }
}
