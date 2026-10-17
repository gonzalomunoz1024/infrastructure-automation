package com.iaf.platform.identity.application;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;

import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.inbound.IdentityCommands;
import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

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
            String id = Ids.next("sa");
            String token = "iaf_" + Ids.next("tok").substring(4) + Ids.next("x").substring(2);
            store.save(new ServiceAccount(id, command.name(), command.roles() == null ? List.of() : command.roles(),
                    command.teams() == null ? List.of() : command.teams(), WorkflowYaml.sha256(token), clock.instant()));
            events.publish("service-account.created", "service-account", id, who.id(), null);
            return new ServiceAccountCreated(id, token);
        });
    }
}
