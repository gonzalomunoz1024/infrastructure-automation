package com.iaf.console.drift.drift.adapters.identity;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.drift.domain.AccountInfo;
import com.iaf.console.drift.drift.ports.outbound.AccountDirectoryPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * The identity service's accounts ({@code GET /v1/accounts?status=active}, every page), read as this
 * service with a BFF service token and kept for {@code keep}. When the identity service does not
 * answer, the last list read stands (empty before the first), so a discovery run still finishes
 * with whatever guesses the accounts it knows allow.
 */
public final class IdentityAccountDirectory implements AccountDirectoryPort {

    private static final Logger log = LoggerFactory.getLogger(IdentityAccountDirectory.class);
    private static final int PAGE = 200;
    /** A runaway cursor stops here: far more accounts than the console's targets. */
    private static final int MOST_PAGES = 500;

    private record Kept(List<AccountInfo> accounts, Instant until) {
    }

    private final WebClient web;
    private final String baseUrl;
    private final Duration keep;
    private final Clock clock;
    private final AtomicReference<Kept> kept = new AtomicReference<>();

    /**
     * {@code credentials} name this service to the identity service: a BFF service token (hub decision 23), or the demos' dev header.
     * An answer must start within {@code timeout}, and its body may not stall for longer between reads.
     */
    public IdentityAccountDirectory(WebClient.Builder builder, String baseUrl, ExchangeFilterFunction credentials, Duration timeout, Duration keep,
            Clock clock) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.keep = keep;
        this.clock = clock;
        this.web = builder.clone().clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(timeout)))
                .defaultHeader(HttpHeaders.USER_AGENT, "console-drift-service")
                .filter(credentials)
                .filter((request, next) -> next.exchange(request).timeout(timeout))
                .build();
    }

    @Override
    public Mono<List<AccountInfo>> listAccounts() {
        Kept k = kept.get();
        if (k != null && k.until().isAfter(clock.instant())) {
            return Mono.just(k.accounts());
        }
        return page(null, 0).collectList()
                .map(List::copyOf)
                .doOnNext(accounts -> kept.set(new Kept(accounts, clock.instant().plus(keep))))
                .onErrorResume(e -> {
                    log.warn("the identity service did not answer the accounts: {}", e.toString());
                    Kept last = kept.get();
                    return Mono.just(last == null ? List.of() : last.accounts());
                });
    }

    private Flux<AccountInfo> page(String after, int pages) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl + "/v1/accounts").queryParam("status", "active").queryParam("limit", PAGE);
        if (after != null) {
            uri.queryParam("after", "{after}");
        }
        URI target = after == null ? uri.build().toUri() : uri.encode().buildAndExpand(after).toUri();
        return web.get().uri(target).retrieve().bodyToMono(JsonNode.class).flatMapMany(body -> {
            List<AccountInfo> accounts = new ArrayList<>();
            for (JsonNode item : body.path("items")) {
                if (item.hasNonNull("accountId")) {
                    accounts.add(new AccountInfo(item.get("accountId").asText(), item.path("name").asText(null), item.path("owningTeam").asText(null)));
                }
            }
            Flux<AccountInfo> these = Flux.fromIterable(accounts);
            String next = body.hasNonNull("next") ? body.get("next").asText() : null;
            if (next == null || next.isBlank() || pages + 1 >= MOST_PAGES) {
                return these;
            }
            return these.concatWith(Flux.defer(() -> page(next, pages + 1)));
        });
    }
}
