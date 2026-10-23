package com.iaf.console.bff.demo.cost.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.bff.demo.cost.domain.DemoPrices;
import com.iaf.console.bff.demo.directory.application.DemoDirectory;
import com.iaf.console.bff.demo.directory.domain.DemoAccount;
import com.iaf.console.bff.demo.state.application.DemoStateStore;
import com.iaf.console.bff.live.application.ConsoleEventListener;
import com.iaf.console.bff.live.domain.ConsoleEvents;
import com.iaf.console.bff.live.domain.LiveHub;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.wfrn.Ulid;

/**
 * The cost service's ledger and budgets for the demo, over the demo state stand-in: every live
 * resource is charged its list price ({@link DemoPrices}) from the day it was created, a month's
 * spend counted day by day. A budget's spend is the monthly run rate of the resources in its scope
 * (deterministic, so a demo budget is over as soon as its amount is below what its scope runs at),
 * and a threshold crossed is published to the live hub as the cost service's
 * {@code BudgetThresholdCrossed} would be, through the same mapping Kafka's events take, and to the
 * console event listeners (the notifications) as Kafka's consumer hands it on.
 */
public class DemoCostLedger {

    private static final Pattern GROUP_BY = Pattern.compile("^(service|type|account|region|costCentre|tag:[a-z0-9-]{1,32})$");
    private static final Pattern MONTH = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");
    private static final Pattern TAG_KEY = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final int MAX_DAYS = 400;

    private final DemoStateStore store;
    private final DemoDirectory directory;
    private final LiveHub hub;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<String, Budget> budgets = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();
    private final Supplier<List<ConsoleEventListener>> listeners;

    public DemoCostLedger(DemoStateStore store, DemoDirectory directory, LiveHub hub, ObjectMapper json, Clock clock) {
        this(store, directory, hub, json, clock, List::of);
    }

    public DemoCostLedger(DemoStateStore store, DemoDirectory directory, LiveHub hub, ObjectMapper json, Clock clock,
            Supplier<List<ConsoleEventListener>> listeners) {
        this.store = store;
        this.directory = directory;
        this.hub = hub;
        this.json = json;
        this.clock = clock;
        this.listeners = listeners;
    }

    /** One budget as the demo keeps it. */
    static final class Budget {
        final String budgetId;
        final String accountId;
        final String tagKey;
        final String tagValue;
        final String createdBy;
        final Instant createdAt;
        String name;
        double amount;
        String currency;
        List<Integer> thresholds;
        boolean blockCreates;
        long version;
        Instant updatedAt;
        final Map<String, List<Integer>> alerted = new LinkedHashMap<>();

        Budget(String budgetId, String accountId, String tagKey, String tagValue, String createdBy, Instant createdAt) {
            this.budgetId = budgetId;
            this.accountId = accountId;
            this.tagKey = tagKey;
            this.tagValue = tagValue;
            this.createdBy = createdBy;
            this.createdAt = createdAt;
        }

        boolean covers(Map<String, String> tags) {
            return tagKey == null || (tags != null && tagValue.equals(tags.get(tagKey)));
        }
    }

    // ---- Cost Explorer

