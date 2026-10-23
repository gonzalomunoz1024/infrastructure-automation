package com.iaf.console.bff.insights.adapters.outbound;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.insights.domain.DriftNumbers;
import com.iaf.console.bff.insights.ports.outbound.DriftStatsPort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** Without {@code console.drift.url} the console has no drift service to ask, so the executive home counts no findings. */
@Component
@ConditionalOnExpression("'${console.drift.url:}' == ''")
public class NoDriftService implements DriftStatsPort {

    private static final Logger log = LoggerFactory.getLogger(NoDriftService.class);

    public NoDriftService() {
        log.info("console.drift.url is not set: the executive home shows no drift findings");
    }

    @Override
    public Mono<DriftNumbers> findStats(AccountScope scope, Instant since, Instant until) {
        return Mono.empty();
    }
}
