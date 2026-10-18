package com.iaf.platform.providers.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderDefinition.Slo;

/**
 * One action as the admin view and the registry show it. {@code output} is the effective schema
 * (the declared one with the overrides merged in); {@code overridden} says an override exists;
 * {@code slo} is the action's own SLO, null when it takes the provider's.
 */
public record ActionInfo(
        String id,
        String title,
        String operation,
        Effect effect,
        String retry,
        String approval,
        String plan,
        JsonNode input,
        JsonNode output,
        JsonNode sample,
        boolean overridden,
        String description,
        String effectDetail,
        JsonNode recorded,
        Slo slo) {

    public static ActionInfo of(ProviderDefinition def, Action a) {
        JsonNode override = def.override(a.id());
        boolean overridden = override != null && !override.isNull();
        JsonNode output = a.output();
        if (overridden && override.has("response")) {
            output = Schemas.merge(output, override.get("response"));
        }
        String operation = a.operation();
        if (operation == null && a.method() != null) {
            operation = a.method().toUpperCase() + " " + (a.path() == null ? "" : a.path());
        }
        if (operation == null && a.playbook() != null) {
            operation = a.playbook();
        }
        JsonNode input = a.input() != null ? a.input() : a.vars();
        return new ActionInfo(a.id(), a.title() == null ? a.id() : a.title(), operation, a.effect(), a.retrySafe() ? "safe" : "unsafe",
                a.approvalRequired() ? "required" : "none", a.plan(), input, output, a.sample(), overridden, a.description(),
                a.effectDetail(), a.recorded(), a.slo());
    }
}
