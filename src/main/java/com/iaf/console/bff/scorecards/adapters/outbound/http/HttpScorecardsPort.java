package com.iaf.console.bff.scorecards.adapters.outbound.http;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.bff.common.relay.RelayCall;
import com.iaf.console.bff.common.relay.RelayClient;
import com.iaf.console.bff.common.relay.Relayed;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.scorecards.application.DriftProperties;
import com.iaf.console.bff.scorecards.application.ScorecardsConfiguration;
import com.iaf.console.bff.scorecards.ports.outbound.ScorecardsPort;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** The drift service's scorecards over HTTP. With no URL configured it points at the in-process demo stand-in. */
@Component
public class HttpScorecardsPort implements ScorecardsPort {

    private final RelayClient client;

    @Autowired
    public HttpScorecardsPort(WebClient.Builder builder, Trust trust, DriftProperties properties, Environment environment) {
        this(new RelayClient(builder, () -> {
            if (properties.url() != null && !properties.url().isBlank()) {
                return properties.url().replaceAll("/+$", "");
            }
            String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "18081"));
            return "http://127.0.0.1:" + port + ScorecardsConfiguration.DEMO_PATH;
        }, trust, properties.serviceName(), properties.timeout()));
    }

    public HttpScorecardsPort(RelayClient client) {
        this.client = client;
    }

    @Override
    public Mono<Relayed> callAsPerson(AccountScope scope, StateCaller caller, RelayCall call) {
        return client.asPerson(scope, caller, call);
    }

    @Override
    public Mono<Relayed> callAsService(RelayCall call) {
        return client.asService(call);
    }
}
