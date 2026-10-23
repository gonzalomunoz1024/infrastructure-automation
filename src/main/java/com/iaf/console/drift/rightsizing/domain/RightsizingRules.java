package com.iaf.console.drift.rightsizing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What a resource's utilisation says, and what that does to its recommendation. A judgement needs
 * enough points (a week at an hour a point by default) and both percentiles. Both CPU and memory
 * under the idle thresholds is {@code idle}: delete it. Otherwise each of CPU and memory under its
 * oversized threshold shrinks to the smallest allowed value at which its 95th percentile, scaled to
 * the smaller size, stays under the target (60% by default); either shrinking is {@code oversized}.
 * Pure: the thresholds come from the settings, the sizes from the pass.
 */
public final class RightsizingRules {

    /** The settings' thresholds, in percent, and the points a judgement needs. */
    public record Thresholds(double idleCpu, double idleMemory, double oversizedCpu, double oversizedMemory, double targetP95, int minSamples) {
    }

    /**
     * A resource's size as the rules see it: the size fields as they are ({@code current}), the CPU
     * and memory values among them (null when absent or not a number), and the values each may take.
     */
    public record Sizing(ObjectNode current, String cpuField, Double cpu, List<Double> cpuAllowed, String memoryField, Double memory,
            List<Double> memoryAllowed) {
    }

    /** What the rules found: the kind, what applying it does, the size now and, for a resize, the fields it sets. */
    public record Verdict(RecommendationKind kind, RecommendationAction action, ObjectNode current, ObjectNode proposed) {
    }

    /** What a pass does to a resource's recommendation. */
    public enum Move {
        /** Nothing changes. */
        KEEP,
        /** A new recommendation opens. */
        OPEN,
        /** The open one takes today's numbers. */
        REFRESH,
        /** A dismissal ran out and the resource still qualifies: open again. */
        REOPEN,
        /** The current one ends: the resource changed size, or no longer qualifies (a dismissal only once its date came). */
        SUPERSEDE,
        /** The current one ends and a new one opens: the size changed, or the kind did. */
        REPLACE
    }

    private final Thresholds t;

    public RightsizingRules(Thresholds thresholds) {
        this.t = thresholds;
    }

    public Thresholds thresholds() {
        return t;
    }

    /** Whether the utilisation rests on enough points to judge at all. */
    public boolean judged(Utilisation u) {
        return u != null && u.samples() >= t.minSamples() && u.complete();
    }

    /** Idle, oversized, or nothing to say. */
    public Optional<Verdict> judge(Utilisation u, Sizing s) {
        if (!judged(u)) {
            return Optional.empty();
        }
        if (u.cpuP95() < t.idleCpu() && u.memoryP95() < t.idleMemory()) {
            return Optional.of(new Verdict(RecommendationKind.IDLE, RecommendationAction.DELETE, s.current(), null));
        }
        ObjectNode proposed = JsonNodeFactory.instance.objectNode();
        smaller(u.cpuP95(), s.cpu(), s.cpuAllowed(), t.oversizedCpu()).ifPresent(v -> put(proposed, s.cpuField(), v));
        smaller(u.memoryP95(), s.memory(), s.memoryAllowed(), t.oversizedMemory()).ifPresent(v -> put(proposed, s.memoryField(), v));
        if (proposed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Verdict(RecommendationKind.OVERSIZED, RecommendationAction.RESIZE, s.current(), proposed));
    }

    /** The smallest allowed value below the current one at which the 95th percentile, scaled by current over it, stays under the target. */
    Optional<Double> smaller(double p95, Double current, List<Double> allowed, double threshold) {
        if (current == null || current <= 0 || p95 >= threshold || allowed == null) {
            return Optional.empty();
        }
        return allowed.stream()
                .filter(v -> v > 0 && v < current)
                .filter(v -> p95 * current / v < t.targetP95())
                .min(Double::compare);
    }

    /**
     * What happens to the resource's current recommendation (open or dismissed; null when it has
     * none): a size change ends it; without enough points nothing changes; a dismissal holds until
     * its date, or until the utilisation changes kind.
     */
    public static Move move(Recommendation current, Optional<Verdict> verdict, boolean judged, boolean sizeChanged, Instant now) {
        if (current == null || !current.active()) {
            return verdict.isPresent() ? Move.OPEN : Move.KEEP;
        }
        if (sizeChanged) {
            return verdict.isPresent() ? Move.REPLACE : Move.SUPERSEDE;
        }
        if (!judged) {
            return Move.KEEP;
        }
        boolean open = current.status() == RecommendationStatus.OPEN;
        if (verdict.isEmpty()) {
            return open || current.dismissalOver(now) ? Move.SUPERSEDE : Move.KEEP;
        }
        if (verdict.get().kind() != current.kind()) {
            return Move.REPLACE;
        }
        if (open) {
            return Move.REFRESH;
        }
        return current.dismissalOver(now) ? Move.REOPEN : Move.KEEP;
    }

    /** The size fields present in a desired state, as they are. */
    public static ObjectNode sizeOf(ObjectNode desired, String... fields) {
        ObjectNode size = JsonNodeFactory.instance.objectNode();
        if (desired != null) {
            for (String field : fields) {
                JsonNode v = desired.get(field);
                if (field != null && v != null && !v.isNull()) {
                    size.set(field, v.deepCopy());
                }
            }
        }
        return size;
    }

    /** Whether two sizes hold the same fields with the same values, numbers compared by value (4 and 4.0 are one size). */
    public static boolean sameSize(ObjectNode a, ObjectNode b) {
        ObjectNode x = a == null ? JsonNodeFactory.instance.objectNode() : a;
        ObjectNode y = b == null ? JsonNodeFactory.instance.objectNode() : b;
        if (x.size() != y.size()) {
            return false;
        }
        for (var entry : x.properties()) {
            JsonNode mine = entry.getValue();
            JsonNode other = y.get(entry.getKey());
            if (other == null) {
                return false;
            }
            boolean same = mine.isNumber() && other.isNumber() ? mine.decimalValue().compareTo(other.decimalValue()) == 0 : mine.equals(other);
            if (!same) {
                return false;
            }
        }
        return true;
    }

    /** A size field's value as a number; null when it is absent or not a number. */
    public static Double number(ObjectNode desired, String field) {
        JsonNode v = desired == null || field == null ? null : desired.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isTextual()) {
            try {
                return Double.parseDouble(v.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static void put(ObjectNode node, String field, double value) {
        if (value == Math.rint(value) && Math.abs(value) < Long.MAX_VALUE) {
            node.put(field, (long) value);
        } else {
            node.put(field, value);
        }
    }
}
