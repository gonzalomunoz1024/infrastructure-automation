package com.iaf.platform.identity.application;

import java.time.Clock;
import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Hashes;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.identity.domain.Scopes;
import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.inbound.IdentityCommands;
import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Creates service accounts (admins only). An account acts for the teams it is given; given none,
 * it acts for its creator's teams, and the response says so, because the policy refuses every job
 * started on behalf of no team.
 */
@Primary
@Service
@RequiredArgsConstructor
public class IdentityCommandHandler implements IdentityCommands {

    private final ServiceAccountStore store;
    private final EventBus events;
    private final Clock clock;

    @Override
    public Mono<ServiceAccountCreated> createServiceAccount(CreateServiceAccount command) {
        return CurrentIdentity.current().map(who -> {
            if (!who.admin()) {
                throw new ForbiddenException("Only admins create service accounts");
            }
            if (command.name() == null || command.name().isBlank()) {
                throw new IllegalArgumentException("name is required");
            }
            List<String> scopes = Scopes.resolve(command.scopes(), command.preset());
            List<String> given = clean(command.teams());
            List<String> teams = given.isEmpty() ? clean(who.teams()) : given;
            String note = given.isEmpty() ? teamsNote(who, teams) : null;
            String id = Ids.next("sa");
            String token = "iaf_" + Ids.next("tok").substring(4) + Ids.next("x").substring(2);
            store.save(new ServiceAccount(id, command.name(), command.roles() == null ? List.of() : command.roles(),
                    teams, Hashes.sha256(token), clock.instant(), scopes));
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("name", command.name());
            ArrayNode granted = data.putArray("scopes");
            scopes.forEach(granted::add);
            ArrayNode actsFor = data.putArray("teams");
            teams.forEach(actsFor::add);
            if (given.isEmpty()) {
                data.put("teamsFrom", "creator");
            }
            events.publish("service-account.created", "service-account", id, who.id(), data);
            return new ServiceAccountCreated(id, token, scopes, teams, note);
        });
    }

    /** What the response says when no teams were given. */
    static String teamsNote(Identity creator, List<String> teams) {
        if (teams.isEmpty()) {
            return "No teams were given and " + creator.name() + " belongs to none: the account acts for no team,"
                    + " so the policy refuses every job it starts; create it again with teams to start jobs";
        }
        return "No teams were given: the account acts for " + creator.name() + "'s teams (" + String.join(", ", teams) + ")";
    }

    private static List<String> clean(List<String> teams) {
        return teams == null ? List.of() : teams.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
