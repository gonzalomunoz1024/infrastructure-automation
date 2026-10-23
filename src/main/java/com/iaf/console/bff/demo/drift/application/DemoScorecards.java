package com.iaf.console.bff.demo.drift.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.bff.demo.state.application.DemoStateStore;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;

/**
 * The drift service's scorecards for the demo, judged on the fly over the demo state stand-in with
 * one rule pack, {@code baseline}: every resource names its owner and its cost centre (warning and
 * critical, each with a one-click tags fix) and has a name of at least three characters (info, no
 * fix). Scores are weighted as the contract says (critical 10, warning 3, info 1); the trend is
 * today's score only. A tags fix sets the tag on the demo state stand-in, as the person.
 */
public class DemoScorecards {

    public static final String PACK = "baseline";
    private static final int CRITICAL = 10;
    private static final int WARNING = 3;
    private static final int INFO = 1;

    /** One rule of the demo pack. */
    public record Rule(String id, String title, String severity, String tag) {

        boolean passes(StateResource r) {
            return tag != null ? r.tags().containsKey(tag) && !r.tags().get(tag).isBlank() : r.name() != null && r.name().length() >= 3;
        }

        boolean remediable() {
            return tag != null;
        }

        int weight() {
            return switch (severity) {
                case "critical" -> CRITICAL;
                case "warning" -> WARNING;
                default -> INFO;
            };
        }
    }

    public static final List<Rule> RULES = List.of(
            new Rule("owner-tag", "Resources name their owner", "warning", "owner"),
            new Rule("cost-centre-tag", "Resources name their cost centre", "critical", "cost-centre"),
            new Rule("meaningful-name", "Resources have a meaningful name", "info", null));

    private final DemoStateStore store;
    private final ObjectMapper json;
    private final Clock clock;

    public DemoScorecards(DemoStateStore store, ObjectMapper json, Clock clock) {
        this.store = store;
        this.json = json;
        this.clock = clock;
    }

