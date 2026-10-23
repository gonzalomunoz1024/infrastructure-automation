package com.iaf.console.cost.pricing.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every price list in force and every type's binding, read at one version. Prices a resource from
 * its type and attributes: the binding names the price; a per-value price looks up the attribute
 * {@code by} names, from the provider's observation when it reported one, else from the desired
 * attributes, so a resource that drifted to 8 vCPU costs what 8 vCPU cost. No binding, no list,
 * no price for the value: the resource is unpriced at 0 and says so.
 */
public final class PricingCatalog {

    /** The currency when nothing is priced at all. */
    public static final String DEFAULT_CURRENCY = "USD";

    private final Map<String, PriceList> lists;
    private final Map<String, PriceBinding> bindings;
    private final String version;

    public PricingCatalog(List<PriceList> lists, List<PriceBinding> bindings, String version) {
        this.lists = lists.stream().collect(Collectors.toUnmodifiableMap(PriceList::name, Function.identity(), (a, b) -> b));
        this.bindings = bindings.stream().collect(Collectors.toUnmodifiableMap(PriceBinding::type, Function.identity(), (a, b) -> b));
        this.version = Objects.requireNonNullElse(version, "");
    }

    public static PricingCatalog empty() {
        return new PricingCatalog(List.of(), List.of(), "empty");
    }

    public List<PriceList> lists() {
        return lists.values().stream().sorted(java.util.Comparator.comparing(PriceList::name)).toList();
    }

    public List<PriceBinding> bindings() {
        return bindings.values().stream().sorted(java.util.Comparator.comparing(PriceBinding::type)).toList();
    }

    public String version() {
        return version;
    }

    public Optional<PriceBinding> binding(String type) {
        return Optional.ofNullable(bindings.get(type));
    }

    /** The currency of the type's price list, or the default. */
    public String currencyOf(String type) {
        return binding(type).map(b -> lists.get(b.priceList())).map(PriceList::currency).orElse(DEFAULT_CURRENCY);
    }

    /** The monthly rate of one resource of a type, from its observed and desired attributes. */
    public Rate price(String type, Map<String, ?> desired, Map<String, ?> observed) {
        PriceBinding binding = bindings.get(type);
        if (binding == null) {
            return Rate.unpriced(DEFAULT_CURRENCY, null);
        }
        PriceList list = lists.get(binding.priceList());
        PriceRule bare = new PriceRule(binding.priceList(), binding.provider(), binding.action(), binding.by(), null);
        if (list == null) {
            return Rate.unpriced(DEFAULT_CURRENCY, bare);
        }
        Optional<Price> price = list.price(binding.provider(), binding.action());
        if (price.isEmpty()) {
            return Rate.unpriced(list.currency(), bare);
        }
        if (price.get().flatRate()) {
            return new Rate(Micros.of(price.get().flat()), list.currency(), true, bare, null);
        }
        String by = binding.by() != null ? binding.by() : price.get().by();
        Object observedValue = observed == null ? null : observed.get(by);
        Object value = observedValue != null ? observedValue : desired == null ? null : desired.get(by);
        String from = observedValue != null ? Rate.OBSERVED : Rate.DESIRED;
        PriceRule rule = new PriceRule(binding.priceList(), binding.provider(), binding.action(), by, value);
        java.math.BigDecimal monthly = price.get().monthlyFor(value);
        if (monthly == null) {
            return Rate.unpriced(list.currency(), rule);
        }
        return new Rate(Micros.of(monthly), list.currency(), true, rule, from);
    }

    /** The types whose price changed between two catalogs: a binding added, removed or changed, or its price changed. */
    public java.util.Set<String> typesRepricedSince(PricingCatalog before) {
        java.util.Set<String> types = new java.util.TreeSet<>();
        Map<String, PriceBinding> all = new HashMap<>(before.bindings);
        all.putAll(bindings);
        for (String type : all.keySet()) {
            PriceBinding now = bindings.get(type);
            PriceBinding then = before.bindings.get(type);
            if (!Objects.equals(now, then)) {
                types.add(type);
                continue;
            }
            Optional<Price> p1 = Optional.ofNullable(lists.get(now.priceList())).flatMap(l -> l.price(now.provider(), now.action()));
            Optional<Price> p0 = Optional.ofNullable(before.lists.get(then.priceList())).flatMap(l -> l.price(then.provider(), then.action()));
            if (!p1.equals(p0)) {
                types.add(type);
            }
        }
        return types;
    }
}
