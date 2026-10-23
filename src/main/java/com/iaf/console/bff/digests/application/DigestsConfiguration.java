package com.iaf.console.bff.digests.application;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.cost.ports.outbound.CostPort;
import com.iaf.console.bff.digests.domain.Digest;
import com.iaf.console.bff.digests.ports.outbound.AccessLedger;
import com.iaf.console.bff.digests.ports.outbound.DigestAccounts;
import com.iaf.console.bff.digests.ports.outbound.DigestStore;
import com.iaf.console.bff.digests.ports.outbound.DigestWriter;
import com.iaf.console.bff.insights.application.ExecutiveHome;
import com.iaf.console.bff.insights.ports.outbound.DriftStatsPort;
import com.iaf.console.bff.notifications.application.Notifications;
import com.iaf.console.bff.platform.application.PlatformProperties;

/**
 * Wires the weekly digests: the sources, the writer (the first one that is not the rules template,
 * else the template), the schedule and the record of identity events, which only the
 * {@code kafka} profile feeds.
 */
@Configuration(proxyBeanMethods = false)
public class DigestsConfiguration {

    static final String KAFKA = "kafka";

    @Bean
    public DigestSources digestSources(ExecutiveHome home, DriftStatsPort drift, CostPort cost, AccessLedger ledger, Environment environment,
            ObjectMapper json) {
        return new DigestSources(home, drift, cost, ledger, environment.acceptsProfiles(Profiles.of(KAFKA)), json);
    }

    @Bean
    public Digests digests(DigestStore store, DigestAccounts accounts, DigestSources sources, ObjectProvider<DigestWriter> writers,
            Notifications notifications, Access access, PlatformProperties platform, DigestsProperties properties, Clock clock) {
        DigestWriter writer = writers.orderedStream().filter(w -> !Digest.RULES.equals(w.source())).findFirst()
                .orElseGet(() -> writers.orderedStream().findFirst().orElseThrow(() -> new IllegalStateException("no digest writer is wired")));
        return new Digests(store, accounts, sources, writer, notifications, access, platform.partition(), properties.lease(), clock);
    }

    @Bean
    public DigestScheduler digestScheduler(Digests digests, DigestsProperties properties, Clock clock) {
        return new DigestScheduler(digests, properties, clock);
    }

    @Bean
    public AccessRecord accessRecord(AccessLedger ledger, Environment environment, Clock clock) {
        return new AccessRecord(ledger, environment.acceptsProfiles(Profiles.of(KAFKA)), clock);
    }
}
