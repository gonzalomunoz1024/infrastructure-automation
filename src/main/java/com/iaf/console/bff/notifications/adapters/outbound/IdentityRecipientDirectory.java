package com.iaf.console.bff.notifications.adapters.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.identity.application.IdentityClient;
import com.iaf.console.bff.notifications.domain.AccountPeople;
import com.iaf.console.bff.notifications.ports.outbound.RecipientDirectory;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * An account's users, groups and roles from the identity service ({@code GET
 * /v1/accounts/{accountId}/users}, {@code /groups} and {@code /roles}), every page of each, as the
 * console-internal principal, which may act on any account. An account the service does not know
 * is empty.
 */
@Component
public class IdentityRecipientDirectory implements RecipientDirectory {

    static final int PAGE = 200;
    /** The most pages of one list read: 10,000 users is more than an account holds. */
    static final int MAX_PAGES = 50;

    private final IdentityClient identity;

    public IdentityRecipientDirectory(IdentityClient identity) {
        this.identity = identity;
    }

    @Override
    public Mono<AccountPeople> findPeople(AccountScope scope) {
        String accountId = scope.accountId();
        return Mono.zip(all(accountId, "users"), all(accountId, "groups"), all(accountId, "roles"))
                .map(t -> AccountPeople.of(accountId, users(t.getT1()), groups(t.getT2()), roles(t.getT3())))
                .onErrorResume(WebClientResponseException.class, e -> e.getStatusCode().value() == HttpStatus.NOT_FOUND.value() ? Mono.empty()
                        : Mono.error(e));
    }

    private record Listed(List<JsonNode> items, String next) {
    }

    private Mono<List<JsonNode>> all(String accountId, String what) {
        return page(accountId, what, null)
                .expand(p -> p.next() == null ? Mono.empty() : page(accountId, what, p.next()))
                .take(MAX_PAGES)
                .concatMapIterable(Listed::items)
                .collectList();
    }

    private Mono<Listed> page(String accountId, String what, String after) {
        return identity.asService(HttpMethod.GET)
                .uri(b -> b.path("/v1/accounts/{accountId}/" + what).queryParam("limit", PAGE).queryParamIfPresent("after", Optional.ofNullable(after))
                        .build(accountId))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(identity.timeout())
                .map(body -> {
                    List<JsonNode> items = new ArrayList<>();
                    body.path("items").forEach(items::add);
                    String next = body.hasNonNull("next") && !body.get("next").asText().isBlank() ? body.get("next").asText() : null;
                    return new Listed(items, next);
                });
    }

    private static List<AccountPeople.User> users(List<JsonNode> items) {
        return items.stream().map(u -> new AccountPeople.User(text(u, "userId"), text(u, "email"), text(u, "name"), text(u, "status"),
                strings(u.path("groups")), strings(u.path("roles")))).toList();
    }

    private static List<AccountPeople.Group> groups(List<JsonNode> items) {
        return items.stream().map(g -> new AccountPeople.Group(text(g, "groupId"), text(g, "name"), strings(g.path("roles")), strings(g.path("members")),
                strings(g.path("idpGroups")))).toList();
    }

    private static List<AccountPeople.Role> roles(List<JsonNode> items) {
        return items.stream().map(r -> new AccountPeople.Role(text(r, "roleId"), text(r, "name"))).toList();
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(n -> {
                if (n.isTextual()) {
                    out.add(n.asText());
                }
            });
        }
        return out;
    }
}
