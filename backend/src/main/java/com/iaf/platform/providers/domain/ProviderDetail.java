package com.iaf.platform.providers.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.iaf.platform.providers.domain.ProviderDefinition.Alerts;
import com.iaf.platform.providers.domain.ProviderDefinition.Auth;
import com.iaf.platform.providers.domain.ProviderDefinition.Slo;
import com.iaf.platform.workflows.domain.ChangeRequest;

/**
 * The provider page: the summary, where it lives and how it signs in (the credential by its
 * {@code vault:} name only), the file, the actions and the last two weeks of telemetry.
 */
public record ProviderDetail(
        String name,
        String title,
        ProviderType type,
        String owner,
        String version,
        String description,
        List<String> environments,
        Map<String, String> endpoints,
        Auth auth,
        int actionCount,
        Health health,
        Slo slo,
        Alerts alerts,
        String yaml,
        List<ActionInfo> actions,
        List<Daily> daily,
        List<PerAction> perAction,
        List<ChangeRequest> changeRequests) {

    /**
     * One 24-hour window of calls, oldest first; {@code date} is the instant the window starts. A
     * window without calls has no rates (null), not a 0% success rate.
     */
    public record Daily(String date, int calls, Double successRate, Long p50Ms, Long p95Ms) {
    }

    public record PerAction(String action, int calls, double successRate, long p50Ms, long p95Ms, List<ErrorCount> topErrors) {
    }

    /** Serialises as {@code { class, count }}, the shape the API promises. */
    public record ErrorCount(@JsonProperty("class") String errorClass, int count) {
    }

    public static ProviderDetail of(ProviderSummary s, ProviderDefinition def, String yaml, List<ActionInfo> actions, List<Daily> daily,
            List<PerAction> perAction, List<ChangeRequest> changeRequests) {
        Map<String, String> endpoints = def.spec() == null || def.spec().endpoints() == null ? Map.of() : def.spec().endpoints();
        Auth auth = def.spec() == null ? null : def.spec().auth();
        return new ProviderDetail(s.name(), s.title(), s.type(), s.owner(), s.version(), s.description(), s.environments(), endpoints, auth,
                s.actionCount(), s.health(), s.slo(), s.alerts(), yaml, actions, daily, perAction, changeRequests);
    }
}
