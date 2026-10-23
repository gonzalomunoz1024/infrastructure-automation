package com.iaf.console.bff.search.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.application.AccessProperties;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.access.domain.Decisions;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.access.ports.outbound.AuthorizationPort;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * What the search application's unit tests share: two accounts, a catalogue of three types, and
 * people whose policies differ (gonzalo may do anything, mia list anything, lena list VMs in the
 * compute account only, noah nothing).
 */
final class SearchFixtures {

    static final String COMPUTE = "123456789012";
    static final String DATA = "210987654321";
    static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<ConsoleType> CATALOGUE = List.of(type("compute", "vm"), type("storage", "volume"), type("data", "database"));
    static final Map<String, Predicate<String>> POLICIES = Map.of(
            "user_gonzalo", action -> true,
            "user_mia", action -> action.endsWith(":list"),
            "user_lena", action -> action.equals("compute:list"),
            "user_noah", action -> false);

    private SearchFixtures() {
    }

    /** The search application over the catalogue, the policies and the given indexer. */
    static Search search(FakeSearchIndex index) {
        return new Search(catalogue(), access(), platform(), index);
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ConsoleType type(String service, String name) {
        return new ConsoleType(service + "/" + name, name, service, name + "s", name, "box", null, null, null, null, null, null, null, null, null);
    }

    /** The person in the account, a member of both accounts. */
    static Session session(String user, String accountId) {
        Principal principal = new Principal("user_" + user, user + "@example.com", user, List.of());
        AccountMembership compute = new AccountMembership(COMPUTE, "Compute", "active", "global", List.of(), List.of("operator"));
        AccountMembership data = new AccountMembership(DATA, "Data", "active", "global", List.of(), List.of("operator"));
        return new Session("s_" + user, principal, List.of(compute, data), accountId, "global", NOW, NOW, NOW, NOW.plus(Duration.ofHours(12)),
                "https://idp.test", user);
    }

    /** The person in the compute account only. */
    static Session alone(String user) {
        Principal principal = new Principal("user_" + user, user + "@example.com", user, List.of());
        AccountMembership compute = new AccountMembership(COMPUTE, "Compute", "active", "global", List.of(), List.of("operator"));
        return new Session("s_" + user, principal, List.of(compute), COMPUTE, "global", NOW, NOW, NOW, NOW.plus(Duration.ofHours(12)),
                "https://idp.test", user);
    }

    static Types catalogue() {
        return new Types(null, null, null, null, null) {
            @Override
            public Mono<List<ConsoleType>> list(Session session) {
                return Mono.just(CATALOGUE);
            }

            @Override
            public Mono<ConsoleType> byType(Session session, String type) {
                return Mono.justOrEmpty(CATALOGUE.stream().filter(t -> t.type().equals(type)).findFirst())
                        .switchIfEmpty(Mono.error(NotFoundException.of("type", type)));
            }
        };
    }

    /** Policies per person; in the data account lena may list nothing. */
    static Access access() {
        AuthorizationPort port = (scope, principal, groups, checks) -> Mono.just(new Decisions(checks.stream()
                .map(c -> allowed(principal.userId(), scope.accountId(), c.action()) ? Decision.allow()
                        : Decision.deny(Denial.byPolicy(c.action(), c.resource(), "No statement allows " + c.action(), null, null, null)))
                .toList(), 1));
        return new Access(port, new AccessProperties(Duration.ofSeconds(60), 1000));
    }

    private static boolean allowed(String userId, String accountId, String action) {
        if (userId.equals("user_lena") && accountId.equals(DATA)) {
            return false;
        }
        return POLICIES.getOrDefault(userId, a -> false).test(action);
    }

    static PlatformProperties platform() {
        return new PlatformProperties("http://localhost:18090", "", "wf", Duration.ofSeconds(30), Duration.ofMinutes(10), Map.of(),
                new PlatformProperties.Fake(false, 0, Duration.ofMillis(1), 1L));
    }
}
