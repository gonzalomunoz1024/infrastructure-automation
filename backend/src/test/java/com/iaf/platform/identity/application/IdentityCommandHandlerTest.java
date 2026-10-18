package com.iaf.platform.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.server.WebFilter;

import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Hashes;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.identity.adapters.inbound.RestControllerIdentityAdapter;
import com.iaf.platform.identity.adapters.outbound.InMemoryServiceAccounts;
import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.inbound.IdentityCommands.CreateServiceAccount;
import com.iaf.platform.identity.ports.inbound.IdentityCommands.ServiceAccountCreated;

import reactor.core.publisher.Mono;

/** Creating and listing service accounts: admins only, with checked scopes, a preset or the defaults, the creator's teams by default, and the token returned once. */
class IdentityCommandHandlerTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    static final Identity ADMIN = new Identity("gonzalo", "Gonzalo", null, List.of(), List.of("admin"), true, Identity.Kind.USER);
    static final Identity ANA = new Identity("ana", "Ana", null, List.of("team-data"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity MATEO = new Identity("mateo", "Mateo", null, List.of("team-compute", "team-platform"), List.of("admin"), true, Identity.Kind.USER);

    final InMemoryServiceAccounts store = new InMemoryServiceAccounts();
    final EventBus bus = new EventBus(CLOCK);
    final List<DomainEvent> events = new CopyOnWriteArrayList<>();
    final IdentityCommandHandler handler = new IdentityCommandHandler(store, bus, CLOCK);

    IdentityCommandHandlerTest() {
        bus.register(events::add);
    }

    static <T> T as(Identity who, Mono<T> mono) {
        return mono.contextWrite(ctx -> CurrentIdentity.with(ctx, who)).block();
    }

    @Test
    void theAssistantPresetGetsReadValidateAndDryRunOnly() {
        ServiceAccountCreated created = as(ADMIN, handler.createServiceAccount(new CreateServiceAccount("devin", List.of(), List.of(), null, "assistant")));
        assertThat(created.token()).startsWith("iaf_");
        assertThat(created.scopes()).containsExactly("registry:read", "workflows:validate", "workflows:dry-run");
        ServiceAccount saved = store.byTokenHash(Hashes.sha256(created.token())).orElseThrow();
        assertThat(saved.scopes()).containsExactly("registry:read", "workflows:validate", "workflows:dry-run");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).data().path("scopes")).hasSize(3);
        assertThat(events.get(0).data().toString()).doesNotContain(created.token());
    }

    @Test
    void scopesAreCheckedAndDefaultWhenNoneAreGiven() {
        assertThat(as(ADMIN, handler.createServiceAccount(new CreateServiceAccount("bot", List.of("ops"), List.of("team-compute"), null, null))).scopes())
                .containsExactly("registry:read", "jobs:start", "jobs:read");
        assertThat(as(ADMIN, handler.createServiceAccount(new CreateServiceAccount("ci", null, null, List.of("workflows:write", "providers:write"), null))).scopes())
                .containsExactly("workflows:write", "providers:write");
        assertThatThrownBy(() -> as(ADMIN, handler.createServiceAccount(new CreateServiceAccount("x", null, null, List.of("everything"), null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("everything");
        assertThatThrownBy(() -> as(ANA, handler.createServiceAccount(new CreateServiceAccount("x", null, null, List.of("admin"), null))))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anAccountGivenNoTeamsActsForItsCreatorsTeamsAndTheResponseSaysSo() {
        ServiceAccountCreated defaulted = as(MATEO, handler.createServiceAccount(new CreateServiceAccount("nightly", List.of(), null, null, "invoker")));
        assertThat(defaulted.teams()).containsExactly("team-compute", "team-platform");
        assertThat(defaulted.note()).isEqualTo("No teams were given: the account acts for Mateo's teams (team-compute, team-platform)");
        assertThat(store.byTokenHash(Hashes.sha256(defaulted.token())).orElseThrow().identity().teams()).containsExactly("team-compute", "team-platform");
        assertThat(events.get(0).data().path("teams")).hasSize(2);
        assertThat(events.get(0).data().path("teamsFrom").asText()).isEqualTo("creator");

        ServiceAccountCreated given = as(MATEO, handler.createServiceAccount(new CreateServiceAccount("data-bot", List.of(), List.of(" team-data ", ""), null, null)));
        assertThat(given.teams()).containsExactly("team-data");
        assertThat(given.note()).isNull();
        assertThat(events.get(1).data().has("teamsFrom")).isFalse();

        // a creator without a team: the account has none either, and the response says what that means
        ServiceAccountCreated none = as(ADMIN, handler.createServiceAccount(new CreateServiceAccount("orphan", List.of(), List.of(), null, null)));
        assertThat(none.teams()).isEmpty();
        assertThat(none.note()).contains("Gonzalo belongs to none").contains("refuses every job it starts");
    }

    @Test
    void onlyAdminsListServiceAccounts() {
        IdentityQueryHandler queries = new IdentityQueryHandler(store);
        as(ADMIN, handler.createServiceAccount(new CreateServiceAccount("bot", List.of(), List.of("team-compute"), null, null)));
        assertThatThrownBy(() -> as(ANA, queries.serviceAccounts()))
                .isInstanceOf(ForbiddenException.class).hasMessage("Only admins list service accounts");
        assertThat(as(ADMIN, queries.serviceAccounts())).extracting(ServiceAccount::name).containsExactly("bot");
        assertThat(as(ADMIN, queries.serviceAccounts()).get(0).tokenHash()).isNull();
    }

    @Test
    void theApiTakesScopesAndListsThemWithoutTheTokenHash() {
        RestControllerIdentityAdapter adapter = new RestControllerIdentityAdapter(handler, new IdentityQueryHandler(store));
        WebFilter asAdmin = (exchange, chain) -> chain.filter(exchange).contextWrite(ctx -> CurrentIdentity.with(ctx, ADMIN));
        var client = org.springframework.test.web.reactive.server.WebTestClient.bindToController(adapter)
                .controllerAdvice(new com.iaf.platform.config.GlobalExceptionHandler())
                .webFilter(asAdmin)
                .build();
        client.post().uri("/api/v1/service-accounts").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "devin", "scopes", List.of("registry:read", "workflows:validate")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.token").isNotEmpty()
                .jsonPath("$.scopes.length()").isEqualTo(2)
                .jsonPath("$.teams.length()").isEqualTo(0)
                .jsonPath("$.note").value(note -> assertThat(String.valueOf(note)).contains("acts for no team"));
        client.post().uri("/api/v1/service-accounts").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "x", "preset", "nope"))
                .exchange()
                .expectStatus().isBadRequest();
        client.get().uri("/api/v1/service-accounts").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("devin")
                .jsonPath("$[0].scopes[1]").isEqualTo("workflows:validate")
                .jsonPath("$[0].tokenHash").doesNotExist();
        WebFilter asAna = (exchange, chain) -> chain.filter(exchange).contextWrite(ctx -> CurrentIdentity.with(ctx, ANA));
        org.springframework.test.web.reactive.server.WebTestClient.bindToController(adapter)
                .controllerAdvice(new com.iaf.platform.config.GlobalExceptionHandler())
                .webFilter(asAna)
                .build()
                .get().uri("/api/v1/service-accounts").exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("Only admins list service accounts");
    }
}
