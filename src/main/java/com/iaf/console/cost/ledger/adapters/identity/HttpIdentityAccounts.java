package com.iaf.console.cost.ledger.adapters.identity;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.cost.ledger.domain.AccountInfo;
import com.iaf.console.cost.ledger.ports.outbound.AccountDirectoryPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Every console account with its name, owning team and cost centre, from the identity service's
 * {@code GET /v1/accounts} as the internal principal (the WebClient carries this service's
 * credentials), page after page. Closed accounts are left out.
 */
public final class HttpIdentityAccounts implements AccountDirectoryPort {

    private final WebClient web;

    public HttpIdentityAccounts(WebClient web) {
        this.web = web;
    }

    private record Listed(List<AccountInfo> accounts, String next) {
    }

    @Override
    public Flux<AccountInfo> listAccounts() {
        return page(null).expand(p -> p.next() == null ? Mono.empty() : page(p.next())).concatMapIterable(Listed::accounts);
    }

    private Mono<Listed> page(String after) {
        return web.get().uri(b -> {
            b.path("/v1/accounts").queryParam("limit", 200);
            if (after != null) {
                b.queryParam("after", after);
            }
            return b.build();
        }).retrieve().bodyToMono(JsonNode.class).map(body -> {
            List<AccountInfo> out = new ArrayList<>();
            body.path("items").forEach(a -> {
                String id = a.path("accountId").asText(null);
                if (id != null && !"closed".equals(a.path("status").asText())) {
                    out.add(new AccountInfo(id, a.path("name").asText(id), a.path("owningTeam").asText(null), a.path("costCentre").asText(null)));
                }
            });
            JsonNode next = body.get("next");
            return new Listed(out, next == null || next.isNull() ? null : next.asText());
        });
    }
}
