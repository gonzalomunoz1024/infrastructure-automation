package com.iaf.console.bff.session.adapters.outbound;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.identity.application.IdentityClient;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.IdpUser;
import com.iaf.console.bff.session.domain.NoConsoleAccountException;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.ResolvedPrincipal;
import com.iaf.console.bff.session.domain.RoleConventions;
import com.iaf.console.bff.session.ports.outbound.PrincipalResolverPort;

import reactor.core.publisher.Mono;

/**
 * {@code POST /v1/principals:resolve} at the identity service, as the console-internal principal:
 * at sign-in and whenever a session's memberships are due again. Each account's
 * {@code elevations[].until} bounds how long its roles may be used.
 */
@Component
public class HttpPrincipalResolver implements PrincipalResolverPort {

    private final IdentityClient identity;

    public HttpPrincipalResolver(IdentityClient identity) {
        this.identity = identity;
    }

    @Override
    public Mono<ResolvedPrincipal> resolve(IdpUser user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", user.issuer());
        body.put("subject", user.subject());
        if (user.email() != null) {
            body.put("email", user.email());
        }
        if (user.name() != null) {
            body.put("name", user.name());
        }
        body.put("groups", user.groups());
        return identity.asService(HttpMethod.POST).uri("/v1/principals:resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status == HttpStatus.NOT_FOUND, response -> Mono.error(new NoConsoleAccountException(user.subject())))
                .bodyToMono(JsonNode.class)
                .timeout(identity.timeout())
                .map(HttpPrincipalResolver::parse)
                .flatMap(resolved -> resolved.hasAccounts() ? Mono.just(resolved) : Mono.error(new NoConsoleAccountException(user.subject())));
    }

    static ResolvedPrincipal parse(JsonNode json) {
        JsonNode u = json.path("user");
        Principal principal = new Principal(u.path("userId").asText(), u.path("email").asText(""), u.path("name").asText(null), strings(u.path("groups")));
        List<AccountMembership> accounts = new ArrayList<>();
        for (JsonNode a : json.path("accounts")) {
            List<String> groups = strings(a.path("groups"));
            List<String> roles = a.has("roles") ? strings(a.path("roles")) : RoleConventions.fromGroups(groups);
            accounts.add(new AccountMembership(a.path("accountId").asText(), a.path("name").asText(null), a.path("status").asText(null),
                    a.path("defaultRegion").asText(null), groups, roles, earliestUntil(a.path("elevations"))));
        }
        return new ResolvedPrincipal(principal, accounts);
    }

    /**
     * The earliest {@code until} of the roles held for a while in an account. One that cannot be
     * read counts as ended now, so the session reads its memberships again at once rather than
     * keeping a role whose end it does not know.
     */
    static Instant earliestUntil(JsonNode elevations) {
        Instant earliest = null;
        if (elevations != null && elevations.isArray()) {
            for (JsonNode e : elevations) {
                Instant until;
                try {
                    until = Instant.parse(e.path("until").asText(""));
                } catch (DateTimeParseException unreadable) {
                    until = Instant.EPOCH;
                }
                if (earliest == null || until.isBefore(earliest)) {
                    earliest = until;
                }
            }
        }
        return earliest;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode n : array) {
                out.add(n.asText());
            }
        }
        return out;
    }
}
