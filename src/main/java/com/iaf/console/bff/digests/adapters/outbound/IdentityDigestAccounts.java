package com.iaf.console.bff.digests.adapters.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.digests.domain.DigestAccount;
import com.iaf.console.bff.digests.ports.outbound.DigestAccounts;
import com.iaf.console.bff.identity.application.IdentityClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Every active account from the identity service ({@code GET /v1/accounts?status=active}, each page), as the console-internal principal. */
@Component
public class IdentityDigestAccounts implements DigestAccounts {

    static final int PAGE = 200;
    static final int MAX_PAGES = 50;
    static final String ACTIVE = "active";

    private final IdentityClient identity;

    public IdentityDigestAccounts(IdentityClient identity) {
        this.identity = identity;
    }

    private record Listed(List<DigestAccount> items, String next) {
    }

    @Override
    public Flux<DigestAccount> listAccounts() {
        return page(null).expand(p -> p.next() == null ? Mono.empty() : page(p.next())).take(MAX_PAGES).concatMapIterable(Listed::items);
    }

    private Mono<Listed> page(String after) {
        return identity.asService(HttpMethod.GET)
                .uri(b -> b.path("/v1/accounts").queryParam("status", ACTIVE).queryParam("limit", PAGE)
                        .queryParamIfPresent("after", Optional.ofNullable(after)).build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(identity.timeout())
                .map(body -> {
                    List<DigestAccount> items = new ArrayList<>();
                    for (JsonNode a : body.path("items")) {
                        String id = a.path("accountId").asText(null);
                        String status = a.path("status").asText(ACTIVE);
                        if (id != null && ACTIVE.equals(status)) {
                            items.add(new DigestAccount(id, a.path("name").asText(null), a.path("defaultRegion").asText(null)));
                        }
                    }
                    String next = body.hasNonNull("next") && !body.get("next").asText().isBlank() ? body.get("next").asText() : null;
                    return new Listed(items, next);
                });
    }
}
