package com.iaf.console.drift.drift.adapters.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Money;
import com.sun.net.httpserver.HttpServer;

class CostEstimatesTest {

    private static ObjectNode vm(int cpu, int memoryGb) {
        ObjectNode o = Json.object();
        o.put("cpu", cpu);
        o.put("memoryGb", memoryGb);
        return o;
    }

    @Test
    void thePriceListAddsTheBaseTheRatesAndTheChoice() {
        PriceListCostEstimates prices = new PriceListCostEstimates(Map.of(
                "vm", new PriceListCostEstimates.TypePrices(5.0, Map.of("cpu", 14.6, "memoryGb", 3.65), null, null),
                "database", new PriceListCostEstimates.TypePrices(null, null, "tier", Map.of("M10", 57.0, "M30", 388.0))), "USD", "list-prices");

        Money vm = prices.monthly("vm", "global", vm(4, 16)).block();
        assertThat(vm.amount()).isEqualByComparingTo("121.80");
        assertThat(vm.currency()).isEqualTo("USD");
        assertThat(vm.basis()).isEqualTo("list-prices");
        ObjectNode m30 = Json.object();
        m30.put("tier", "M30");
        assertThat(prices.monthly("database", "global", m30).block().amount()).isEqualByComparingTo("388.00");
        // a tier the list does not price, a type it does not name: unpriced, not zero
        ObjectNode m99 = Json.object();
        m99.put("tier", "M99");
        assertThat(prices.monthly("database", "global", m99).blockOptional()).isEmpty();
        assertThat(prices.monthly("queue", "global", Json.object()).blockOptional()).isEmpty();
    }

    @Test
    void theCostServicesListPriceIsTheMonthlyCostOfOneAndAnythingElseIsUnpriced() throws IOException {
        AtomicReference<String> answer = new AtomicReference<>();
        AtomicReference<JsonNode> asked = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer cost = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cost.createContext("/v1/cost:estimate", exchange -> {
            asked.set(Json.MAPPER.readTree(exchange.getRequestBody()));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = answer.get();
            if (body == null) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        cost.start();
        try {
            ExchangeFilterFunction token = (request, next) -> next.exchange(
                    org.springframework.web.reactive.function.client.ClientRequest.from(request).header("Authorization", "Bearer service-token").build());
            HttpCostEstimates estimates = new HttpCostEstimates(WebClient.builder(), "http://127.0.0.1:" + cost.getAddress().getPort() + "/", token,
                    Duration.ofSeconds(5));

            answer.set("{\"type\":\"vm\",\"monthly\":243.6,\"unitMonthly\":121.8,\"currency\":\"USD\",\"source\":\"list-prices\","
                    + "\"rule\":{\"priceList\":\"vsphere-2026\",\"provider\":\"vsphere\"}}");
            Money priced = estimates.monthly("vm", "global", vm(4, 16)).block();
            assertThat(priced.amount()).isEqualByComparingTo("121.80");
            assertThat(priced.basis()).isEqualTo("vsphere-2026");
            assertThat(asked.get().path("type").asText()).isEqualTo("vm");
            assertThat(asked.get().path("region").asText()).isEqualTo("global");
            assertThat(asked.get().path("attributes").path("cpu").asInt()).isEqualTo(4);
            assertThat(auth.get()).isEqualTo("Bearer service-token");

            answer.set("{\"type\":\"queue\",\"monthly\":0,\"currency\":\"USD\",\"source\":\"unpriced\"}");
            assertThat(estimates.monthly("queue", "global", Json.object()).blockOptional()).isEmpty();
            answer.set(null);
            assertThat(estimates.monthly("vm", "global", vm(2, 8)).blockOptional()).isEmpty();
        } finally {
            cost.stop(0);
        }
    }
}
