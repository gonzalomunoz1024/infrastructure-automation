package com.iaf.console.drift.scorecards.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Every rule that applies to one resource and how it stands: one evaluation per resource and
 * rule. {@code version} grows with every stored change and every write is conditional on it.
 * {@code observed} is what the provider last reported, kept so a change of the desired state is
 * scored against it; {@code inputDigest} and {@code packsDigest} let an unchanged read skip the
 * engine altogether.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceEvaluation(String wfrn, String accountId, String region, String type, String name, long version, String packsDigest,
        String inputDigest, ObjectNode observed, List<RuleResult> results, Instant evaluatedAt) {

    public ResourceEvaluation {
        Objects.requireNonNull(wfrn, "wfrn");
        Objects.requireNonNull(accountId, "accountId");
        results = results == null ? List.of() : List.copyOf(results);
    }

    @JsonIgnore
    public long passed() {
        return results.stream().filter(r -> r.status() == RuleStatus.PASS).count();
    }

    @JsonIgnore
    public long failed() {
        return results.stream().filter(r -> r.status() == RuleStatus.FAIL).count();
    }

    /** The {@code <pack>/<rule>} keys of the rules it fails. */
    @JsonIgnore
    public List<String> failing() {
        return results.stream().filter(r -> r.status() == RuleStatus.FAIL).map(r -> r.key().text()).toList();
    }

    public RuleResult result(RuleKey key) {
        return results.stream().filter(r -> r.key().equals(key)).findFirst().orElse(null);
    }

    /** Whether the results say the same as another's (status and message per rule, whatever the times). */
    public boolean sameResults(List<RuleResult> others) {
        if (others.size() != results.size()) {
            return false;
        }
        for (RuleResult r : others) {
            if (!r.sameAs(result(r.key()))) {
                return false;
            }
        }
        return true;
    }

    /** One rule whose status changed, from and to {@code pass}, {@code fail}, {@code error} or {@code none}. */
    public record Change(RuleKey key, String severity, String from, String to, String message) {
    }

    /** What changed between two evaluations of a resource (either may be null). */
    public static List<Change> changes(ResourceEvaluation before, ResourceEvaluation after) {
        Map<RuleKey, RuleResult> was = index(before);
        Map<RuleKey, RuleResult> now = index(after);
        List<Change> out = new ArrayList<>();
        for (Map.Entry<RuleKey, RuleResult> e : now.entrySet()) {
            RuleResult old = was.get(e.getKey());
            RuleResult r = e.getValue();
            if (old == null || old.status() != r.status()) {
                out.add(new Change(e.getKey(), r.severity().id(), old == null ? "none" : old.status().id(), r.status().id(), r.message()));
            }
        }
        for (Map.Entry<RuleKey, RuleResult> e : was.entrySet()) {
            if (!now.containsKey(e.getKey())) {
                out.add(new Change(e.getKey(), e.getValue().severity().id(), e.getValue().status().id(), "none", null));
            }
        }
        return out;
    }

    /**
     * What the account's counters gain and lose when a resource's evaluation goes from
     * {@code before} to {@code after} (either may be null): one per rule, and one for the resource
     * as a whole under {@link RuleKey#RESOURCES}.
     */
    public static List<CountDelta> deltas(ResourceEvaluation before, ResourceEvaluation after) {
        Map<RuleKey, long[]> sums = new LinkedHashMap<>();
        Map<RuleKey, String> severities = new LinkedHashMap<>();
        add(before, -1, sums, severities);
        add(after, 1, sums, severities);
        List<CountDelta> out = new ArrayList<>();
        sums.forEach((key, d) -> {
            CountDelta delta = new CountDelta(key, severities.get(key), d[0], d[1]);
            if (!delta.empty()) {
                out.add(delta);
            }
        });
        return out;
    }

    private static void add(ResourceEvaluation e, int sign, Map<RuleKey, long[]> sums, Map<RuleKey, String> severities) {
        if (e == null || e.results.isEmpty()) {
            return;
        }
        for (RuleResult r : e.results) {
            if (r.status() == RuleStatus.ERROR) {
                continue;
            }
            long[] d = sums.computeIfAbsent(r.key(), k -> new long[2]);
            d[r.status() == RuleStatus.PASS ? 0 : 1] += sign;
            severities.put(r.key(), r.severity().id());
        }
        long[] d = sums.computeIfAbsent(RuleKey.RESOURCES, k -> new long[2]);
        d[e.failed() > 0 ? 1 : 0] += sign;
        severities.putIfAbsent(RuleKey.RESOURCES, "info");
    }

    private static Map<RuleKey, RuleResult> index(ResourceEvaluation e) {
        Map<RuleKey, RuleResult> out = new LinkedHashMap<>();
        if (e != null) {
            e.results.forEach(r -> out.put(r.key(), r));
        }
        return out;
    }
}
