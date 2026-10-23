package com.iaf.console.bff.insights.adapters.outbound;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.insights.domain.DriftNumbers;
import com.iaf.console.bff.insights.ports.outbound.DriftStatsPort;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.domain.InternalToken;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The drift service's {@code GET /v1/findings/stats} (its contract, hub PR #17) at
 * {@code console.drift.url}, as the console-internal principal naming the account: found is
 * {@code opened}, fixed is {@code closed.total}, open is {@code open}, and the mean time to fix is
 * {@code meanMinutesToClose}, absent when nothing closed.
 */
@Component
@ConditionalOnExpression("'${console.drift.url:}' != ''")
public class HttpDriftStats implements DriftStatsPort {

    private final WebClient client;
    private final Trust trust;
    private final String serviceName;
    private final Duration timeout;

    public HttpDriftStats(WebClient.Builder builder, Trust trust, @Value("${console.drift.url}") String url,
            @Value("${console.drift.service-name:console-drift-service}") String serviceName, @Value("${console.drift.timeout:5s}") Duration timeout) {
        this.client = builder.clone().baseUrl(url).build();
        this.trust = trust;
        this.serviceName = serviceName;
        this.timeout = timeout;
    }

    @Override
    public Mono<DriftNumbers> findStats(AccountScope scope, Instant since, Instant until) {
        String token = trust.mint(InternalToken.service(serviceName, trust.issuer()));
        return client.get()
                .uri(b -> b.path("/v1/findings/stats").queryParam("accountId", scope.accountId()).queryParam("since", since.toString())
                        .queryParam("until", until.toString()).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(HttpDriftStats::numbers);
    }

    static DriftNumbers numbers(JsonNode stats) {
        JsonNode mean = stats.path("meanMinutesToClose");
        return new DriftNumbers(stats.path("opened").asLong(0), stats.path("closed").path("total").asLong(0), stats.path("open").asLong(0),
                mean.isNumber() ? Double.valueOf(mean.asDouble()) : null);
    }
}
