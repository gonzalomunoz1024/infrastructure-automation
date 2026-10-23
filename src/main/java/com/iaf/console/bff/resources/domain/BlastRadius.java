package com.iaf.console.bff.resources.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a change to a resource would reach: the live resources that depend on it, directly or
 * through others, up to {@code depth} steps, counted per account. Only the session's account's
 * dependents are named ({@code dependents}, {@code edges}); another account's are counted in
 * {@code byAccount} and never named, so the radius shows no more than the person may see.
 * {@code truncated} says the walk stopped at its cap and the counts are a lower bound.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlastRadius(ResourceRef resource, long total, long direct, List<AccountCount> byAccount, List<Dependent> dependents, List<Edge> edges,
        int depth, boolean truncated) {

    /** A named dependent: a {@link ResourceRef} and how many steps away it is (1 depends on the resource itself). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Dependent(String wfrn, String type, String name, String state, String id, int depth) {

        public ResourceRef ref() {
            return new ResourceRef(wfrn, type, name, state, id);
        }
    }

    /** How many dependents one account holds; {@code current} for the session's account. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountCount(String accountId, long count, boolean current) {
    }

    /** One dependency between two named resources: {@code to} depends on {@code from}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Edge(String from, String to, String via) {
    }

    public BlastRadius {
        byAccount = byAccount == null ? List.of() : List.copyOf(byAccount);
        dependents = dependents == null ? List.of() : List.copyOf(dependents);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** A resource nothing depends on (or not registered yet, so nothing can). */
    public static BlastRadius none(ResourceRef resource, int depth) {
        return new BlastRadius(resource, 0, 0, List.of(), List.of(), List.of(), depth, false);
    }

    /** Whether anything live depends on the resource directly. */
    public boolean hasDependents() {
        return direct > 0;
    }

    /** The named dependents one step away. */
    public List<Dependent> directDependents() {
        return dependents.stream().filter(d -> d.depth() == 1).toList();
    }
}
