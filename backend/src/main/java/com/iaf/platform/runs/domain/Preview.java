package com.iaf.platform.runs.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;

/** The blast radius of a run before it happens: what each step would do, which resources it touches, what it costs, what policy says. */
public record Preview(
        String workflow,
        String version,
        List<PreviewStep> steps,
        List<AffectedResource> affectedResources,
        Cost cost,
        PolicyDecision policy) {

    /** {@code skipped}: the dry run on this input did not take the step, so it does nothing (effect none). */
    public record PreviewStep(String id, String name, Effect effect, String detail, @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean skipped) {

        public PreviewStep(String id, String name, Effect effect, String detail) {
            this(id, name, effect, detail, false);
        }
    }

    public record AffectedResource(String id, String type, String name, String via) {
    }

    public record Cost(double monthly, String currency, String detail) {
    }

    public boolean destructive() {
        return steps.stream().anyMatch(s -> s.effect() != null && s.effect().destructive());
    }
}
