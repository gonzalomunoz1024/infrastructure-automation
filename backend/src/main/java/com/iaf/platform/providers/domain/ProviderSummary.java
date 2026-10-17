package com.iaf.platform.providers.domain;

import java.util.List;

import com.iaf.platform.providers.domain.ProviderDefinition.Alerts;
import com.iaf.platform.providers.domain.ProviderDefinition.Slo;

/** One row of the providers admin view: the file's identity plus its judged health. */
public record ProviderSummary(
        String name,
        String title,
        ProviderType type,
        String owner,
        String version,
        String description,
        List<String> environments,
        int actionCount,
        Health health,
        Slo slo,
        Alerts alerts) {

    public static ProviderSummary of(ProviderDefinition def, Health health) {
        return new ProviderSummary(def.name(), def.title(), def.type(), def.owner(), def.metadata().version(), def.metadata().description(),
                def.environments(), def.actions().size(), health, def.slo(), def.spec().alerts());
    }
}
