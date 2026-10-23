package com.iaf.console.bff.resources.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.iaf.console.platform.model.AffectedResource;
import com.iaf.console.platform.model.Cost;
import com.iaf.console.platform.model.Preview;
import com.iaf.console.platform.model.PreviewStep;

/**
 * The platform's preview of a run, with wfrns on the affected resources where the platform or the
 * subject names them; before a delete or a destructive verb, also the resource's blast radius,
 * whose named dependents join the affected resources.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreviewView(String workflow, String version, List<PreviewStep> steps, List<ResourceRef> affectedResources, Cost cost,
        ConsolePolicy policy, BlastRadius blastRadius) {

    public static final String DENY = "deny";

    public PreviewView {
        steps = steps == null ? List.of() : List.copyOf(steps);
        affectedResources = affectedResources == null ? List.of() : List.copyOf(affectedResources);
        policy = policy == null ? new ConsolePolicy(ConsolePolicy.ALLOW, List.of(), null) : policy;
    }

    public static PreviewView of(Preview preview, Map<String, String> wfrnsByRegistryId) {
        List<ResourceRef> affected = new ArrayList<>();
        if (preview.affectedResources() != null) {
            for (AffectedResource a : preview.affectedResources()) {
                String wfrn = a.wfrn() != null ? a.wfrn() : wfrnsByRegistryId.get(a.id());
                affected.add(new ResourceRef(wfrn, a.type(), a.name(), null, a.id()));
            }
        }
        return new PreviewView(preview.workflow(), preview.version(), preview.steps(), affected, preview.cost(), ConsolePolicy.of(preview.policy()), null);
    }

    /** The preview of a workflow that publishes none: no steps, nothing decided yet but what the console adds. */
    public static PreviewView unpublished(String workflow, String version, ResourceRef subject) {
        return new PreviewView(workflow, version, List.of(), subject == null ? List.of() : List.of(subject), null, null, null);
    }

    /** The same preview with a rule of the console's: a deny makes the decision a deny. */
    public PreviewView withRule(String id, String result, String message) {
        return withRule(ConsolePolicy.Rule.of(id, result, message));
    }

    /** The same preview with a rule of the console's, a budget rule with its {@code budgetId}: a deny makes the decision a deny. */
    public PreviewView withRule(ConsolePolicy.Rule rule) {
        return new PreviewView(workflow, version, steps, affectedResources, cost, policy.with(rule), blastRadius);
    }

    /** The same preview with a cost: the cost service's estimate when the provider's plan priced nothing. */
    public PreviewView withCost(Cost estimated) {
        return new PreviewView(workflow, version, steps, affectedResources, estimated, policy, blastRadius);
    }

    /** With the blast radius; {@code refuseDependents} (a delete) adds the {@code dependents.exist} deny when anything depends on the resource. */
    public PreviewView withBlastRadius(BlastRadius radius, boolean refuseDependents) {
        List<ResourceRef> affected = new ArrayList<>(affectedResources);
        for (BlastRadius.Dependent d : radius.dependents()) {
            if (affected.stream().noneMatch(a -> (a.wfrn() != null && a.wfrn().equals(d.wfrn())) || (a.id() != null && Objects.equals(a.id(), d.id())))) {
                affected.add(d.ref());
            }
        }
        PreviewView view = new PreviewView(workflow, version, steps, affected, cost, policy, radius);
        return refuseDependents && radius.hasDependents()
                ? view.withRule(DependentsExistException.CODE, DENY, DependentsExistException.sentence(radius))
                : view;
    }
}
