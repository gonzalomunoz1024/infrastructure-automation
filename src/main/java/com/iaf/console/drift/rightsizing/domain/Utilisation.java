package com.iaf.console.drift.rightsizing.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How much of its size a resource used over a window ({@code Utilisation}): the 95th percentile and
 * the peak of its CPU and memory, in percent, and how many points they rest on. The 95th percentile
 * leaves out the rare spike, so one busy hour does not keep an idle resource alive; the peak is shown
 * so a person can see what a resize would have had to absorb.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Utilisation(Instant from, Instant to, int samples, Double cpuP95, Double cpuMax, Double memoryP95, Double memoryMax) {

    /**
     * The utilisation of the values read: the samples are the points both series have (the one that
     * has any, when the other is missing), and a series without points has no percentile.
     */
    public static Utilisation of(Instant from, Instant to, List<Double> cpu, List<Double> memory) {
        int samples = cpu.isEmpty() ? memory.size() : memory.isEmpty() ? cpu.size() : Math.min(cpu.size(), memory.size());
        return new Utilisation(from, to, samples, p95(cpu), max(cpu), p95(memory), max(memory));
    }

    /** Whether both percentiles are known. */
    public boolean complete() {
        return cpuP95 != null && memoryP95 != null;
    }

    /** The 95th percentile by nearest rank (the smallest value at least 95% of the values do not exceed); null when there is none. */
    public static Double p95(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(0.95 * sorted.size());
        return round(sorted.get(Math.max(0, rank - 1)));
    }

    static Double max(List<Double> values) {
        return values.isEmpty() ? null : round(Collections.max(values));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
