package com.iaf.console.bff.cost.adapters.outbound.http;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.common.relay.RelayCall;
import com.iaf.console.bff.common.relay.RelayClient;
import com.iaf.console.bff.common.relay.Relayed;
import com.iaf.console.bff.cost.application.CostConfiguration;
import com.iaf.console.bff.cost.application.CostProperties;
import com.iaf.console.bff.cost.domain.BudgetCheck;
import com.iaf.console.bff.cost.domain.BudgetRule;
import com.iaf.console.bff.cost.domain.Estimate;
import com.iaf.console.bff.cost.domain.Money;
import com.iaf.console.bff.cost.ports.outbound.CostPort;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The cost service over HTTP, as its contract says. With no URL configured it points at the
 * in-process demo stand-in.
 */
@Component
public class HttpCostPort implements CostPort {

    private final RelayClient client;
    private final ObjectMapper json;

    @Autowired
    public HttpCostPort(WebClient.Builder builder, Trust trust, CostProperties properties, ObjectMapper json, Environment environment) {
        this(new RelayClient(builder, () -> {
            if (properties.url() != null && !properties.url().isBlank()) {
                return properties.url().replaceAll("/+$", "");
            }
            String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "18081"));
            return "http://127.0.0.1:" + port + CostConfiguration.DEMO_PATH;
        }, trust, properties.serviceName(), properties.timeout()), json);
    }

    public HttpCostPort(RelayClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    public String url() {
        return client.url();
    }

    @Override
    public Mono<Relayed> callAsPerson(AccountScope scope, StateCaller caller, RelayCall call) {
        return client.asPerson(scope, caller, call);
    }

    @Override
    public Mono<Relayed> callAsService(RelayCall call) {
        return client.asService(call);
    }

    @Override
    public Mono<List<BudgetRule>> checkBudgets(BudgetCheck check, Duration within) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", check.accountId());
        body.put("type", check.type());
        body.put("service", check.service());
        body.put("tags", check.tags());
        body.put("monthly", check.monthly());
        return client.asService(RelayCall.send("POST", "/v1/budgets:check", List.of(), Map.of(), bytes(body)), within).map(answer -> {
            JsonNode node = ok(answer, "budgets:check");
            List<BudgetRule> rules = new ArrayList<>();
            for (JsonNode r : node.path("rules")) {
                rules.add(new BudgetRule(r.path("id").asText(), r.path("result").asText("warn"), r.path("message").asText(null),
                        r.path("budgetId").asText(null), r.path("budgetName").asText(null)));
            }
            return rules;
        });
    }

    @Override
    public Mono<Estimate> estimate(String type, String region, Map<String, Object> attributes, int count, Duration within) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("region", region);
        body.put("attributes", attributes == null ? Map.of() : attributes);
        body.put("count", count);
        return client.asService(RelayCall.send("POST", "/v1/cost:estimate", List.of(), Map.of(), bytes(body)), within).map(answer -> {
            JsonNode node = ok(answer, "cost:estimate");
            BigDecimal monthly = node.path("monthly").decimalValue();
            boolean priced = !"unpriced".equals(node.path("source").asText()) && monthly.signum() > 0;
            return new Estimate(new Money(monthly, node.path("currency").asText("USD")), node.path("detail").asText(null), priced);
        });
    }

    private JsonNode ok(Relayed answer, String what) {
        if (!answer.ok()) {
            throw new IllegalStateException("the cost service answered " + answer.status() + " to " + what + ": " + answer.text());
        }
        try {
            return json.readTree(answer.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("the cost service answered what the console cannot read: " + e.getMessage(), e);
        }
    }

    private byte[] bytes(Object body) {
        try {
            return json.writeValueAsBytes(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
