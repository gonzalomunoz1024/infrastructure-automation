package com.iaf.console.cost.pricing.adapters.platform;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.cost.pricing.domain.PriceBinding;
import com.iaf.console.cost.pricing.domain.PriceList;
import com.iaf.console.cost.pricing.domain.PricingCatalog;
import com.iaf.console.cost.pricing.domain.PricingDocuments;
import com.iaf.console.cost.pricing.ports.outbound.PricingSourcePort;

import reactor.core.publisher.Mono;

/**
 * The prices as the platform serves them after P-22: {@code GET /api/v1/price-lists} and the
 * {@code cost} block of every {@code GET /api/v1/resource-types} entry, read with the console's
 * internal service account (the WebClient carries it). The version joins each list's version and
 * the types' versions, so any publish re-prices.
 */
public final class PlatformPricing implements PricingSourcePort {

    public static final String KIND = "platform";

    private final WebClient web;

    public PlatformPricing(WebClient web) {
        this.web = web;
    }

    @Override
    public Mono<PricingCatalog> load() {
        Mono<JsonNode> lists = web.get().uri("/api/v1/price-lists").retrieve().bodyToMono(JsonNode.class);
        Mono<JsonNode> types = web.get().uri("/api/v1/resource-types").retrieve().bodyToMono(JsonNode.class);
        return Mono.zip(lists, types).map(t -> {
            List<PriceList> priceLists = new ArrayList<>();
            StringBuilder version = new StringBuilder();
            for (JsonNode l : t.getT1()) {
                PriceList list = PricingDocuments.priceList(l);
                priceLists.add(list);
                version.append(list.name()).append('@').append(list.version()).append(';');
            }
            List<PriceBinding> bindings = new ArrayList<>();
            for (JsonNode type : t.getT2()) {
                String name = type.path("metadata").path("name").asText(type.path("name").asText(null));
                PriceBinding b = name == null ? null : PricingDocuments.binding(name, type.get("cost"));
                if (b != null) {
                    bindings.add(b);
                    version.append(b).append(';');
                }
            }
            return new PricingCatalog(priceLists, bindings, "platform-" + Integer.toHexString(version.toString().hashCode()));
        });
    }

    @Override
    public String kind() {
        return KIND;
    }
}
