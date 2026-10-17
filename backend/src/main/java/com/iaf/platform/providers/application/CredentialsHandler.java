package com.iaf.platform.providers.application;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.providers.ports.inbound.CredentialsCommands;
import com.iaf.platform.providers.ports.inbound.CredentialsQueries;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/** Credentials by name and type: the value goes to the {@code CredentialsPort} and never comes back. */
@Primary
@Service
@RequiredArgsConstructor
public class CredentialsHandler implements CredentialsCommands, CredentialsQueries {

    public static final String STORED_EVENT = "credential.stored";
    public static final Set<String> TYPES = Set.of("token", "password", "ssh-key", "api-key", "basic", "file");

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_./-]*$");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final CredentialsPort credentials;
    private final EventBus events;

    @Override
    public Mono<CommandAccepted> storeCredential(StoreCredential command) {
        return CurrentIdentity.current().flatMap(who -> {
            if (command == null || command.name() == null || command.name().isBlank()) {
                throw new IllegalArgumentException("name is required: the Vault path, like vsphere/token");
            }
            String name = command.name().trim();
            if (name.startsWith("vault:")) {
                name = name.substring("vault:".length());
            }
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("name is a path of letters, digits, dots, dashes and slashes, like vsphere/token");
            }
            String type = command.type() == null || command.type().isBlank() ? "token" : command.type().trim().toLowerCase();
            if (!TYPES.contains(type)) {
                throw new IllegalArgumentException("type is one of " + TYPES.stream().sorted().toList());
            }
            if (command.value() == null || command.value().isBlank()) {
                throw new IllegalArgumentException("value is required");
            }
            return credentials.store(name, type, command.value(), who.id()).map(info -> {
                ObjectNode data = F.objectNode();
                data.put("name", info.name());
                data.put("type", info.type());
                data.put("by", who.id());
                events.publish(STORED_EVENT, "credential", info.name(), who.id(), data);
                return CommandAccepted.of(CredentialsPort.referenceOf(info.name()), "stored");
            });
        });
    }

    @Override
    public Mono<List<CredentialInfo>> credentials() {
        return credentials.list();
    }
}
