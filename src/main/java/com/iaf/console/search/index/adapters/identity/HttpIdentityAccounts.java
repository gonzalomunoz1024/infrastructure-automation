package com.iaf.console.search.index.adapters.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.search.index.ports.outbound.AccountsPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Every console account, from the identity service's {@code GET /v1/accounts} as the internal principal, page after page. */
public final class HttpIdentityAccounts implements AccountsPort {

    private final WebClient web;
    private final Consumer<HttpHeaders> credentials;

    public HttpIdentityAccounts(WebClient web, Consumer<HttpHeaders> credentials) {
        this.web = web;
        this.credentials = credentials;
    }

    private record Listed(List<String> ids, String next) {
    }

    @Override
    public Flux<String> listAccountIds() {
        return page(null).expand(p -> p.next() == null ? Mono.empty() : page(p.next())).concatMapIterable(Listed::ids);
    }

    private Mono<Listed> page(String after) {
        return web.get().uri(b -> {
            b.path("/v1/accounts").queryParam("limit", 200);
            if (after != null) {
                b.queryParam("after", after);
            }
            return b.build();
        }).headers(credentials::accept).retrieve().bodyToMono(JsonNode.class).map(body -> {
            List<String> ids = new ArrayList<>();
            body.path("items").forEach(a -> {
                String id = a.path("accountId").asText(null);
                if (id != null && !"closed".equals(a.path("status").asText())) {
                    ids.add(id);
                }
            });
            JsonNode next = body.get("next");
            return new Listed(ids, next == null || next.isNull() ? null : next.asText());
        });
    }
}
