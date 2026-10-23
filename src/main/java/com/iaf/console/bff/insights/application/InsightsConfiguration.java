package com.iaf.console.bff.insights.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.insights.domain.Counted;
import com.iaf.console.bff.insights.ports.outbound.AccountProfilesPort;
import com.iaf.console.bff.insights.ports.outbound.DriftStatsPort;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.search.application.Search;
import com.iaf.console.bff.tags.application.TagPolicies;

/** Wires the executive home over the search index's counts, the platform's jobs, the drift service and the identity service's account records. */
@Configuration(proxyBeanMethods = false)
public class InsightsConfiguration {

    /** The search slice's counts across accounts, read as the executive home's own record. */
    @Bean
    public InventoryCounts inventoryCounts(Search search) {
        return (session, accountIds, filters, groupBy) -> search.countsAcross(session, accountIds, filters, groupBy).map(InsightsConfiguration::counted);
    }

    static Counted counted(JsonNode answer) {
        List<Counted.Group> groups = new ArrayList<>();
        for (JsonNode group : answer.path("groups")) {
            String service = group.path("service").asText(null);
            groups.add(new Counted.Group(group.path("key").asText(), group.path("total").asLong(0), service == null || service.isBlank() ? null : service));
        }
        return new Counted(answer.path("total").asLong(0), groups);
    }

    @Bean
    public ExecutiveHome executiveHome(Access access, PlatformClients clients, PlatformProperties platform, InventoryCounts counts, DriftStatsPort drift,
            AccountProfilesPort profiles, TagPolicies tags, InsightsProperties properties, Clock clock) {
        return new ExecutiveHome(access, clients, platform, counts, drift, profiles, tags, properties, clock);
    }
}