    /** The report of {@code GET /v1/cost} over the named accounts. */
    public synchronized Map<String, Object> report(List<String> accounts, Map<String, String> params, Map<String, String> tags) {
        LocalDate today = today();
        LocalDate from = date("from", params.get("from"));
        LocalDate to = date("to", params.get("to"));
        if (to.isBefore(from) || from.plusDays(MAX_DAYS).isBefore(to)) {
            throw invalid("range", "to", "to is on or after from, at most " + MAX_DAYS + " days later");
        }
        String granularity = params.getOrDefault("granularity", "day");
        if (!granularity.equals("day") && !granularity.equals("month")) {
            throw invalid("enum", "granularity", "day or month");
        }
        String groupBy = params.get("groupBy");
        if (groupBy != null && !GROUP_BY.matcher(groupBy).matches()) {
            throw invalid("pattern", "groupBy", "service, type, account, region, costCentre or tag:<key>");
        }
        int top = params.get("top") == null ? 10 : Integer.parseInt(params.get("top"));
        if (top < 1 || top > 50) {
            throw invalid("range", "top", "top is 1 to 50");
        }
        List<StateResource> resources = select(accounts, params, tags);
        Map<LocalDate, Map<String, Double>> points = new TreeMap<>();
        Map<String, Double> groupTotals = new LinkedHashMap<>();
        double total = 0;
        for (LocalDate day = from; !day.isAfter(to) && !day.isAfter(today); day = day.plusDays(1)) {
            points.computeIfAbsent(bucket(day, granularity), d -> new LinkedHashMap<>());
        }
        for (StateResource r : resources) {
            Double monthly = DemoPrices.monthly(r.type(), r.attributes());
            if (monthly == null) {
                continue;
            }
            LocalDate start = startOf(r, from);
            for (LocalDate day = start; !day.isAfter(to) && !day.isAfter(today); day = day.plusDays(1)) {
                double amount = monthly / day.lengthOfMonth();
                String key = groupBy == null ? "" : groupKey(r, groupBy);
                points.computeIfAbsent(bucket(day, granularity), d -> new LinkedHashMap<>()).merge(key, amount, Double::sum);
                groupTotals.merge(key, amount, Double::sum);
                total += amount;
            }
        }
        List<String> kept = groupTotals.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(top)
                .map(Map.Entry::getKey).toList();
        List<Map<String, Object>> groups = new ArrayList<>();
        if (groupBy != null) {
            double other = 0;
            for (Map.Entry<String, Double> e : groupTotals.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).toList()) {
                if (kept.contains(e.getKey())) {
                    groups.add(group(e.getKey(), label(e.getKey(), groupBy), e.getValue()));
                } else {
                    other += e.getValue();
                }
            }
            if (other > 0) {
                groups.add(group("__other", "Other", other));
            }
        }
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<String, Double>> p : points.entrySet()) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("start", p.getKey().toString());
            double sum = p.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
            point.put("total", round(sum));
            Map<String, Object> byGroup = new LinkedHashMap<>();
            if (groupBy != null) {
                double other = 0;
                for (Map.Entry<String, Double> g : p.getValue().entrySet()) {
                    if (kept.contains(g.getKey())) {
                        byGroup.put(g.getKey(), round(g.getValue()));
                    } else {
                        other += g.getValue();
                    }
                }
                if (other > 0) {
                    byGroup.put("__other", round(other));
                }
            }
            point.put("groups", byGroup);
            if (!p.getKey().isBefore(granularity.equals("day") ? today : today.withDayOfMonth(1))) {
                point.put("provisional", true);
            }
            series.add(point);
        }
        double runRate = 0;
        int charged = 0;
        Set<String> unpricedTypes = new LinkedHashSet<>();
        int unpriced = 0;
        for (StateResource r : resources) {
            Double monthly = DemoPrices.monthly(r.type(), r.attributes());
            if (monthly == null) {
                unpriced++;
                unpricedTypes.add(r.type());
            } else {
                runRate += monthly;
                charged++;
            }
        }
        double monthToDate = 0;
        for (StateResource r : resources) {
            monthToDate += monthToDate(r, today);
        }
        double left = (double) (today.lengthOfMonth() - today.getDayOfMonth()) / today.lengthOfMonth();
        double monthEnd = monthToDate + runRate * left;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", DemoPrices.CURRENCY);
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("granularity", granularity);
        if (groupBy != null) {
            out.put("groupBy", groupBy);
        }
        out.put("total", round(total));
        out.put("groups", groups);
        out.put("series", series);
        out.put("forecast", Map.of("month", YearMonth.from(today).toString(), "monthToDate", round(monthToDate), "monthEnd", round(monthEnd),
                "low", round(monthEnd * 0.95), "high", round(monthEnd * 1.05), "method", "run-rate"));
        out.put("runRate", Map.of("monthly", round(runRate), "resources", charged));
        out.put("asOf", clock.instant().toString());
        out.put("closedThrough", today.minusDays(1).toString());
        out.put("source", "list-prices");
        out.put("unpriced", Map.of("resources", unpriced, "types", List.copyOf(unpricedTypes)));
        if ("account".equals(groupBy) || "costCentre".equals(groupBy)) {
            List<Map<String, Object>> named = new ArrayList<>();
            for (String a : accounts) {
                Map<String, Object> account = new LinkedHashMap<>();
                account.put("accountId", a);
                directory.account(a).ifPresent(d -> {
                    account.put("name", d.name());
                    account.put("costCentre", d.owningTeam());
                });
                named.add(account);
            }
            out.put("accounts", named);
        }
        return out;
    }

    /** {@code GET /v1/wfrn/{wfrn}/cost}: one resource's rate, month to date and price. */
    public synchronized Map<String, Object> resourceCost(String accountId, String wfrn) {
        StateResource r = store.snapshot(accountId, null).stream().filter(x -> x.wfrn().equals(wfrn)).findFirst()
                .orElseThrow(() -> NotFoundException.of("resource", wfrn));
        Double monthly = r.live() ? DemoPrices.monthly(r.type(), r.attributes()) : null;
        DemoPrices.Price price = DemoPrices.priceOf(r.type());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wfrn", r.wfrn());
        out.put("accountId", r.accountId());
        out.put("type", r.type());
        out.put("service", r.service());
        out.put("state", r.state());
        out.put("charged", r.live());
        out.put("monthly", monthly == null ? 0.0 : monthly);
        out.put("monthToDate", round(monthToDate(r, today())));
        out.put("currency", DemoPrices.CURRENCY);
        if (r.createdAt() != null) {
            out.put("since", r.createdAt().toString());
        }
        out.put("source", monthly == null ? "unpriced" : "list-prices");
        if (price != null) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("priceList", DemoPrices.PRICE_LIST);
            rule.put("provider", price.provider());
            rule.put("action", price.action());
            rule.put("by", price.by());
            String value = DemoPrices.valueOf(price, r.attributes());
            if (value != null) {
                rule.put("value", value);
            }
            out.put("rule", rule);
        }
        out.put("pricedFrom", "desired");
        out.put("history", monthly == null || r.createdAt() == null ? List.of() : List.of(Map.of("from", r.createdAt().toString(), "monthly", monthly)));
        return out;
    }

    /** {@code GET /v1/price-lists}. */
    public Map<String, Object> priceLists() {
        List<Map<String, Object>> prices = new ArrayList<>();
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (DemoPrices.Price p : DemoPrices.PRICES) {
            prices.add(Map.of("provider", p.provider(), "action", p.action(), "by", p.by(), "monthly", p.byValue()));
            bindings.add(Map.of("type", p.type(), "priceList", DemoPrices.PRICE_LIST, "provider", p.provider(), "action", p.action(), "by", p.by()));
        }
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("name", DemoPrices.PRICE_LIST);
        list.put("title", "List prices");
        list.put("currency", DemoPrices.CURRENCY);
        list.put("version", "demo");
        list.put("prices", prices);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", List.of(list));
        out.put("next", null);
        out.put("bindings", bindings);
        return out;
    }

    /** {@code POST /v1/cost:estimate}. */
    public Map<String, Object> estimate(String type, JsonNode attributes, int count) {
        if (type == null || type.isBlank()) {
            throw invalid("required", "/type", "the resource type");
        }
        if (count < 1 || count > 10_000) {
            throw invalid("range", "/count", "1 to 10000");
        }
        Double unit = DemoPrices.monthly(type, attributes);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("monthly", unit == null ? 0.0 : round(unit * count));
        out.put("unitMonthly", unit == null ? 0.0 : unit);
        out.put("currency", DemoPrices.CURRENCY);
        out.put("source", unit == null ? "unpriced" : "list-prices");
        out.put("detail", unit == null ? "No list price for " + type + ", so it counts as $0.00"
                : (count == 1 ? "$" + String.format(java.util.Locale.US, "%.2f", unit) + " a month (list prices)"
                        : count + " × $" + String.format(java.util.Locale.US, "%.2f", unit) + " a month (list prices)"));
        return out;
    }

    // ---- chargeback

    /** A month's chargeback rows over the named accounts, and its CSV. */
    public record Chargeback(Map<String, Object> report, String csv) {
    }

    public synchronized Chargeback chargeback(List<String> accounts, String month, String tag) {
        if (month == null || !MONTH.matcher(month).matches()) {
            throw invalid("pattern", "month", "a month, e.g. 2026-10");
        }
        if (tag != null && !TAG_KEY.matcher(tag).matches()) {
            throw invalid("pattern", "tag", "a tag key of [a-z0-9-]{1,32}");
        }
        YearMonth ym = YearMonth.parse(month);
        LocalDate today = today();
        Map<List<String>, Double> sums = new LinkedHashMap<>();
        for (String accountId : accounts) {
            for (StateResource r : store.snapshot(accountId, null)) {
                Double monthly = r.live() ? DemoPrices.monthly(r.type(), r.attributes()) : null;
                if (monthly == null) {
                    continue;
                }
                double amount = 0;
                for (LocalDate day = ym.atDay(1); !day.isAfter(ym.atEndOfMonth()) && !day.isAfter(today); day = day.plusDays(1)) {
                    if (!day.isBefore(startOf(r, ym.atDay(1)))) {
                        amount += monthly / day.lengthOfMonth();
                    }
                }
                if (amount > 0) {
                    String tagValue = tag == null ? null : r.tags().getOrDefault(tag, "__none");
                    sums.merge(java.util.Arrays.asList(costCentre(r), accountId, r.service(), tagValue), amount, Double::sum);
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        StringBuilder csv = new StringBuilder("month,costCentre,accountId,accountName,service,tagKey,tagValue,amount,currency\n");
        double total = 0;
        for (Map.Entry<List<String>, Double> e : sums.entrySet().stream().sorted(Comparator.comparing(e -> String.join("|", e.getKey().stream()
                .map(v -> v == null ? "" : v).toList()))).toList()) {
            List<String> k = e.getKey();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("costCentre", k.get(0));
            row.put("accountId", k.get(1));
            String accountName = directory.account(k.get(1)).map(DemoAccount::name).orElse(k.get(1));
            row.put("accountName", accountName);
            row.put("service", k.get(2));
            if (tag != null) {
                row.put("tag", Map.of("key", tag, "value", k.get(3)));
            }
            row.put("amount", round(e.getValue()));
            rows.add(row);
            total += e.getValue();
            csv.append(month).append(',').append(k.get(0)).append(',').append(k.get(1)).append(',').append(accountName).append(',').append(k.get(2))
                    .append(',').append(tag == null ? "" : tag).append(',').append(k.get(3) == null ? "" : k.get(3)).append(',')
                    .append(String.format(java.util.Locale.US, "%.2f", round(e.getValue()))).append(',').append(DemoPrices.CURRENCY).append('\n');
        }
        boolean closed = ym.isBefore(YearMonth.from(today));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("month", month);
        report.put("currency", DemoPrices.CURRENCY);
        report.put("final", closed);
        report.put("total", round(total));
        report.put("rows", rows);
        report.put("generatedAt", clock.instant().toString());
        if (closed) {
            report.put("sha256", sha256(csv.toString()));
        }
        return new Chargeback(report, csv.toString());
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- budgets

    /** The account's budgets, by name, each with this month's status. */
    public synchronized List<Map<String, Object>> budgets(String accountId) {
        return budgets.values().stream().filter(b -> b.accountId.equals(accountId)).sorted(Comparator.comparing(b -> b.name))
                .map(this::view).toList();
    }

    public synchronized Map<String, Object> budget(String accountId, String budgetId) {
        return view(own(accountId, budgetId));
    }

    /** A new budget; a repeat with the same idempotency key answers the first one. */
    public synchronized Map<String, Object> create(String accountId, JsonNode body, String actor, String idempotencyKey) {
        if (idempotencyKey != null && idempotency.containsKey(accountId + "|" + idempotencyKey)) {
            return budget(accountId, idempotency.get(accountId + "|" + idempotencyKey));
        }
        JsonNode scope = body.path("scope");
        String tagKey = null;
        String tagValue = null;
        List<Problem> problems = new ArrayList<>();
        if (scope.path("tag").isObject()) {
            tagKey = scope.path("tag").path("key").asText("");
            tagValue = scope.path("tag").path("value").asText("");
            if (!TAG_KEY.matcher(tagKey).matches()) {
                problems.add(Problem.error("pattern", "/scope/tag/key", "a tag key of [a-z0-9-]{1,32}"));
            }
            if (tagValue.isEmpty() || tagValue.length() > 256) {
                problems.add(Problem.error("length", "/scope/tag/value", "1 to 256 characters"));
            }
        }
        Budget b = new Budget("bud_" + Ulid.next().toLowerCase(java.util.Locale.ROOT), accountId, tagKey, tagValue, actor, clock.instant());
        apply(b, body, problems);
        b.version = 1;
        b.updatedAt = b.createdAt;
        budgets.put(b.budgetId, b);
        if (idempotencyKey != null) {
            idempotency.put(accountId + "|" + idempotencyKey, b.budgetId);
        }
        return view(b);
    }

    public synchronized Map<String, Object> replace(String accountId, String budgetId, long expected, JsonNode body) {
        Budget b = own(accountId, budgetId);
        if (b.version != expected) {
            throw new ConflictException("the budget changed since it was read: version " + b.version, Map.of("version", b.version));
        }
        apply(b, body, new ArrayList<>());
        b.version++;
        b.updatedAt = clock.instant();
        return view(b);
    }

    public synchronized void delete(String accountId, String budgetId, Long expected) {
        Budget b = own(accountId, budgetId);
        if (expected != null && b.version != expected) {
            throw new ConflictException("the budget changed since it was read: version " + b.version, Map.of("version", b.version));
        }
        budgets.remove(budgetId);
    }

    public synchronized List<Map<String, Object>> periods(String accountId, String budgetId) {
        return List.of(status(own(accountId, budgetId)));
    }

    /** {@code POST /v1/budgets:check}: deny while a covering budget that blocks creates is over, warn when its forecast with the create goes over. */
    public synchronized Map<String, Object> check(String accountId, Map<String, String> tags, double monthly) {
        List<Map<String, Object>> rules = new ArrayList<>();
        double added = monthly * (double) (today().lengthOfMonth() - today().getDayOfMonth() + 1) / today().lengthOfMonth();
        for (Budget b : budgets.values().stream().filter(x -> x.accountId.equals(accountId) && x.covers(tags)).sorted(Comparator.comparing(x -> x.name)).toList()) {
            double spent = spent(b);
            Map<String, Object> rule = new LinkedHashMap<>();
            if (b.blockCreates && spent >= b.amount) {
                rule.put("id", "budget.exceeded");
                rule.put("result", "deny");
                rule.put("message", "The budget " + b.name + " is over: " + money(spent) + " of " + money(b.amount) + " spent in " + YearMonth.from(today())
                        + ", so creates in its scope are refused");
            } else if (spent + added > b.amount) {
                rule.put("id", "budget.forecast-over");
                rule.put("result", "warn");
                rule.put("message", "The budget " + b.name + " is forecast to reach " + money(spent + added) + " of " + money(b.amount) + " in "
                        + YearMonth.from(today()) + (added > 0 ? " with this create" : ""));
            } else {
                continue;
            }
            rule.put("budgetId", b.budgetId);
            rule.put("budgetName", b.name);
            rule.put("percent", round(spent / b.amount * 100));
            rules.add(rule);
        }
        boolean deny = rules.stream().anyMatch(r -> "deny".equals(r.get("result")));
        return Map.of("decision", deny ? "deny" : "allow", "rules", rules);
    }

    private void apply(Budget b, JsonNode body, List<Problem> problems) {
        String name = body.path("name").asText("").strip();
        if (name.isEmpty() || name.length() > 64) {
            problems.add(Problem.error("required", "/name", "a name of 1 to 64 characters"));
        }
        JsonNode amount = body.path("amount");
        if (!amount.isNumber() || amount.asDouble() <= 0 || amount.asDouble() > 1_000_000_000) {
            problems.add(Problem.error("range", "/amount", "the monthly amount, more than 0 and at most 1000000000"));
        }
        String currency = body.path("currency").asText(DemoPrices.CURRENCY);
        if (!CURRENCY.matcher(currency).matches()) {
            problems.add(Problem.error("pattern", "/currency", "an ISO 4217 code, e.g. USD"));
        }
        List<Integer> thresholds = new ArrayList<>();
        if (body.has("thresholds")) {
            for (JsonNode t : body.path("thresholds")) {
                if (!t.canConvertToInt() || t.asInt() < 1 || t.asInt() > 1000 || thresholds.contains(t.asInt())) {
                    problems.add(Problem.error("range", "/thresholds", "1 to 5 distinct percentages of 1 to 1000"));
                    break;
                }
                thresholds.add(t.asInt());
            }
            if (thresholds.isEmpty() || thresholds.size() > 5) {
                problems.add(Problem.error("range", "/thresholds", "1 to 5 distinct percentages of 1 to 1000"));
            }
        } else {
            thresholds = List.of(80, 100);
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException(problems.get(0).message(), problems);
        }
        b.name = name;
        b.amount = amount.asDouble();
        b.currency = currency;
        b.thresholds = thresholds.stream().sorted().toList();
        b.blockCreates = body.path("blockCreates").asBoolean(false);
    }

    private Budget own(String accountId, String budgetId) {
        Budget b = budgets.get(budgetId);
        if (b == null || !b.accountId.equals(accountId)) {
            throw NotFoundException.of("budget", budgetId);
        }
        return b;
    }

    private Map<String, Object> view(Budget b) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("accountId", b.accountId);
        if (b.tagKey != null) {
            scope.put("tag", Map.of("key", b.tagKey, "value", b.tagValue));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("budgetId", b.budgetId);
        out.put("name", b.name);
        out.put("scope", scope);
        out.put("amount", b.amount);
        out.put("currency", b.currency);
        out.put("thresholds", b.thresholds);
        out.put("blockCreates", b.blockCreates);
        out.put("version", b.version);
        out.put("createdBy", b.createdBy);
        out.put("createdAt", b.createdAt.toString());
        out.put("updatedAt", b.updatedAt.toString());
        out.put("status", status(b));
        return out;
    }

    /** This month's status; a threshold crossed for the first time this month is published as the cost service would. */
    private Map<String, Object> status(Budget b) {
        String period = YearMonth.from(today()).toString();
        double spent = spent(b);
        double percent = round(spent / b.amount * 100);
        List<Integer> sent = b.alerted.computeIfAbsent(period, p -> new ArrayList<>());
        for (int threshold : b.thresholds) {
            if (percent >= threshold && !sent.contains(threshold)) {
                sent.add(threshold);
                publish(b, period, threshold, percent, spent);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("amount", b.amount);
        out.put("spent", round(spent));
        out.put("forecast", round(spent));
        out.put("percent", percent);
        out.put("forecastPercent", percent);
        out.put("state", spent >= b.amount ? "over" : "ok");
        out.put("alertsSent", List.copyOf(sent));
        out.put("asOf", clock.instant().toString());
        return out;
    }

    private void publish(Budget b, String period, int threshold, double percent, double spent) {
        ObjectNode data = json.createObjectNode();
        data.put("version", b.version);
        data.put("budgetId", b.budgetId);
        data.put("name", b.name);
        data.put("period", period);
        data.put("threshold", threshold);
        data.put("percent", percent);
        data.put("spent", round(spent));
        data.put("amount", b.amount);
        data.put("forecast", round(spent));
        data.put("currency", b.currency);
        data.put("blockCreates", b.blockCreates && threshold >= 100);
        String key = ConsoleEvents.BUDGET_THRESHOLD_CROSSED + ":" + b.accountId + ":" + b.budgetId + ":" + period + "-" + threshold;
        EventEnvelope envelope = new EventEnvelope(EventEnvelope.SPEC_VERSION, 1, "evt_" + Ulid.next().toLowerCase(java.util.Locale.ROOT), key, null,
                ConsoleEvents.BUDGET_THRESHOLD_CROSSED, clock.instant(), "budget", b.budgetId, "console-cost-service", null,
                new EventEnvelope.Context(b.accountId, null, null, null), null, data);
        ConsoleEvents.toLive(envelope).ifPresent(mapped -> hub.publish(mapped.accountId(), mapped.event()));
        for (ConsoleEventListener listener : listeners.get()) {
            try {
                listener.onConsoleEvent(envelope);
            } catch (RuntimeException e) {
                // a listener's failure is its own; the ledger carries on as the cost service would
            }
        }
    }

    /** The run rate of the live resources in a budget's scope: the demo's spend. */
    private double spent(Budget b) {
        double sum = 0;
        for (StateResource r : store.snapshot(b.accountId, null)) {
            if (r.live() && b.covers(r.tags())) {
                Double monthly = DemoPrices.monthly(r.type(), r.attributes());
                sum += monthly == null ? 0 : monthly;
            }
        }
        return sum;
    }

    // ---- pieces

    private List<StateResource> select(List<String> accounts, Map<String, String> params, Map<String, String> tags) {
        List<StateResource> out = new ArrayList<>();
        for (String accountId : accounts) {
            for (StateResource r : store.snapshot(accountId, null)) {
                if (!r.live()) {
                    continue;
                }
                if (params.get("service") != null && !params.get("service").equals(r.service())) {
                    continue;
                }
                if (params.get("type") != null && !params.get("type").equals(r.type())) {
                    continue;
                }
                if (params.get("region") != null && !params.get("region").equals(r.region())) {
                    continue;
                }
                if (params.get("wfrn") != null && !params.get("wfrn").equals(r.wfrn())) {
                    continue;
                }
                if (tags.entrySet().stream().anyMatch(t -> !t.getValue().equals(r.tags().get(t.getKey())))) {
                    continue;
                }
                out.add(r);
            }
        }
        return out;
    }

    private String groupKey(StateResource r, String groupBy) {
        return switch (groupBy) {
            case "service" -> r.service();
            case "type" -> r.type();
            case "account" -> r.accountId();
            case "region" -> r.region();
            case "costCentre" -> costCentre(r);
            default -> r.tags().getOrDefault(groupBy.substring(4), "__none");
        };
    }

    private String label(String key, String groupBy) {
        if ("__none".equals(key)) {
            return "No " + groupBy.substring(4) + " tag";
        }
        if ("account".equals(groupBy)) {
            return directory.account(key).map(DemoAccount::name).orElse(key);
        }
        return key;
    }

    /** The resource's cost-centre tag, else its account's owning team, as the cost service falls back. */
    private String costCentre(StateResource r) {
        String tagged = r.tags().get("cost-centre");
        return tagged != null ? tagged : directory.account(r.accountId()).map(DemoAccount::owningTeam).orElse("unassigned");
    }

    private static Map<String, Object> group(String key, String label, double total) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("key", key);
        g.put("label", label);
        g.put("total", round(total));
        return g;
    }

    private double monthToDate(StateResource r, LocalDate today) {
        Double monthly = DemoPrices.monthly(r.type(), r.attributes());
        if (monthly == null || !r.live()) {
            return 0;
        }
        LocalDate start = startOf(r, today.withDayOfMonth(1));
        long days = start.isAfter(today) ? 0 : today.toEpochDay() - start.toEpochDay() + 1;
        return monthly * days / today.lengthOfMonth();
    }

    private static LocalDate startOf(StateResource r, LocalDate floor) {
        LocalDate created = r.createdAt() == null ? floor : r.createdAt().atZone(ZoneOffset.UTC).toLocalDate();
        return created.isBefore(floor) ? floor : created;
    }

    private static LocalDate bucket(LocalDate day, String granularity) {
        return "month".equals(granularity) ? day.withDayOfMonth(1) : day;
    }

    private LocalDate today() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) {
            throw invalid("required", name, name + " is required, a date like 2026-10-01");
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw invalid("format", name, name + " is a date like 2026-10-01");
        }
    }

    private static String money(double amount) {
        return "$" + String.format(java.util.Locale.US, "%,.2f", amount);
    }

    static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static InvalidInputException invalid(String code, String field, String message) {
        return new InvalidInputException(message, List.of(Problem.error(code, field, message)));
    }
}
