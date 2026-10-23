package com.iaf.console.bff.resources.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.iaf.console.platform.model.AffectedResource;
import com.iaf.console.platform.model.Cost;
import com.iaf.console.platform.model.PolicyDecision;
import com.iaf.console.platform.model.Preview;
import com.iaf.console.platform.model.PreviewStep;

/** The platform's preview of a run, with wfrns on the affected resources where the platform or the subject names them. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreviewView(String workflow, String version, List<PreviewStep> steps, List<ResourceRef> affectedResources, Cost cost,
        PolicyDecision policy) {

    public static PreviewView of(Preview preview, Map<String, String> wfrnsByRegistryId) {
        List<ResourceRef> affected = new ArrayList<>();
        if (preview.affectedResources() != null) {
            for (AffectedResource a : preview.affectedResources()) {
                String wfrn = a.wfrn() != null ? a.wfrn() : wfrnsByRegistryId.get(a.id());
                affected.add(new ResourceRef(wfrn, a.type(), a.name(), null, a.id()));
            }
        }
        PolicyDecision policy = preview.policy() == null ? new PolicyDecision("allow", List.of(), null) : preview.policy();
        return new PreviewView(preview.workflow(), preview.version(), preview.steps() == null ? List.of() : preview.steps(), affected,
                preview.cost(), policy);
    }
}