    /** {@code GET /v1/scorecards/packs}. */
    public Map<String, Object> packs() {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Rule rule : RULES) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rule.id());
            r.put("title", rule.title());
            r.put("severity", rule.severity());
            r.put("types", List.of("vm", "database", "volume", "image", "environment"));
            if (rule.remediable()) {
                r.put("remediation", Map.of("title", "Set the " + rule.tag() + " tag", "kind", "tags", "inputs",
                        List.of(Map.of("name", rule.tag(), "title", rule.tag(), "type", "string", "required", true))));
            }
            rules.add(r);
        }
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("name", PACK);
        pack.put("title", "Baseline");
        pack.put("description", "What every resource of the demo should carry");
        pack.put("version", 1);
        pack.put("owner", "team-platform");
        pack.put("rules", rules);
        return Map.of("items", List.of(pack));
    }

    /** {@code GET /v1/scorecards/account}. */
    public Map<String, Object> scorecard(String accountId) {
        List<StateResource> resources = live(accountId);
        int[] all = new int[2];
        Map<String, int[]> bySeverity = new LinkedHashMap<>();
        for (String s : List.of("critical", "warning", "info")) {
            bySeverity.put(s, new int[2]);
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        int failingResources = 0;
        for (StateResource r : resources) {
            if (RULES.stream().anyMatch(rule -> !rule.passes(r))) {
                failingResources++;
            }
        }
        for (Rule rule : RULES) {
            int passed = (int) resources.stream().filter(rule::passes).count();
            int failed = resources.size() - passed;
            all[0] += passed;
            all[1] += failed;
            bySeverity.get(rule.severity())[0] += passed;
            bySeverity.get(rule.severity())[1] += failed;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rule.id());
            r.put("title", rule.title());
            r.put("severity", rule.severity());
            r.put("passed", passed);
            r.put("failed", failed);
            r.put("score", passed + failed == 0 ? null : Math.round(100.0 * passed / (passed + failed)));
            r.put("remediable", rule.remediable());
            rules.add(r);
        }
        Integer score = score(resources);
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("name", PACK);
        pack.put("title", "Baseline");
        pack.put("version", 1);
        pack.put("passed", all[0]);
        pack.put("failed", all[1]);
        pack.put("score", score);
        pack.put("rules", rules);
        Map<String, Object> severities = new LinkedHashMap<>();
        bySeverity.forEach((s, c) -> severities.put(s, Map.of("passed", c[0], "failed", c[1])));
        Map<String, Object> trendPoint = new LinkedHashMap<>();
        trendPoint.put("day", LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString());
        trendPoint.put("score", score);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        out.put("score", score);
        out.put("passed", all[0]);
        out.put("failed", all[1]);
        out.put("resources", resources.size());
        out.put("failingResources", failingResources);
        out.put("bySeverity", severities);
        out.put("packs", List.of(pack));
        out.put("trend", List.of(trendPoint));
        out.put("evaluatedAt", clock.instant().toString());
        return out;
    }

    /** {@code GET /v1/scorecards/heatmap}: one row per named account. */
    public Map<String, Object> heatmap(List<String> accountIds) {
        if (accountIds.isEmpty() || accountIds.size() > 500) {
            throw new InvalidInputException("accountIds is 1 to 500 accounts", List.of(Problem.error("range", "accountIds", "1 to 500 accounts")));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String accountId : accountIds) {
            List<StateResource> resources = live(accountId);
            Integer score = score(resources);
            int failed = 0;
            for (Rule rule : RULES) {
                failed += (int) resources.stream().filter(r -> !rule.passes(r)).count();
            }
            Map<String, Object> packs = new LinkedHashMap<>();
            packs.put(PACK, score);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("accountId", accountId);
            row.put("score", score);
            row.put("failed", failed);
            row.put("packs", packs);
            rows.add(row);
        }
        return Map.of("packs", List.of(Map.of("name", PACK, "title", "Baseline")), "rows", rows);
    }

    /** {@code GET /v1/scorecards/packs/{pack}/rules/{ruleId}/failing}. */
    public Map<String, Object> failing(String accountId, String pack, String ruleId, String type, int limit) {
        Rule rule = rule(pack, ruleId);
        List<Map<String, Object>> items = live(accountId).stream().filter(r -> type == null || type.equals(r.type())).filter(r -> !rule.passes(r))
                .sorted(Comparator.comparing(StateResource::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(limit).map(r -> evaluation(r, rule)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("next", null);
        return out;
    }

    /** {@code GET /v1/scorecards/resources?wfrn=}. */
    public Map<String, Object> resource(String accountId, String wfrn) {
        StateResource r = live(accountId).stream().filter(x -> x.wfrn().equals(wfrn)).findFirst().orElseThrow(() -> NotFoundException.of("resource", wfrn));
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        for (Rule rule : RULES.stream().sorted(Comparator.comparing(rule -> rule.passes(r))).toList()) {
            boolean ok = rule.passes(r);
            passed += ok ? 1 : 0;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pack", PACK);
            result.put("ruleId", rule.id());
            result.put("title", rule.title());
            result.put("severity", rule.severity());
            result.put("status", ok ? "pass" : "fail");
            result.put("message", message(rule, ok));
            result.put("since", since(r).toString());
            result.put("remediable", rule.remediable());
            results.add(result);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wfrn", r.wfrn());
        out.put("accountId", r.accountId());
        out.put("region", r.region());
        out.put("type", r.type());
        out.put("evaluatedAt", clock.instant().toString());
        out.put("passed", passed);
        out.put("failed", RULES.size() - passed);
        out.put("results", results);
        return out;
    }

    /**
     * {@code POST /v1/scorecards/packs/{pack}/rules/{ruleId}:remediate}: a tags fix sets the tag as
     * the person the assertion names (409 {@code remediation.needsPerson} without one); a passing
     * resource is 409 {@code evaluation.passing}, a rule without a fix {@code remediation.none}.
     */
    public Map<String, Object> remediate(String accountId, String pack, String ruleId, JsonNode body, String onBehalfOf, String actor, String key,
            String requestId) {
        Rule rule = rule(pack, ruleId);
        if (!rule.remediable()) {
            throw new ConflictException("remediation.none: the rule " + pack + "/" + ruleId + " has no one-click fix", Map.of("rule", pack + "/" + ruleId));
        }
        String wfrn = body.path("wfrn").asText("");
        StateResource r = live(accountId).stream().filter(x -> x.wfrn().equals(wfrn)).findFirst().orElseThrow(() -> NotFoundException.of("resource", wfrn));
        if (rule.passes(r)) {
            throw new ConflictException("evaluation.passing: " + wfrn + " does not fail " + pack + "/" + ruleId, Map.of("wfrn", wfrn));
        }
        if (onBehalfOf == null || onBehalfOf.isBlank()) {
            throw new ConflictException("remediation.needsPerson: a fix runs on behalf of the person who asks for it; the call needs the BFF's X-On-Behalf-Of assertion",
                    Map.of());
        }
        String value = body.path("input").path(rule.tag()).asText("").strip();
        if (value.isEmpty()) {
            throw new InvalidInputException("remediation.input: " + rule.tag() + " is required",
                    List.of(Problem.error("remediation.input", "/input/" + rule.tag(), rule.tag() + " is required")));
        }
        ObjectNode command = json.createObjectNode();
        command.putObject("set").put(rule.tag(), value);
        command.putArray("removed");
        StateResource tagged = store.tag(accountId, wfrn, command, actor, key, requestId).resource();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wfrn", wfrn);
        out.put("pack", pack);
        out.put("ruleId", ruleId);
        out.put("kind", "tags");
        out.put("tags", tagged.tags());
        return out;
    }

    private Map<String, Object> evaluation(StateResource r, Rule rule) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("wfrn", r.wfrn());
        e.put("accountId", r.accountId());
        e.put("region", r.region());
        e.put("type", r.type());
        e.put("name", r.name());
        e.put("pack", PACK);
        e.put("ruleId", rule.id());
        e.put("severity", rule.severity());
        boolean ok = rule.passes(r);
        e.put("status", ok ? "pass" : "fail");
        e.put("message", message(rule, ok));
        e.put("since", since(r).toString());
        e.put("evaluatedAt", clock.instant().toString());
        return e;
    }

    private static String message(Rule rule, boolean ok) {
        if (rule.tag() == null) {
            return ok ? "the name says what it is" : "the name is shorter than three characters";
        }
        return ok ? "carries the " + rule.tag() + " tag" : "has no " + rule.tag() + " tag";
    }

    private Instant since(StateResource r) {
        return r.updatedAt() != null ? r.updatedAt() : r.createdAt() != null ? r.createdAt() : clock.instant();
    }

    private static Rule rule(String pack, String ruleId) {
        if (!PACK.equals(pack)) {
            throw NotFoundException.of("rule pack", pack);
        }
        return RULES.stream().filter(r -> r.id().equals(ruleId)).findFirst().orElseThrow(() -> NotFoundException.of("rule", pack + "/" + ruleId));
    }

    /** The weighted score: 100 × Σ weight × passed ÷ Σ weight × (passed + failed); null while nothing is scored. */
    private static Integer score(List<StateResource> resources) {
        long earned = 0;
        long possible = 0;
        for (Rule rule : RULES) {
            for (StateResource r : resources) {
                possible += rule.weight();
                earned += rule.passes(r) ? rule.weight() : 0;
            }
        }
        return possible == 0 ? null : (int) Math.round(100.0 * earned / possible);
    }

    private List<StateResource> live(String accountId) {
        return store.snapshot(accountId, null).stream().filter(StateResource::live).toList();
    }
}
