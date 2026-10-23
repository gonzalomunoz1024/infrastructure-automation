package com.iaf.console.cost.pricing.adapters.web;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.cost.pricing.application.Pricing;
import com.iaf.console.cost.pricing.domain.Estimate;
import com.iaf.console.cost.pricing.domain.Price;
import com.iaf.console.cost.pricing.domain.PriceBinding;
import com.iaf.console.cost.pricing.domain.PriceList;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

import reactor.core.publisher.Mono;

/**
 * The prices in force and estimates from them: they belong to no account, so any person reads them,
 * and any console service allowed them as itself (the BFF and the drift service by default).
 */
@RestController
public final class PricingController {

    private final Pricing pricing;

    public PricingController(Pricing pricing) {
        this.pricing = pricing;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PriceView(String provider, String action, String by, Object monthly) {
        static PriceView of(Price p) {
            if (p.flatRate()) {
                return new PriceView(p.provider(), p.action(), p.by(), p.flat());
            }
            Map<String, BigDecimal> perValue = new LinkedHashMap<>(p.perValue());
            return new PriceView(p.provider(), p.action(), p.by(), perValue);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PriceListView(String name, String title, String currency, String version, List<PriceView> prices) {
        static PriceListView of(PriceList l) {
            return new PriceListView(l.name(), l.title(), l.currency(), l.version(), l.prices().stream().map(PriceView::of).toList());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PriceLists(List<PriceListView> items, String next, List<PriceBinding> bindings) {
    }

    @GetMapping("/v1/price-lists")
    public Mono<PriceLists> priceLists(ServerWebExchange exchange) {
        Principals.pricing(exchange);
        return pricing.catalog().map(c -> new PriceLists(c.lists().stream().map(PriceListView::of).toList(), null, c.bindings()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EstimateRequest(String type, String region, Map<String, Object> attributes, Integer count) {
    }

    @PostMapping("/v1/cost:estimate")
    public Mono<Estimate> estimate(ServerWebExchange exchange, @RequestBody EstimateRequest body) {
        Principals.pricing(exchange);
        if (body == null || body.type() == null || body.type().isBlank()) {
            return Mono.error(new InvalidInputException("type is required", List.of(Problem.error("required", "/type", "the resource type"))));
        }
        if (body.attributes() == null) {
            return Mono.error(new InvalidInputException("attributes are required", List.of(Problem.error("required", "/attributes", "an object"))));
        }
        int count = body.count() == null ? 1 : body.count();
        if (count < 1 || count > Estimate.MAX_COUNT) {
            return Mono.error(new InvalidInputException("count is 1 to " + Estimate.MAX_COUNT,
                    List.of(Problem.error("range", "/count", "1 to " + Estimate.MAX_COUNT))));
        }
        return pricing.catalog().map(c -> Estimate.of(c, body.type(), body.attributes(), count));
    }
}
