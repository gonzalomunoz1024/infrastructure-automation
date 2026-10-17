package com.iaf.platform.workflows.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Semantic versions and the small range syntax {@code run.version} accepts: ^1, ~1.2, 1.2.0, *. */
public record Semver(int major, int minor, int patch) implements Comparable<Semver> {

    public static final Comparator<String> ORDER = Comparator.comparing(Semver::parse);

    public static Semver parse(String s) {
        if (s == null || s.isBlank()) {
            return new Semver(0, 0, 0);
        }
        String[] parts = s.split("-")[0].split("\\.");
        int major = parts.length > 0 ? num(parts[0]) : 0;
        int minor = parts.length > 1 ? num(parts[1]) : 0;
        int patch = parts.length > 2 ? num(parts[2]) : 0;
        return new Semver(major, minor, patch);
    }

    private static int num(String p) {
        try {
            return Integer.parseInt(p.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean satisfies(String range) {
        if (range == null || range.isBlank() || "*".equals(range) || "latest".equals(range)) {
            return true;
        }
        if (range.startsWith("^")) {
            Semver min = parse(range.substring(1));
            return compareTo(min) >= 0 && major == min.major;
        }
        if (range.startsWith("~")) {
            Semver min = parse(range.substring(1));
            return compareTo(min) >= 0 && major == min.major && minor == min.minor;
        }
        String[] parts = range.split("\\.");
        Semver exact = parse(range);
        if (parts.length == 1) {
            return major == exact.major;
        }
        if (parts.length == 2 || range.endsWith(".x")) {
            return major == exact.major && minor == exact.minor;
        }
        return equals(exact);
    }

    /** The highest of the candidates inside a range. */
    public static Optional<String> highest(List<String> candidates, String range) {
        return candidates.stream().filter(v -> parse(v).satisfies(range)).max(ORDER);
    }

    public Semver bumpPatch() {
        return new Semver(major, minor, patch + 1);
    }

    public Semver bumpMinor() {
        return new Semver(major, minor + 1, 0);
    }

    @Override
    public int compareTo(Semver o) {
        if (major != o.major) {
            return Integer.compare(major, o.major);
        }
        if (minor != o.minor) {
            return Integer.compare(minor, o.minor);
        }
        return Integer.compare(patch, o.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
