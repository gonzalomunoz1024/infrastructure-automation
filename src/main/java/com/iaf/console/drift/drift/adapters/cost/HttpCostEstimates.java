package com.iaf.console.drift.drift.adapters.cost;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Money;
import com.iaf.console.drift.drift.ports.outbound.CostEstimatesPort;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * The cost service's estimate (WP 7.3, {@code POST /v1/cost:estimate}), asked as this service with
 * a BFF service token: the monthly cost of one resource of a type with these attributes, from the
 * platform's price lists. {@code unpriced} answers nothing, and so does a cost service that fails:
 * a missing figure never stops a discovery run or a utilisation pass.
 */
public final class HttpCostEstimates implements CostEstimatesPort {

    private static final Logger log = LoggerFactory.getLogger(HttpCostEstimates.class);

    private final WebClient web;
    private final URI estimate;

    /** An answer must start within {@code timeout}, and its body may not stall for longer between reads. */
    public HttpCostEstimates(WebClient.Builder builder, String baseUrl, ExchangeFilterFunction credentials, Duration timeout) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.estimate = URI.create(base + "/v1/cost:estimate");
        this.web = builder.clone().clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(timeout)))
                .defaultHeader(HttpHeaders.USER_AGENT, "console-drift-service")
                .filter(credentials)
                .filter((request, next) -> next.exchange(request).timeout(timeout))
                .build();
    }

    @Override
    public Mono<Money> monthly(String type, String region, ObjectNode attributes) {
        ObjectNode body = Json.object().put("type", type);
        if (region != null) {
            body.put("region", region);
        }
        body.set("attributes", attributes == null ? Json.object() : attributes);
        return web.post().uri(estimate).contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(JsonNode.class)
                .flatMap(answer -> {
                    if (!"list-prices".equals(answer.path("source").asText()) || !answer.path("monthly").isNumber()) {
                        return Mono.<Money>empty();
                    }
                    BigDecimal unit = answer.path("unitMonthly").isNumber() ? answer.get("unitMonthly").decimalValue() : answer.get("monthly").decimalValue();
                    String basis = answer.path("rule").hasNonNull("priceList") ? answer.path("rule").get("priceList").asText() : "list-prices";
                    return Mono.just(new Money(unit, answer.path("currency").asText(null), basis));
                })
                .onErrorResume(e -> {
                    log.warn("the cost service did not price a {}: {}", type, e.toString());
                    return Mono.empty();
                });
    }
}
