package com.iaf.console.cost.pricing.adapters.bundled;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.iaf.console.cost.pricing.domain.PriceBinding;
import com.iaf.console.cost.pricing.domain.PriceList;
import com.iaf.console.cost.pricing.domain.PricingCatalog;
import com.iaf.console.cost.pricing.domain.PricingDocuments;
import com.iaf.console.cost.pricing.ports.outbound.PricingSourcePort;

import reactor.core.publisher.Mono;

/**
 * The prices until the platform serves them (P-22): copies of the hub's pinned
 * {@code list-prices.yaml} and the overlays' {@code cost} blocks, bundled under
 * {@code pricing/} ({@code price-lists.yaml} holds the lists as YAML documents, {@code bindings.yaml}
 * the blocks by type). The version is the sha256 of both, so a new build with new prices re-prices.
 */
public final class BundledPricing implements PricingSourcePort {

    public static final String KIND = "bundled";

    private final PricingCatalog catalog;

    public BundledPricing(String priceListsResource, String bindingsResource) {
        byte[] lists = read(priceListsResource);
        byte[] bindings = read(bindingsResource);
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try {
            List<PriceList> priceLists = new ArrayList<>();
            for (JsonNode doc : yaml.readValues(yaml.createParser(lists), JsonNode.class).readAll()) {
                if (doc != null && !doc.isNull()) {
                    priceLists.add(PricingDocuments.priceList(doc));
                }
            }
            List<PriceBinding> bound = new ArrayList<>();
            yaml.readTree(bindings).path("types").properties().forEach(e -> {
                PriceBinding b = PricingDocuments.binding(e.getKey(), e.getValue());
                if (b != null) {
                    bound.add(b);
                }
            });
            this.catalog = new PricingCatalog(priceLists, bound, "bundled-" + sha256(lists, bindings).substring(0, 12));
        } catch (IOException e) {
            throw new UncheckedIOException("the bundled prices cannot be read", e);
        }
    }

    public static BundledPricing standard() {
        return new BundledPricing("pricing/price-lists.yaml", "pricing/bindings.yaml");
    }

    @Override
    public Mono<PricingCatalog> load() {
        return Mono.just(catalog);
    }

    @Override
    public String kind() {
        return KIND;
    }

    private static byte[] read(String resource) {
        try (InputStream in = BundledPricing.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("no " + resource + " on the class path");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] a, byte[] b) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(a);
            digest.update("\n---\n".getBytes(StandardCharsets.UTF_8));
            digest.update(b);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
